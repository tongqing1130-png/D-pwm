package com.yg.minivault.net

import com.yg.minivault.data.ItemType
import com.yg.minivault.data.VaultItem
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BitwardenException(message: String) : Exception(message)

/**
 * 在应用内直接登录 Bitwarden（官方兼容流程）并把条目解密成本地明文，随后由
 * [com.yg.minivault.data.VaultRepository.importItems] 用自己的密钥重新加密落盘。
 *
 * 密码学流程（与官方客户端一致）：
 *   masterKey = PBKDF2-SHA256(主密码, email.trim().lowercase(), 迭代次数, 32B)
 *   (encKey, macKey) = HKDF-SHA256(masterKey, info="enc"/"mac")
 *   Key 字段（EncString 2.iv|ct|mac）用上面两个键解开 → userKey（64B: enc|mac）
 *   (encKey, macKey) = HKDF-SHA256(userKey, info="enc"/"mac")
 *   条目的每个字符串都是 EncString，用同一套键解密；若 cipher 带自己的 key 字段，
 *   先用 userKey 解开该条目的 key，再用它解字段（Bitwarden 的 per-item key）。
 *
 * HKDF 这一层历史上出现过「只做 Expand」和「Extract(salt="bitwarden")+Expand」两种实现，
 * 因此这里不写死：用账户 Key 字段的 HMAC 校验自动判定该账号用的是哪一种。
 *
 * 主密码只在本函数调用期间存在，不落盘、不外传（只发给你填的那台服务器）。
 */
object BitwardenClient {
    /** 声明的客户端版本；服务端用它判断加解密协商是否安全，太旧会被要求升级。 */
    private const val CLIENT_VERSION = "2025.6.2"


    /** 老服务器可能没有 prelogin，默认按官方当前的 600000 次迭代。 */
    private const val DEFAULT_ITERATIONS = 600_000

    fun importAll(
        baseUrl: String,
        email: String,
        password: CharArray,
        twoFactorCode: String = "",
        deviceIdentifier: String,
        onProgress: (String) -> Unit = {},
    ): List<VaultItem> {
        val base = normalize(baseUrl)
        val account = email.trim()
        if (account.isEmpty()) throw BitwardenException("请填写 Bitwarden 邮箱")
        if (password.isEmpty()) throw BitwardenException("请填写 Bitwarden 主密码")

        onProgress("正在获取账户参数…")
        val (kdf, iterations) = fetchPrelogin(base, account)
        if (kdf == 1) {
            throw BitwardenException(
                "这个账号用的是 Argon2id KDF，当前版本只支持 PBKDF2。\n" +
                    "可以先用官方客户端/网页端把 KDF 临时切换成 PBKDF2（600000 轮）再导入，导入完再切回去。" +
                    "切换 KDF 不影响已存数据。",
            )
        }

        onProgress("正在派生主密钥（$iterations 次迭代）…")
        val masterKey = pbkdf2(password, account.lowercase(), iterations)

        // 服务器要的是 masterPasswordHash（PBKDF2 第二轮，1 次迭代），不是主密码本身
        val masterPasswordHash = hashMasterKey(masterKey, password)
        onProgress("正在登录…")
        val token = fetchToken(base, account, masterPasswordHash, twoFactorCode, deviceIdentifier)

        val stretch = detectStretch(token.key, masterKey)
        val keys: Keys = if (token.key.isEmpty()) {
            // 老账号：没有 Key 字段，主密钥经 HKDF 直接当数据密钥
            stretch(masterKey)
        } else {
            val userKey = decryptRaw(token.key, stretch(masterKey))
                ?: throw BitwardenException("无法解密账户密钥（主密码可能不正确，或账号使用了不支持的 KDF）")
            // 关键：账户 Key 解开后是 64 字节 = encKey(32) || macKey(32)，**直接切分**，
            // 不能再走一遍 HKDF（之前多走一步导致所有条目都解不开）
            splitUserKey(userKey)
        }

        onProgress("正在拉取保险库…")
        val ciphers = fetchCiphers(base, token.accessToken)

        val items = ArrayList<VaultItem>()
        var skipped = 0
        for (i in 0 until ciphers.length()) {
            val cipher = ciphers.optJSONObject(i) ?: continue
            val favorite = cipher.optBoolean("favorite", false)

            // per-item key：有就先解出来，字段用它而不是账户键
            val ownKey = cipher.optString("key").takeIf { it.isNotEmpty() }
            val itemKeys = ownKey?.let { decryptRaw(it, keys) }?.let { splitUserKey(it) }

            val name = decrypt(cipher.optString("name"), itemKeys ?: keys)
            val notes = decrypt(cipher.optString("notes"), itemKeys ?: keys)

            when (cipher.optInt("type", 1)) {
                1 -> {
                    val login = cipher.optJSONObject("login")
                    val uris = login?.optJSONArray("uris")
                    val rawUri = if (uris != null && uris.length() > 0) {
                        uris.optJSONObject(0)?.optString("uri").orEmpty()
                    } else {
                        ""
                    }
                    val k = itemKeys ?: keys
                    // uri 也是 EncString；老数据里可能是明文，解不开就原样保留
                    val url = decrypt(rawUri, k).ifEmpty {
                        if (rawUri.startsWith("http", ignoreCase = true)) rawUri else ""
                    }
                    items += VaultItem(
                        type = ItemType.LOGIN,
                        favorite = favorite,
                        title = name.ifBlank { "（无标题）" },
                        username = decrypt(login?.optString("username").orEmpty(), k),
                        password = decrypt(login?.optString("password").orEmpty(), k),
                        url = url,
                        notes = notes,
                        totp = decrypt(login?.optString("totp").orEmpty(), k),
                    )
                }
                2 -> items += VaultItem(
                    type = ItemType.NOTE,
                    favorite = favorite,
                    title = name.ifBlank { "（无标题）" },
                    notes = notes,
                )
                3 -> {
                    val k = itemKeys ?: keys
                    val card = cipher.optJSONObject("card")
                    val details = listOfNotNull(
                        decrypt(card?.optString("number").orEmpty(), k).takeIf { it.isNotBlank() }?.let { "卡号：$it" },
                        decrypt(card?.optString("expMonth").orEmpty(), k).takeIf { it.isNotBlank() }?.let { "有效期月：$it" },
                        decrypt(card?.optString("expYear").orEmpty(), k).takeIf { it.isNotBlank() }?.let { "有效期年：$it" },
                        decrypt(card?.optString("code").orEmpty(), k).takeIf { it.isNotBlank() }?.let { "安全码：$it" },
                        decrypt(card?.optString("cardholderName").orEmpty(), k).takeIf { it.isNotBlank() }?.let { "持卡人：$it" },
                        decrypt(card?.optString("brand").orEmpty(), k).takeIf { it.isNotBlank() }?.let { "卡组织：$it" },
                    ).joinToString("\n")
                    items += VaultItem(
                        type = ItemType.CARD,
                        favorite = favorite,
                        title = name.ifBlank { "（无标题）" },
                        notes = listOf(details, notes).filter { it.isNotBlank() }.joinToString("\n\n"),
                    )
                }
                else -> skipped++
            }
        }
        onProgress("已解密 ${items.size} 条" + if (skipped > 0) "（跳过 $skipped 条未支持类型）" else "")
        return items
    }

    // ---------------------------------------------------------------- 网络

    private data class Token(val accessToken: String, val key: String)

    private fun normalize(baseUrl: String): String {
        val raw = baseUrl.trim().ifEmpty { "https://vault.bitwarden.com" }
        val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        return withScheme.trimEnd('/')
    }

    /** 返回 (kdf, iterations)：kdf 0=PBKDF2, 1=Argon2id。 */
    private fun fetchPrelogin(base: String, email: String): Pair<Int, Int> {
        val body = JSONObject().put("email", email).toString()
        val candidates = listOf(
            "$base/identity/accounts/prelogin/password",
            "$base/identity/accounts/prelogin",
        )
        for (url in candidates) {
            val response = runCatching { postJson(url, body) }.getOrNull() ?: continue
            val json = runCatching { JSONObject(response) }.getOrNull() ?: continue
            val kdf = json.optInt("Kdf", json.optInt("kdf", 0))
            val iterations = json.optInt("KdfIterations", json.optInt("kdfIterations", 0))
            return kdf to (if (iterations > 0) iterations else DEFAULT_ITERATIONS)
        }
        return 0 to DEFAULT_ITERATIONS
    }

    private fun fetchToken(
        base: String,
        email: String,
        passwordHash: String,
        twoFactorCode: String,
        deviceIdentifier: String,
    ): Token {
        val fields = mutableListOf(
            "grant_type" to "password",
            "username" to email,
            "password" to passwordHash,
            "scope" to "api offline_access",
            "client_id" to "cli",
            "deviceType" to "10",
            "deviceName" to "MiniVault",
            // 必须固定：新设备验证码绑定首次尝试的设备，每次随机会导致验证码永远不对
            "deviceIdentifier" to deviceIdentifier,
        )
        if (twoFactorCode.isNotBlank()) {
            val otp = twoFactorCode.trim()
            // 两种校验共用一个输入框：
            //  twoFactorToken → 账号开了 2FA（TOTP）
            //  newDeviceOtp   → Bitwarden 的「新设备邮件验证」
            fields += "twoFactorToken" to otp
            fields += "twoFactorProvider" to "0"
            fields += "twoFactorRemember" to "0"
            fields += "newDeviceOtp" to otp
        }
        val form = fields.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }

        val (code, raw) = postForm("$base/identity/connect/token", form)
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val accessToken = json?.optString("access_token").orEmpty()
        if (code in 200..299 && accessToken.isNotEmpty()) {
            return Token(accessToken, json?.optString("Key").orEmpty())
        }

        val error = json?.optString("error").orEmpty()
        val description = json?.optString("error_description").orEmpty()
        val needsTwoFactor = raw.contains("TwoFactorProviders", ignoreCase = true) ||
            description.contains("two factor", ignoreCase = true) ||
            description.contains("two-step", ignoreCase = true)
        if (raw.contains("New device verification", ignoreCase = true) ||
            description.contains("new device", ignoreCase = true)
        ) {
            throw BitwardenException(
                "Bitwarden 要求验证这台新设备：验证码已发到邮箱。\n" +
                    "把 6 位验证码填进「两步验证 / 新设备验证码」再点一次登录（设备标识已固定，重试有效）。",
            )
        }
        if (needsTwoFactor) {
            throw BitwardenException("该账号启用了两步验证：请在设置里填写当前 6 位验证码后再试。")
        }
        throw BitwardenException(
            when {
                error == "invalid_grant" || description.contains("invalid_username_or_password") ->
                    "Bitwarden 邮箱或主密码不正确"
                else -> "登录失败（$code）@ ${hostOf("$base/identity/connect/token")}：" +
                    description.ifEmpty { raw.take(200) }
            },
        )
    }

    private fun fetchCiphers(base: String, accessToken: String): org.json.JSONArray {
        // 官方云（vault.bitwarden.com）与 Vaultwarden 都在 /api/sync 暴露同步接口，
        // 只有把 base 直接填成 API 根（如 https://api.bitwarden.com）时才走 /sync。
        // 之前只试 /sync，自建服务就会命中前端路由，拿回一张 404 HTML 页面。
        val candidates = listOf("$base/api/sync", "$base/sync")
        var lastError: BitwardenException? = null
        for (url in candidates) {
            val text = try {
                val connection = openConnection(url, "GET")
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                connection.setRequestProperty("Device-Type", "10")
                readAll(connection)
            } catch (e: BitwardenException) {
                lastError = e
                continue
            } catch (e: Exception) {
                lastError = BitwardenException("拉取保险库失败：${e.message}")
                continue
            }
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: throw BitwardenException(
                    "拉取保险库失败（$url 返回的不是 JSON）：${text.take(200)}",
                )
            return json.optJSONArray("ciphers") ?: org.json.JSONArray()
        }
        throw lastError?.let { BitwardenException(htmlHint(it.message ?: "")) }
            ?: BitwardenException("拉取保险库失败：无法连接服务器")
    }

    /** 若服务端返回的是网页（HTML）而不是 JSON，说明地址填成了站点根/前端路由。 */
    private fun htmlHint(raw: String): String =
        if (raw.contains("<html", ignoreCase = true) || raw.contains("<!doctype", ignoreCase = true)) {
            "服务器返回的是网页，不是 Bitwarden API。\n" +
                "请把「服务器」填成站点根地址（官方 https://vault.bitwarden.com，或你的 https://你的域名），" +
                "不要带 /#/、/api、/vault 之类的路径。\n\n原始响应：" + raw.take(120)
        } else {
            raw
        }

    private fun postJson(url: String, body: String): String {
        val connection = openConnection(url, "POST")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return readAll(connection)
    }

    private fun postForm(url: String, body: String): Pair<Int, String> {
        val connection = openConnection(url, "POST")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        return code to text
    }

    private fun hostOf(url: String): String = runCatching { java.net.URL(url).host }.getOrDefault(url)

    private fun openConnection(url: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        // Bitwarden 服务端（官方云 / Vaultwarden）都要求声明客户端身份：
        // 缺 Bitwarden-Client-Version 会直接被拒 400 "No client version header found…"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Bitwarden-Client-Name", "cli")
        connection.setRequestProperty("Bitwarden-Client-Version", CLIENT_VERSION)
        connection.setRequestProperty("Device-Type", "10")
        connection.setRequestProperty("User-Agent", "MiniVault-android/$CLIENT_VERSION")
        return connection
    }

    private fun readAll(connection: HttpURLConnection): String {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        if (code !in 200..299) {
            throw BitwardenException("服务器返回 $code：${text.take(200)}")
        }
        return text
    }

    // ---------------------------------------------------------------- 密码学

    internal class Keys(val encKey: ByteArray, val macKey: ByteArray)

    private fun pbkdf2(password: CharArray, salt: String, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt.toByteArray(Charsets.UTF_8), iterations, 256)
        return try {
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Bitwarden 登录用的 hashMasterKey：
     * PBKDF2-HMAC-SHA256(masterKey 作为密码, 主密码字符串作为盐, 1 轮, 32B) → Base64。
     * 1 轮时 T1 = HMAC(masterKey, salt || 0x00000001)，所以直接用一次 HMAC 即可。
     */
    /**
     * 账户 Key 解开后是 64 字节：前 32 = encKey，后 32 = macKey（Bitwarden 的 SymmetricCryptoKey）。
     * 只有 32 字节的极端情况（老式单密钥）才退回 HKDF。
     */
    internal fun splitUserKey(userKey: ByteArray): Keys = when (userKey.size) {
        64 -> Keys(userKey.copyOfRange(0, 32), userKey.copyOfRange(32, 64))
        32 -> Keys(userKey.copyOfRange(0, 32), hmac(userKey, "mac".toByteArray(Charsets.UTF_8)))
        else -> throw BitwardenException("账户密钥长度异常（${userKey.size} 字节）")
    }

    internal fun hashMasterKey(masterKey: ByteArray, password: CharArray): String {
        val salt = String(password).toByteArray(Charsets.UTF_8)
        val block = salt + byteArrayOf(0, 0, 0, 1)
        return Base64.getEncoder().encodeToString(hmac(masterKey, block))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** RFC 5869 的 Expand 一步（输出 32B 就只要 T(1)）。 */
    private fun expand(prk: ByteArray, info: String): ByteArray =
        hmac(prk, info.toByteArray(Charsets.UTF_8) + byteArrayOf(1))

    /** 变体 A：直接用 key 当 PRK 做 Expand。 */
    internal fun stretchExpandOnly(key: ByteArray): Keys = Keys(expand(key, "enc"), expand(key, "mac"))

    /** 变体 B：先 Extract（salt="bitwarden"）再 Expand。 */
    internal fun stretchWithSalt(key: ByteArray): Keys {
        val prk = hmac("bitwarden".toByteArray(Charsets.UTF_8), key)
        return Keys(expand(prk, "enc"), expand(prk, "mac"))
    }

    /**
     * 判定该账号用的是哪种 HKDF 变体：
     * 拿账户 Key 字段做试验，谁的 HMAC 校验通过就用谁；没有 Key 字段（老账号）默认变体 A。
     */
    internal fun detectStretch(keyField: String, masterKey: ByteArray): (ByteArray) -> Keys {
        if (keyField.isEmpty()) return ::stretchExpandOnly
        for (candidate in listOf(::stretchExpandOnly, ::stretchWithSalt)) {
            if (decryptRaw(keyField, candidate(masterKey)) != null) return candidate
        }
        // 两种都验不过：仍返回 A，让上层抛「无法解密账户密钥」，错误信息更准确
        return ::stretchExpandOnly
    }

    internal fun decrypt(enc: String?, keys: Keys): String {
        if (enc.isNullOrEmpty()) return ""
        val raw = decryptRaw(enc, keys) ?: return ""
        return String(raw, Charsets.UTF_8)
    }

    /** 解密一个 EncString。校验不过返回 null（调用方据此判定密钥不对）。 */
    internal fun decryptRaw(enc: String, keys: Keys): ByteArray? {
        val typeText = enc.substringBefore('.', "")
        val type = typeText.toIntOrNull() ?: return null
        val parts = enc.substringAfter('.', "").split('|')
        return runCatching {
            when (type) {
                // 0 = AesCbc256_B64（无 MAC，老数据）
                0 -> {
                    if (parts.size < 2) return null
                    aesCbc(keys.encKey, parts[0], parts[1])
                }
                // 2 = AesCbc256_HmacSha256_B64
                2 -> {
                    if (parts.size < 3) return null
                    val iv = Base64.getDecoder().decode(parts[0])
                    val ct = Base64.getDecoder().decode(parts[1])
                    val macBytes = Base64.getDecoder().decode(parts[2])
                    val expected = hmac(keys.macKey, iv + ct)
                    if (!MessageDigest.isEqual(expected, macBytes)) return null
                    aesCbc(keys.encKey, parts[0], parts[1])
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun aesCbc(key: ByteArray, ivB64: String, ctB64: String): ByteArray {
        val iv = Base64.getDecoder().decode(ivB64)
        val ct = Base64.getDecoder().decode(ctB64)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ct)
    }
}