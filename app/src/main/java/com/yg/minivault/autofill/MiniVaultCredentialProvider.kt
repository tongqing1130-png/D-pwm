package com.yg.minivault.autofill

import android.credentials.ClearCredentialStateException
import android.credentials.CreateCredentialException
import android.credentials.GetCredentialException
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.service.credentials.BeginCreateCredentialRequest
import android.service.credentials.BeginCreateCredentialResponse
import android.service.credentials.BeginGetCredentialRequest
import android.service.credentials.BeginGetCredentialResponse
import android.service.credentials.ClearCredentialStateRequest
import android.service.credentials.CredentialProviderService
import androidx.annotation.RequiresApi
import com.yg.minivault.util.CrashLog

/**
 * Android 14+ 的「凭据提供者」注册。
 *
 * 目前只做注册 + 空响应：这样系统设置里的
 * 「密码自动填充与通行密钥服务」就能列出 MiniVault。
 * 实际的密码建议走 [VaultAutofillService]；通行密钥（Passkey）暂不提供，
 * 因此创建/查询都返回空结果，而不是报错（报错会让系统反复提示「服务不可用」）。
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class MiniVaultCredentialProvider : CredentialProviderService() {

    override fun onBeginCreateCredential(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        // 系统会在选中服务后立刻探活；任何异常都必须自己吞掉，否则进程会被判死、服务被系统弃用
        val response = runCatching { BeginCreateCredentialResponse.Builder().build() }.getOrElse {
            CrashLog.append(this, "onBeginCreateCredential", it)
            BeginCreateCredentialResponse.Builder().build()
        }
        runCatching { callback.onResult(response) }
    }

    override fun onBeginGetCredential(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        val response = runCatching { BeginGetCredentialResponse.Builder().build() }.getOrElse {
            CrashLog.append(this, "onBeginGetCredential", it)
            BeginGetCredentialResponse.Builder().build()
        }
        runCatching { callback.onResult(response) }
    }

    override fun onClearCredentialState(
        request: ClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void, ClearCredentialStateException>,
    ) {
        runCatching { callback.onResult(null) }
    }
}