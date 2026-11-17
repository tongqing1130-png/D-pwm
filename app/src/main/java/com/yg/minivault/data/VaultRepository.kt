package com.yg.minivault.data

import com.yg.minivault.crypto.SealedBox
import com.yg.minivault.crypto.VaultCrypto
import com.yg.minivault.crypto.VaultCorruptedException
import com.yg.minivault.crypto.WrongPasswordException
import javax.crypto.SecretKey

/**
 * 保险库的会话大脑：持有解锁后的 DEK、内存中的明文条目，负责读写文件。
 *
 * 线程约定：写操作都在 IO 线程调用，且被 [synchronized] 串起来；
 * 解构成解锁动作的多个入口（主密码 / 生物识别）不会互相覆盖状态。
 */
class VaultRepository(private val store: VaultStore) {

    private var dek: SecretKey? = null
    private var header: VaultHeader? = null
    private var records: List<ItemRecord> = emptyList()
    private var tombstones: List<Tombstone> = emptyList()

    /** 解锁后的明文条目；锁定时清空。 */
    var items: List<VaultItem> = emptyList()
        private set

    /** 解密失败的条目数（正常应为 0，>0 说明文件被改过）。 */
    var corruptedCount: Int = 0
        private set

    fun isInitialized(): Boolean = store.exists()

    fun isUnlocked(): Boolean = dek != null

    // ---------------- 建库 / 解锁 / 锁定 ----------------

    /** 首次创建保险库：派生 KEK、生成 DEK、写入空库。 */
    @Synchronized
    fun createVault(password: CharArray) {
        val salt = VaultCrypto.newSalt()
        val kek = VaultCrypto.deriveKek(password, salt)
        val newDek = VaultCrypto.newDek()
        val wrapped = VaultCrypto.seal(kek, newDek.encoded)

        val h = VaultHeader(
            iterations = VaultCrypto.DEFAULT_ITERATIONS,
            saltB64 = VaultCrypto.b64(salt),
            dekNonceB64 = wrapped.nonceB64,
            dekCtB64 = wrapped.ctB64,
            keyIdB64 = keyIdOf(newDek),
        )
        header = h
        dek = newDek
        records = emptyList()
        tombstones = emptyList()
        items = emptyList()
        corruptedCount = 0
        store.write(VaultDocument(h, emptyList()))
    }

    /** 解锁。密码错误抛 [WrongPasswordException]。已解锁时是空操作（防重复点击）。 */
    @Synchronized
    /** 只校验主密码是否正确，不改变当前解锁状态（自动填充前二次验证用）。 */
    fun verifyPassword(password: CharArray): Boolean {
        val h = header ?: runCatching { store.peekHeader() }.getOrNull() ?: return false
        return runCatching {
            val kek = VaultCrypto.deriveKek(password, VaultCrypto.unb64(h.saltB64), h.iterations)
            VaultCrypto.open(kek, SealedBox(h.dekNonceB64, h.dekCtB64))
            true
        }.getOrDefault(false)
    }

    fun unlock(password: CharArray) {
        if (dek != null) return
        val doc = store.read()
        val h = doc.header
        val salt = VaultCrypto.unb64(h.saltB64)
        val kek = VaultCrypto.deriveKek(password, salt, h.iterations)
        val dekBytes = try {
            VaultCrypto.open(kek, SealedBox(h.dekNonceB64, h.dekCtB64))
        } catch (e: WrongPasswordException) {
            throw WrongPasswordException()
        }
        try {
            adopt(doc, VaultCrypto.dekFromBytes(dekBytes))
        } finally {
            VaultCrypto.wipe(dekBytes)
        }
    }

    /** 用已经解出来的 DEK 字节解锁（生物识别路径）。 */
    @Synchronized
    fun unlockWithDek(dekBytes: ByteArray) {
        if (dek != null) return
        val doc = store.read()
        adopt(doc, VaultCrypto.dekFromBytes(dekBytes))
    }

    private fun adopt(doc: VaultDocument, key: SecretKey) {
        header = doc.header
        dek = key
        records = doc.items
        tombstones = doc.tombstones
        loadItems()
    }

    /** 用当前内存里的密钥重新载入条目（保存后调用）。 */
    fun loadItems() {
        val key = dek ?: return
        val out = ArrayList<VaultItem>(records.size)
        var bad = 0
        records.forEach { rec ->
            try {
                val plain = VaultCrypto.open(key, SealedBox(rec.nonceB64, rec.ctB64), aad(rec.id))
                out.add(
                    VaultJson.decodePayload(
                        id = rec.id,
                        type = rec.type,
                        favorite = rec.favorite,
                        updatedAt = rec.updatedAt,
                        json = String(plain, Charsets.UTF_8),
                    ),
                )
                VaultCrypto.wipe(plain)
            } catch (e: Exception) {
                bad++
            }
        }
        items = out.sortedWith(compareByDescending<VaultItem> { it.favorite }.thenBy { it.title.lowercase() })
        corruptedCount = bad
    }

    /** 锁定：清空 DEK 与明文。 */
    @Synchronized
    fun lock() {
        dek = null
        items = emptyList()
        records = emptyList()
        tombstones = emptyList()
        header = null
        corruptedCount = 0
    }

    // ---------------- 增删改 ----------------

    @Synchronized
    fun save(item: VaultItem): VaultItem {
        val key = dek ?: throw IllegalStateException("保险库已锁定")
        val stamped = item.copy(updatedAt = System.currentTimeMillis())
        records = records.filterNot { it.id == stamped.id } + sealRecord(key, stamped)
        // 用户把它又加回来了 → 墓碑作废，否则下次同步会被对面删掉
        tombstones = tombstones.filterNot { it.id == stamped.id }
        persist()
        loadItems()
        return stamped
    }

    @Synchronized
    fun delete(id: String) {
        records = records.filterNot { it.id == id }
        tombstones = tombstones.filterNot { it.id == id } + Tombstone(id, System.currentTimeMillis())
        persist()
        loadItems()
    }

    @Synchronized
    fun setFavorite(id: String, favorite: Boolean) {
        val current = items.firstOrNull { it.id == id } ?: return
        save(current.copy(favorite = favorite))
    }

    /** 批量导入（Bitwarden / 备份），一次落盘。 */
    @Synchronized
    fun importItems(list: List<VaultItem>): Int {
        val sessionKey = dek ?: throw IllegalStateException("保险库已锁定")
        if (list.isEmpty()) return 0
        val merged = LinkedHashMap<String, ItemRecord>()
        records.forEach { merged[it.id] = it }
        // 按「类型 + 标题 + 用户名」去重：同一批数据重复导入不会长出副本
        val seen = items.mapTo(HashSet()) { fingerprint(it) }
        var added = 0
        list.forEach { item ->
            if (!seen.add(fingerprint(item))) return@forEach
            val stamped = item.copy(updatedAt = System.currentTimeMillis())
            merged[stamped.id] = sealRecord(sessionKey, stamped)
            added++
        }
        records = merged.values.toList()
        persist()
        loadItems()
        return added
    }

    private fun fingerprint(item: VaultItem): String =
        // 标题与用户名都为空时无法区分，用 id 兜底，避免把一批空壳条目折叠成一条
        if (item.title.isBlank() && item.username.isBlank()) {
            "id:${item.id}"
        } else {
            "${item.type.name}|${item.title.trim()}|${item.username.trim()}"
        }

    /** 改主密码：只重新包裹 DEK，条目不重新加密（等价 Bitwarden 的做法）。 */
    @Synchronized
    fun changePassword(newPassword: CharArray) {
        val key = dek ?: throw IllegalStateException("保险库已锁定")
        val current = header ?: throw IllegalStateException("保险库未初始化")
        val salt = VaultCrypto.newSalt()
        val kek = VaultCrypto.deriveKek(newPassword, salt)
        val wrapped = VaultCrypto.seal(kek, key.encoded)
        // 主密码换了但 DEK 没换 → 生物识别那层仍然有效，保留
        // keyId 跟着 DEK 走，不随主密码变化 —— 换密码后另一台仍能正常合并
        header = current.copy(
            iterations = VaultCrypto.DEFAULT_ITERATIONS,
            saltB64 = VaultCrypto.b64(salt),
            dekNonceB64 = wrapped.nonceB64,
            dekCtB64 = wrapped.ctB64,
        )
        persist()
    }

    // ---------------- 生物识别绑定 ----------------

    /** 锁定状态下 header 是空的，这里按需从文件里读一次（只读头，不碰密文）。 */
    private fun ensureHeader() {
        if (header == null) header = runCatching { store.peekHeader() }.getOrNull()
    }

    fun hasBiometricBinding(): Boolean {
        ensureHeader()
        return !header?.biometricCtB64.isNullOrEmpty()
    }

    fun biometricIv(): ByteArray? {
        ensureHeader()
        return header?.biometricIvB64?.takeIf { it.isNotEmpty() }?.let { VaultCrypto.unb64(it) }
    }

    fun biometricCt(): ByteArray? {
        ensureHeader()
        return header?.biometricCtB64?.takeIf { it.isNotEmpty() }?.let { VaultCrypto.unb64(it) }
    }

    /** 供外层做 Keystore 加密的 DEK 字节副本。 */
    fun dekBytesCopy(): ByteArray? = dek?.encoded

    @Synchronized
    fun saveBiometricBinding(iv: ByteArray, ct: ByteArray) {
        val h = header ?: return
        header = h.copy(
            biometricIvB64 = VaultCrypto.b64(iv),
            biometricCtB64 = VaultCrypto.b64(ct),
        )
        persist()
    }

    @Synchronized
    fun clearBiometricBinding() {
        val h = header ?: return
        header = h.copy(biometricIvB64 = "", biometricCtB64 = "")
        persist()
    }

    // ---------------- 备份 ----------------

    /** 导出当前库的 JSON 文本（仍由主密码保护，可在新设备导入）。 */
    @Synchronized
    fun exportText(): String {
        val h = header ?: throw IllegalStateException("保险库未解锁")
        return VaultJson.encode(VaultDocument(h, records))
    }

    /**
     * 从备份文本导入。会校验密码是否匹配该文件。
     * 成功后当前库被替换，并自动解锁。
     */
    @Synchronized
    fun importText(text: String, password: CharArray) {
        val doc = VaultJson.decode(text)
        val salt = VaultCrypto.unb64(doc.header.saltB64)
        val kek = VaultCrypto.deriveKek(password, salt, doc.header.iterations)
        val dekBytes = VaultCrypto.open(kek, SealedBox(doc.header.dekNonceB64, doc.header.dekCtB64))
        val key = VaultCrypto.dekFromBytes(dekBytes)
        // 抽第一条验证一下，能解开说明整份备份真实可用
        doc.items.firstOrNull()?.let { rec ->
            VaultCrypto.open(key, SealedBox(rec.nonceB64, rec.ctB64), aad(rec.id))
        }
        VaultCrypto.wipe(dekBytes)
        store.write(doc)
        header = doc.header
        dek = key
        records = doc.items
        loadItems()
        if (corruptedCount > 0) throw VaultCorruptedException("备份中有 $corruptedCount 条无法解密")
    }

    // ---------------- WebDAV / 原始密文 ----------------

    /** 整份 vault.json 的原始文本（已经是密文，可以直接上传到云端）。 */
    @Synchronized
    fun rawText(): String = store.readRaw()

    /** 用云端下回来的密文覆盖本地库；随后必须重新解锁，且密码必须是那份备份的主密码。 */
    @Synchronized
    fun replaceRaw(text: String) {
        // 先校验能解析，避免把坏文件写进去把库搞丢
        val doc = VaultJson.decode(text)
        store.write(doc)
        dek = null
        header = null
        records = emptyList()
        tombstones = emptyList()
        items = emptyList()
        corruptedCount = 0
    }

    // ---------------- 多设备同步 ----------------

    /** 同一份保险库的指纹：salt 与被包裹的 DEK 都一样，才说明两台设备共用同一把 DEK，可以合并。 */
    fun lineage(): String {
        val h = header ?: return ""
        return h.keyIdB64.ifEmpty { h.saltB64 + "|" + h.dekCtB64 }
    }

    /** DEK 的 SHA-256 前缀，只作指纹用（不能反推密钥）。 */
    private fun keyIdOf(key: SecretKey): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.encoded)
        VaultCrypto.b64(digest).take(22)
    }.getOrDefault("")

    data class MergeReport(
        val conflict: Boolean,
        val added: Int = 0,
        val updated: Int = 0,
        val removed: Int = 0,
    )

    /**
     * 把云端那份密文与本地合并（按条目 id + updatedAt 取新，墓碑优先）。
     * 全程只搬运密文，不重新加密 —— 两台设备本来就共用同一把 DEK。
     */
    @Synchronized
    fun mergeFrom(remoteText: String): MergeReport {
        dek ?: throw IllegalStateException("请先解锁，再点同步")
        val localHeader = header ?: throw IllegalStateException("保险库未初始化")
        val remote = VaultJson.decode(remoteText)

        // 不是同一份保险库：合并出来的密文在对面解不开，必须让用户做选择
        val remoteLineage = remote.header.keyIdB64.ifEmpty {
            remote.header.saltB64 + "|" + remote.header.dekCtB64
        }
        val localLineage = localHeader.keyIdB64.ifEmpty {
            localHeader.saltB64 + "|" + localHeader.dekCtB64
        }
        if (remoteLineage != localLineage) {
            return MergeReport(conflict = true)
        }

        val localById = records.associateBy { it.id }
        val remoteById = remote.items.associateBy { it.id }
        var added = 0
        var updated = 0
        val merged = LinkedHashMap<String, ItemRecord>()
        (localById.keys + remoteById.keys).forEach { id ->
            val l = localById[id]
            val r = remoteById[id]
            when {
                l == null && r != null -> { added++; merged[id] = r }
                l != null && r == null -> merged[id] = l
                l != null && r != null ->
                    if (r.updatedAt > l.updatedAt) { updated++; merged[id] = r } else merged[id] = l
            }
        }

        // 合并墓碑（取更晚的删除时间）
        val tomb = LinkedHashMap<String, Long>()
        tombstones.forEach { tomb[it.id] = it.deletedAt }
        remote.tombstones.forEach { t -> tomb[t.id] = maxOf(tomb[t.id] ?: 0L, t.deletedAt) }
        var removed = 0
        tomb.forEach { (id, at) ->
            val rec = merged[id] ?: return@forEach
            if (at >= rec.updatedAt) { merged.remove(id); removed++ }
        }

        // 墓碑保留 180 天足够覆盖“离线很久的设备”再回来同步
        val cutoff = System.currentTimeMillis() - 180L * 24 * 60 * 60 * 1000
        records = merged.values.toList()
        tombstones = tomb.filterValues { it >= cutoff }.map { Tombstone(it.key, it.value) }
        persist()
        loadItems()
        return MergeReport(conflict = false, added = added, updated = updated, removed = removed)
    }

    // ---------------- 内部 ----------------

    private fun sealRecord(key: SecretKey, item: VaultItem): ItemRecord {
        val payload = VaultJson.encodePayload(item).toByteArray(Charsets.UTF_8)
        val box = VaultCrypto.seal(key, payload, aad(item.id))
        VaultCrypto.wipe(payload)
        return ItemRecord(
            id = item.id,
            type = item.type.name,
            favorite = item.favorite,
            updatedAt = item.updatedAt,
            nonceB64 = box.nonceB64,
            ctB64 = box.ctB64,
        )
    }

    @Synchronized
    private fun persist() {
        val h = header ?: throw IllegalStateException("保险库未初始化")
        store.write(VaultDocument(h, records, tombstones))
    }

    /** AAD 把密文绑定到条目 id，防止把 A 的密文挪到 B 上。 */
    private fun aad(id: String): ByteArray = "$id|v${VaultHeader.FORMAT_VERSION}".toByteArray(Charsets.UTF_8)
}