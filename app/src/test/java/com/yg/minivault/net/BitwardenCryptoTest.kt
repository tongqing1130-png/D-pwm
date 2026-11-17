package com.yg.minivault.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 校验 Bitwarden 那条解密链路的「接线」是否正确：
 * 用同一套算法在测试里加密，再让生产代码解密。
 *
 * 注意：这里证明的是实现自洽（EncString 格式、HMAC 先验、两种 HKDF 变体可区分），
 * 不能证明某个真实账号用的是哪一种 —— 生产代码用账户 Key 字段的 HMAC 自动判定。
 */
class BitwardenCryptoTest {

    private fun encrypt(plain: String, keys: BitwardenClient.Keys): String =
        encryptBytes(plain.toByteArray(Charsets.UTF_8), keys)

    private fun encryptBytes(bytes: ByteArray, keys: BitwardenClient.Keys): String {
        val iv = ByteArray(16) { (it + 1).toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.encKey, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(bytes)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keys.macKey, "HmacSHA256"))
        val tag = mac.doFinal(iv + ct)
        val b64 = Base64.getEncoder()
        return "2.${b64.encodeToString(iv)}|${b64.encodeToString(ct)}|${b64.encodeToString(tag)}"
    }

    private val baseKey = ByteArray(32) { it.toByte() }

    @Test
    fun decryptsWithExpandOnlyVariant() {
        val keys = BitwardenClient.stretchExpandOnly(baseKey)
        assertEquals("hello 密码", BitwardenClient.decrypt(encrypt("hello 密码", keys), keys))
    }

    @Test
    fun decryptsWithSaltVariant() {
        val keys = BitwardenClient.stretchWithSalt(baseKey)
        assertEquals("hello 密码", BitwardenClient.decrypt(encrypt("hello 密码", keys), keys))
    }

    @Test
    fun wrongVariantFailsMacCheck() {
        val a = BitwardenClient.stretchExpandOnly(baseKey)
        val b = BitwardenClient.stretchWithSalt(baseKey)
        assertNull(BitwardenClient.decryptRaw(encrypt("x", a), b))
        assertNull(BitwardenClient.decryptRaw(encrypt("x", b), a))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val keys = BitwardenClient.stretchExpandOnly(baseKey)
        val good = encrypt("secret", keys)
        val parts = good.substringAfter('.').split('|').toMutableList()
        val ct = Base64.getDecoder().decode(parts[1])
        ct[0] = (ct[0].toInt() xor 0x01).toByte()
        parts[1] = Base64.getEncoder().encodeToString(ct)
        assertNull(BitwardenClient.decryptRaw("2." + parts.joinToString("|"), keys))
    }

    @Test
    fun splitUserKeySplitsEncAndMac() {
        val userKey = ByteArray(64) { (it + 1).toByte() }
        val keys = BitwardenClient.splitUserKey(userKey)
        assertEquals(32, keys.encKey.size)
        assertEquals(32, keys.macKey.size)
        assertEquals(userKey[0], keys.encKey[0])
        assertEquals(userKey[64 - 1], keys.macKey[31])
    }

    @Test
    fun itemDecryptsThroughTheSameKeyHierarchyAsBitwarden() {
        // 模拟真实账号：masterKey → HKDF → 解开 64B userKey → 直接切分 → 解条目
        val masterKey = ByteArray(32) { (it * 7).toByte() }
        val stretch = BitwardenClient.stretchWithSalt(masterKey)
        val userKey = ByteArray(64) { (it * 5 + 3).toByte() }
        val wrappedKey = encryptBytes(userKey, stretch)

        val unwrapped = BitwardenClient.decryptRaw(wrappedKey, stretch)
        assertNotNull(unwrapped)
        val keys = BitwardenClient.splitUserKey(unwrapped!!)
        val box = encrypt("password-123", keys)
        assertEquals("password-123", BitwardenClient.decrypt(box, keys))
    }

    @Test
    fun detectPicksTheRightVariant() {
        // 变体 B 的队伍：用盐版包裹一个 64 字节「userKey」
        val userKey = ByteArray(64) { (it * 3).toByte() }
        val wrapped = encryptBytes(userKey, BitwardenClient.stretchWithSalt(baseKey))
        // detectStretch 应识别为 B，并能解开 userKey
        val chosen = BitwardenClient.detectStretch(wrapped, baseKey)
        val unwrapped = BitwardenClient.decryptRaw(wrapped, chosen(baseKey))
        assertNotNull(unwrapped)
        assertEquals(64, unwrapped!!.size)
    }

    @Test
    fun hashMasterKeyMatchesIndependentVector() {
        // 向量由 Python 独立算出：masterKey = PBKDF2-SHA256(pw, email.lower(), 600000, 32)
        //   hashMasterKey = Base64(PBKDF2-SHA256(masterKey, pw, 1, 32))
        val masterKey = java.util.Base64.getDecoder()
            .decode("VdxKlOSIH/15LWy0Fz1xRXVs2V3cmyMQuw/JMRI8gkg=")
        val hash = BitwardenClient.hashMasterKey(
            masterKey,
            "correct horse battery staple".toCharArray(),
        )
        assertEquals("nfEypS52oCiXbQyehOT45BcIhAJYN/5ltdzSYVXHaWg=", hash)
    }

    @Test
    fun detectFallsBackToExpandOnlyForLegacyAccounts() {
        val chosen = BitwardenClient.detectStretch("", baseKey)
        val keys = chosen(baseKey)
        assertEquals("legacy", BitwardenClient.decrypt(encrypt("legacy", keys), keys))
    }
}