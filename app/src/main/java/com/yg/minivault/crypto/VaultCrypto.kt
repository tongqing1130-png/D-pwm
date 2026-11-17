package com.yg.minivault.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 主密码错误，或密文被篡改（GCM tag 校验失败）。 */
class WrongPasswordException(message: String = "主密码不正确") : Exception(message)

/** 保险库文件结构损坏，无法解析。 */
class VaultCorruptedException(message: String = "保险库数据已损坏") : Exception(message)

/** 一段 GCM 密文：随机 nonce + 密文（含 16 字节认证 tag），都以 Base64 存放。 */
data class SealedBox(val nonceB64: String, val ctB64: String)

/**
 * 纯 JVM 实现的加密内核（不依赖 Android 框架，可直接跑单元测试）。
 *
 * 密钥链：
 *   主密码 --PBKDF2-HMAC-SHA256--> KEK(32B)
 *   随机 DEK(32B) --AES-256-GCM(KEK)--> 落盘的包裹 DEK
 *   条目明文 --AES-256-GCM(DEK, 每条目独立 nonce, AAD)--> 落盘的条目密文
 */
object VaultCrypto {

    /** PBKDF2 迭代次数（OWASP 2023 对 SHA-256 的建议下限）。 */
    const val DEFAULT_ITERATIONS = 600_000

    private const val KDF_ALGO = "PBKDF2WithHmacSHA256"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val NONCE_LEN = 12
    private const val KEY_LEN_BYTES = 32

    private val rng = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { rng.nextBytes(it) }

    fun newSalt(): ByteArray = randomBytes(32)

    /** 从主密码派生 KEK。长度不足的密码由调用方负责校验。 */
    fun deriveKek(password: CharArray, salt: ByteArray, iterations: Int = DEFAULT_ITERATIONS): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, KEY_LEN_BYTES * 8)
        val factory = SecretKeyFactory.getInstance(KDF_ALGO)
        val raw = try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return SecretKeySpec(raw, "AES")
    }

    /** 生成一把全新的数据加密密钥。 */
    fun newDek(): SecretKey = SecretKeySpec(randomBytes(KEY_LEN_BYTES), "AES")

    fun dekBytes(dek: SecretKey): ByteArray = dek.encoded

    fun dekFromBytes(bytes: ByteArray): SecretKey = SecretKeySpec(bytes, "AES")

    fun seal(key: SecretKey, plaintext: ByteArray, aad: ByteArray? = null): SealedBox {
        val nonce = randomBytes(NONCE_LEN)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        if (aad != null) cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        return SealedBox(b64(nonce), b64(ct))
    }

    /**
     * 解密。密钥错误或密文/AAD 被改动都会抛 [WrongPasswordException]
     * （GCM 的 tag 校验让两种情形在密码学上无法区分）。
     */
    fun open(key: SecretKey, box: SealedBox, aad: ByteArray? = null): ByteArray {
        val nonce = unb64(box.nonceB64)
        val ct = unb64(box.ctB64)
        val cipher = Cipher.getInstance(TRANSFORM)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            if (aad != null) cipher.updateAAD(aad)
            cipher.doFinal(ct)
        } catch (e: Exception) {
            throw WrongPasswordException()
        }
    }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun unb64(text: String): ByteArray = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        throw VaultCorruptedException()
    }

    /** 清零密钥字节，尽量缩短明文密钥在堆上的存活时间。 */
    fun wipe(bytes: ByteArray?) {
        bytes?.fill(0)
    }
}