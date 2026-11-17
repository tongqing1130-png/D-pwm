package com.yg.minivault.crypto

import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP 参数（兼容 Google Authenticator 的 otpauth:// URI）。
 */
data class TotpSpec(
    val secret: ByteArray,
    val digits: Int = 6,
    val period: Int = 30,
    val algorithm: String = "SHA1",
) {

    /** 生成 [atMillis] 时刻的验证码。 */
    fun code(atMillis: Long = System.currentTimeMillis()): String {
        val counter = atMillis / 1000L / period
        val mac = Mac.getInstance("Hmac$algorithm")
        mac.init(SecretKeySpec(secret, "RAW"))
        val hash = mac.doFinal(counter.toCounterBytes())
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val mod = POWER10[digits.coerceIn(6, 8)]
        return (binary % mod).toString().padStart(digits, '0')
    }

    /** 当前验证码还剩多少秒。 */
    fun remainingSeconds(atMillis: Long = System.currentTimeMillis()): Int {
        val elapsed = (atMillis / 1000L) % period
        return (period - elapsed).toInt()
    }

    private fun Long.toCounterBytes(): ByteArray {
        val out = ByteArray(8)
        var v = this
        for (i in 7 downTo 0) {
            out[i] = (v and 0xff).toByte()
            v = v ushr 8
        }
        return out
    }

    private companion object {
        val POWER10 = intArrayOf(1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000)
    }
}

object Totp {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** 解析 otpauth:// 链接或裸 Base32 密钥；无法识别时返回 null。 */
    fun parse(raw: String?): TotpSpec? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return if (text.startsWith("otpauth://", ignoreCase = true)) parseUri(text) else parseSecret(text)
    }

    fun current(raw: String?, atMillis: Long = System.currentTimeMillis()): String? =
        parse(raw)?.code(atMillis)

    fun remaining(raw: String?, atMillis: Long = System.currentTimeMillis()): Int =
        parse(raw)?.remainingSeconds(atMillis) ?: 0

    private fun parseSecret(secret: String, digits: Int = 6, period: Int = 30, algorithm: String = "SHA1"): TotpSpec? {
        val bytes = base32Decode(secret) ?: return null
        return TotpSpec(bytes, digits, period, algorithm)
    }

    private fun parseUri(text: String): TotpSpec? {
        // 只支持时间型（totp）；hotp 是基于计数器的，语义不同，直接拒绝
        val type = text.substringAfter("otpauth://").substringBefore('?').substringBefore('/').lowercase()
        if (type != "totp") return null
        val query = text.substringAfter('?', "")
        val params = query.split('&').filter { it.contains('=') }.associate { pair ->
            val key = pair.substringBefore('=').lowercase()
            val value = runCatching {
                java.net.URLDecoder.decode(pair.substringAfter('='), "UTF-8")
            }.getOrDefault(pair.substringAfter('='))
            key to value
        }
        val secret = params["secret"] ?: return null
        val digits = (params["digits"]?.toIntOrNull() ?: 6).coerceIn(6, 8)
        val period = (params["period"]?.toIntOrNull() ?: 30).coerceIn(15, 120)
        val algorithm = when (params["algorithm"]?.uppercase()) {
            "SHA256" -> "SHA256"
            "SHA512" -> "SHA512"
            else -> "SHA1"
        }
        return parseSecret(secret, digits, period, algorithm)
    }

    fun base32Decode(input: String): ByteArray? {
        val clean = input.replace(" ", "").replace("=", "").uppercase()
        if (clean.isEmpty()) return null
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val index = ALPHABET.indexOf(c)
            if (index < 0) return null
            buffer = (buffer shl 5) or index
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }
}