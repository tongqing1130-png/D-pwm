package com.yg.minivault.data

import com.yg.minivault.crypto.VaultCorruptedException
import org.json.JSONArray
import org.json.JSONObject

/**
 * 文件头：KDF 参数 + 被 KEK 包裹的 DEK。
 * 这些都不可用于解密（没有主密码），可以明文存放。
 * 生物识别新增两段：被 AndroidKeyStore 密钥再次包裹的 DEK。
 */
data class VaultHeader(
    val version: Int = FORMAT_VERSION,
    val kdf: String = "PBKDF2WithHmacSHA256",
    val iterations: Int,
    val saltB64: String,
    val dekNonceB64: String,
    val dekCtB64: String,
    val biometricIvB64: String = "",
    val biometricCtB64: String = "",
    /** DEK 的指纹：用来判断两台设备是不是同一份保险库。换主密码不会变。 */
    val keyIdB64: String = "",
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

/** 磁盘上的一条记录：元信息明文，内容全部密文。 */
data class ItemRecord(
    val id: String,
    val type: String,
    val favorite: Boolean,
    val updatedAt: Long,
    val nonceB64: String,
    val ctB64: String,
)

/** 删除墓碑：多设备合并时用它把「已在别处删掉」的条目也删掉，否则会被对面复活。 */
data class Tombstone(val id: String, val deletedAt: Long)

data class VaultDocument(
    val header: VaultHeader,
    val items: List<ItemRecord>,
    val tombstones: List<Tombstone> = emptyList(),
)

/** vault.json 的编解码。用 org.json（Android 平台自带，零额外依赖）。 */
object VaultJson {

    private const val K_HEADER = "header"
    private const val K_ITEMS = "items"
    private const val K_TOMBSTONES = "tombstones"

    fun encode(doc: VaultDocument): String {
        val root = JSONObject()
        root.put(
            K_HEADER,
            JSONObject().apply {
                put("version", doc.header.version)
                put("kdf", doc.header.kdf)
                put("iterations", doc.header.iterations)
                put("salt", doc.header.saltB64)
                put("dekNonce", doc.header.dekNonceB64)
                put("dekCt", doc.header.dekCtB64)
                if (doc.header.keyIdB64.isNotEmpty()) put("keyId", doc.header.keyIdB64)
                if (doc.header.biometricCtB64.isNotEmpty()) {
                    put("bioIv", doc.header.biometricIvB64)
                    put("bioCt", doc.header.biometricCtB64)
                }
            },
        )
        val arr = JSONArray()
        doc.items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("type", item.type)
                    put("favorite", item.favorite)
                    put("updatedAt", item.updatedAt)
                    put("nonce", item.nonceB64)
                    put("ct", item.ctB64)
                },
            )
        }
        root.put(K_ITEMS, arr)
        if (doc.tombstones.isNotEmpty()) {
            val tombs = JSONArray()
            doc.tombstones.forEach { t ->
                tombs.put(JSONObject().apply { put("id", t.id); put("at", t.deletedAt) })
            }
            root.put(K_TOMBSTONES, tombs)
        }
        return root.toString(2)
    }

    fun decode(text: String): VaultDocument {
        try {
            val root = JSONObject(text)
            val h = root.getJSONObject(K_HEADER)
            val header = VaultHeader(
                version = h.optInt("version", VaultHeader.FORMAT_VERSION),
                kdf = h.optString("kdf", "PBKDF2WithHmacSHA256"),
                iterations = h.getInt("iterations"),
                saltB64 = h.getString("salt"),
                dekNonceB64 = h.getString("dekNonce"),
                dekCtB64 = h.getString("dekCt"),
                biometricIvB64 = h.optString("bioIv", ""),
                biometricCtB64 = h.optString("bioCt", ""),
                keyIdB64 = h.optString("keyId", ""),
            )
            val arr = root.optJSONArray(K_ITEMS) ?: JSONArray()
            val items = ArrayList<ItemRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(
                    ItemRecord(
                        id = o.getString("id"),
                        type = o.optString("type", "LOGIN"),
                        favorite = o.optBoolean("favorite", false),
                        updatedAt = o.optLong("updatedAt", 0L),
                        nonceB64 = o.getString("nonce"),
                        ctB64 = o.getString("ct"),
                    ),
                )
            }
            val tombArr = root.optJSONArray(K_TOMBSTONES) ?: JSONArray()
            val tombstones = ArrayList<Tombstone>(tombArr.length())
            for (i in 0 until tombArr.length()) {
                val o = tombArr.getJSONObject(i)
                tombstones.add(Tombstone(o.getString("id"), o.optLong("at", 0L)))
            }
            return VaultDocument(header, items, tombstones)
        } catch (e: Exception) {
            throw VaultCorruptedException()
        }
    }

    // ---- 条目明文载荷 ----

    fun encodePayload(item: VaultItem): String = JSONObject().apply {
        put("title", item.title)
        put("username", item.username)
        put("password", item.password)
        put("url", item.url)
        put("notes", item.notes)
        put("totp", item.totp)
    }.toString()

    fun decodePayload(id: String, type: String, favorite: Boolean, updatedAt: Long, json: String): VaultItem {
        val o = JSONObject(json)
        return VaultItem(
            id = id,
            type = ItemType.fromName(type),
            favorite = favorite,
            updatedAt = updatedAt,
            title = o.optString("title", ""),
            username = o.optString("username", ""),
            password = o.optString("password", ""),
            url = o.optString("url", ""),
            notes = o.optString("notes", ""),
            totp = o.optString("totp", ""),
        )
    }
}