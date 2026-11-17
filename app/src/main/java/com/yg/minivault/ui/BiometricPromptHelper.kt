package com.yg.minivault.ui

import android.app.Activity
import android.os.CancellationSignal
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import javax.crypto.Cipher

/**
 * 直接使用平台的 BiometricPrompt（minSdk 29 足够），不额外引入 androidx.biometric，
 * 避免再踩一次依赖版本要求 compileSdk 37 的坑。
 */
object BiometricPromptHelper {

    /** 设备是否有可用的强生物识别（已录入指纹/人脸）。 */
    fun canUse(activity: Activity): Boolean = runCatching {
        val manager = activity.getSystemService(BiometricManager::class.java) ?: return false
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate()
        }
        result == BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    fun authenticate(
        activity: Activity,
        cipher: Cipher,
        title: String,
        subtitle: String,
        negativeLabel: String = "改用主密码",
        onSuccess: (Cipher) -> Unit,
        onError: (String) -> Unit,
        onCancel: () -> Unit,
    ) {
        val executor = activity.mainExecutor
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButton(negativeLabel, executor) { _, _ -> onCancel() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        }

        val prompt = builder.build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            CancellationSignal(),
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val unlocked = result.cryptoObject?.cipher
                    if (unlocked == null) {
                        onError("生物识别没有返回可用密钥")
                    } else {
                        onSuccess(unlocked)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // 10 = 用户取消, 13 = 点了「改用主密码」, 5 = 系统取消
                    if (errorCode == 10 || errorCode == 13 || errorCode == 5) {
                        onCancel()
                    } else {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    // 指纹不匹配：系统会自己提示重试，这里不用打断
                }
            },
        )
    }
}