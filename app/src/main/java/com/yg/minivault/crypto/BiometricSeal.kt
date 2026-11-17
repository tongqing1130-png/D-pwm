package com.yg.minivault.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 AndroidKeyStore 里的一把「需要生物识别才能用」的 AES 密钥再包一层 DEK。
 *
 * 特性：密钥不可导出、只在 TEE/StrongBox 内做运算、指纹新录入会自动失效
 * （[KeyGenParameterSpec.Builder.setInvalidatedByBiometricEnrollment]）。
 * 因此换机 / 新增指纹后这一层会失效，主密码永远是兜底路径。
 */
object BiometricSeal {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "minivault.bio.v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun hasKey(): Boolean = runCatching { keyStore().containsAlias(ALIAS) }.getOrDefault(false)

    /** 确认密钥存在（不存在则创建）。创建后第一次使用需要用户认证。 */
    fun ensureKey() {
        if (hasKey()) return
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        generator.generateKey()
    }

    /** 返回一个已初始化为「加密」的 Cipher（必须交给 BiometricPrompt 的 CryptoObject 使用）。 */
    fun encryptCipher(): Cipher {
        ensureKey()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, loadKey())
        return cipher
    }

    /** 返回一个已用存下来的 IV 初始化好的「解密」Cipher。 */
    fun decryptCipher(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, loadKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher
    }

    fun deleteKey() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun loadKey(): SecretKey = keyStore().getKey(ALIAS, null) as SecretKey

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
}