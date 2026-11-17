package com.yg.minivault.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yg.minivault.crypto.BiometricSeal
import com.yg.minivault.data.VaultItem
import com.yg.minivault.ui.BiometricPromptHelper
import com.yg.minivault.ui.Screen
import com.yg.minivault.ui.VaultViewModel
import com.yg.minivault.ui.screens.UnlockScreen
import com.yg.minivault.ui.theme.MiniVaultTheme
import com.yg.minivault.util.CrashLog

/**
 * 自动填充的「认证后填充」入口。
 *
 * 两条路径：
 *  - 保险库锁定 → 直接复用解锁页（指纹自动拉起 / 主密码），解锁即视为已验证
 *  - 保险库已解锁但需要二次验证（每次填充都验证 / 会话超时）→ 指纹或主密码确认
 *
 * 验证通过后把 **FillResponse** 通过 EXTRA_AUTHENTICATION_RESULT 交回系统。
 */
class AutofillAuthActivity : ComponentActivity() {

    private val vm: VaultViewModel by viewModels { VaultViewModel.Factory }

    private var usernameId: AutofillId? = null
    private var passwordId: AutofillId? = null
    private var domain: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usernameId = intent.autofillIdExtra(FillSupport.EXTRA_USERNAME_ID)
        passwordId = intent.autofillIdExtra(FillSupport.EXTRA_PASSWORD_ID)
        domain = intent.getStringExtra(FillSupport.EXTRA_DOMAIN)
        // 进程内仓库才是真相：本 Activity 的 ViewModel 是新实例，screen 初值不可能是 List
        val alreadyUnlocked = FillSupport.repository(this).isUnlocked()
        CrashLog.note(
            this,
            "autofill-auth",
            "认证页启动 已解锁=$alreadyUnlocked 域名=$domain 有用户名框=${usernameId != null} 有密码框=${passwordId != null}",
        )

        setContent {
            MiniVaultTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Content(alreadyUnlocked)
                }
            }
        }
    }

    @Composable
    private fun Content(alreadyUnlocked: Boolean) {
        var verified by remember { mutableStateOf(!alreadyUnlocked) }
        val screen = vm.screen

        LaunchedEffect(screen, alreadyUnlocked) {
            if (!alreadyUnlocked && screen is Screen.List) verified = true
        }

        LaunchedEffect(Unit) {
            if (vm.items.isEmpty()) vm.refresh()
        }

        when {
            !alreadyUnlocked && screen !is Screen.List -> UnlockScreen(vm)
            !verified -> ConfirmStep(onVerified = { verified = true }, onCancel = { finishCanceled() })
            else -> FillStep()
        }
    }

    @Composable
    private fun ConfirmStep(onVerified: () -> Unit, onCancel: () -> Unit) {
        val context = LocalContext.current
        val activity = context as? Activity
        var password by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val canBiometric = remember {
            activity != null && vm.hasBiometricBinding() && BiometricPromptHelper.canUse(activity)
        }

        fun verifyWithPassword() {
            if (password.isEmpty()) return
            busy = true
            val ok = vm.verifyPassword(password)
            busy = false
            if (ok) onVerified() else error = "主密码不正确"
        }

        fun verifyWithBiometric() {
            val act = activity ?: return
            val iv = vm.biometricIv()
            val ct = vm.biometricCt()
            if (iv == null || ct == null) {
                error = "指纹数据缺失，请用主密码验证"
                return
            }
            runCatching {
                val cipher = BiometricSeal.decryptCipher(iv)
                BiometricPromptHelper.authenticate(
                    activity = act,
                    cipher = cipher,
                    title = "验证后填充",
                    subtitle = "确认是你在使用 D-pwm",
                    onSuccess = {
                        busy = false
                        onVerified()
                    },
                    onError = { error = it },
                    onCancel = { busy = false },
                )
                busy = true
            }.onFailure {
                busy = false
                error = "指纹不可用：${it.message}"
            }
        }

        LaunchedEffect(Unit) { if (canBiometric) verifyWithBiometric() }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("验证后填充", style = MaterialTheme.typography.titleLarge)
            Text(
                if (canBiometric) "用指纹确认，或输入主密码" else "请输入主密码确认",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text("主密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { verifyWithPassword() }, enabled = password.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) {
                Text("验证并填充")
            }
            if (canBiometric) {
                OutlinedButton(onClick = { verifyWithBiometric() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("用指纹验证")
                }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.fillMaxWidth(0.15f), strokeWidth = 2.dp)
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }

    @Composable
    private fun FillStep() {
        // 不能 remember：解锁/导入后 vm.items 才填充，无 key 的 remember 会永久缓存空列表
        val matched = FillSupport.candidates(vm.items, domain)
        // 域名对不上（或拿不到 webDomain）时列出全部登录条目，让用户自己挑
        val list = matched.ifEmpty { FillSupport.logins(vm.items, limit = 20) }

        if (list.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("保险库里还没有可填充的登录条目")
                Text(
                    "先在 D-pwm 里添加一条登录（标题 / 网址 / 账号 / 密码），或者从 Bitwarden 导入后再回来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { finishCanceled() }) { Text("关闭") }
            }
            return
        }
        CandidatePicker(list, unmatched = matched.isEmpty()) { chosen -> finishWithFill(chosen) }
    }

    @Composable
    private fun CandidatePicker(list: List<VaultItem>, unmatched: Boolean, onPick: (VaultItem) -> Unit) {
        Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp)) {
            Text(
                text = "选择要填充的账号",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Text(
                text = if (unmatched) {
                    "当前页面没匹配到网址，下面列出全部登录条目"
                } else {
                    "匹配到 ${list.size} 个账号，点一个即可填回网页"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(list) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(item) }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    ) {
                        Text(item.title.ifBlank { "未命名" }, fontWeight = FontWeight.SemiBold)
                        Text(
                            item.username.ifBlank { item.host() },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Button(
                onClick = { finishCanceled() },
                modifier = Modifier.padding(24.dp),
            ) { Text("取消") }
        }
    }

    private fun Intent.autofillIdExtra(key: String): AutofillId? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, AutofillId::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }

    private fun finishWithFill(item: VaultItem) {
        val response = FillSupport.dataResponse(this, usernameId, passwordId, listOf(item))
        if (response == null) {
            CrashLog.append(
                this,
                "autofill-fill",
                IllegalStateException(
                    "填充响应为空 usernameId=$usernameId passwordId=$passwordId domain=$domain item=${item.title}"
                ),
            )
            finishCanceled()
            return
        }
        val data = Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response)
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun finishCanceled() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }
}
