package com.yg.minivault.autofill

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yg.minivault.data.ItemType
import com.yg.minivault.data.VaultItem
import com.yg.minivault.ui.Screen
import com.yg.minivault.ui.VaultViewModel
import com.yg.minivault.ui.screens.UnlockScreen
import com.yg.minivault.ui.theme.MiniVaultTheme
import com.yg.minivault.ui.theme.glassSurface
import com.yg.minivault.util.CrashLog

/**
 * 网页/App 里新注册或改密码后，系统把 SaveRequest 交给我们，
 * 这里弹一个确认页问「要不要存进 D-pwm」。
 *
 * 锁定时先复用解锁页；解锁后自动进入确认步骤。
 */
class AutofillSaveActivity : ComponentActivity() {

    private val vm: VaultViewModel by viewModels { VaultViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val username = intent.getStringExtra(FillSupport.EXTRA_USERNAME_VALUE).orEmpty()
        val password = intent.getStringExtra(FillSupport.EXTRA_PASSWORD_VALUE).orEmpty()
        val domain = intent.getStringExtra(FillSupport.EXTRA_DOMAIN).orEmpty()

        CrashLog.note(
            this,
            "autofill-save",
            "保存页启动 域名=$domain 用户名长度=${username.length} 密码长度=${password.length}",
        )

        if (password.isBlank()) {
            finish()
            return
        }

        setContent {
            MiniVaultTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Content(username = username, password = password, domain = domain)
                }
            }
        }
    }

    @Composable
    private fun Content(username: String, password: String, domain: String) {
        val screen = vm.screen
        var pending by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            if (vm.items.isEmpty()) vm.refresh()
        }

        // 锁定 → 先解锁；解锁成功后 screen 变 List，自动落到确认步骤
        if (screen !is Screen.List) {
            UnlockScreen(vm)
            return
        }

        if (pending) return

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("保存这个登录？", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))

            Column(Modifier.fillMaxWidth().glassSurface(shape = RoundedCornerShape(20.dp)).padding(18.dp)) {
                Text(
                    text = domain.ifBlank { "本地" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (username.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(username, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
                Text("••••••••", style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        pending = true
                        save(username = username, password = password, domain = domain)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存到 D-pwm") }
                OutlinedButton(
                    onClick = { finish() },
                    modifier = Modifier.weight(1f),
                ) { Text("不要") }
            }
        }
    }

    private fun save(username: String, password: String, domain: String) {
        val host = domain.trim().removePrefix("www.").lowercase()
        val repo = FillSupport.repository(this)
        // 不能用 ViewModel 的协程：Activity 一 finish，viewModelScope 就被取消，
        // 落盘可能半途夭折。这里用独立线程等写完再关页面。
        Thread {
            val result = runCatching {
                val existing = repo.items.firstOrNull { item ->
                    item.type == ItemType.LOGIN &&
                        item.username.trim() == username.trim() &&
                        (host.isBlank() || item.hosts().any { it == host })
                }
                val item = existing?.copy(password = password) ?: VaultItem(
                    type = ItemType.LOGIN,
                    title = host.ifBlank { username.ifBlank { "新登录" } },
                    username = username,
                    password = password,
                    url = if (host.isBlank()) "" else "https://$host",
                )
                repo.save(item)
                existing != null
            }
            result.exceptionOrNull()?.let { CrashLog.append(this, "autofill-save-write", it) }
            runOnUiThread {
                val message = when {
                    result.isFailure -> "保存失败：" + (result.exceptionOrNull()?.message ?: "未知错误")
                    result.getOrNull() == true -> "已更新密码"
                    else -> "已保存到密码库"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
        }.start()
    }
}
