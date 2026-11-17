package com.yg.minivault.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yg.minivault.crypto.BiometricSeal
import com.yg.minivault.ui.BiometricPromptHelper
import com.yg.minivault.ui.VaultViewModel
import com.yg.minivault.ui.theme.glassSurface

@Composable
fun UnlockScreen(vm: VaultViewModel) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var bioError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val activity = context as? Activity
    // 只读一次文件头：锁定态下 header 不在内存里，不能每次重组都去读盘
    val hasBinding = remember { vm.hasBiometricBinding() }
    val biometricReady = activity != null && hasBinding &&
        remember(activity) { BiometricPromptHelper.canUse(activity) }

    fun unlockWithBiometrics() {
        val act = activity ?: return
        val iv = vm.biometricIv()
        val ct = vm.biometricCt()
        if (iv == null || ct == null) {
            bioError = "指纹数据缺失，请用主密码解锁后再重新开启"
            return
        }
        try {
            val cipher = BiometricSeal.decryptCipher(iv)
            BiometricPromptHelper.authenticate(
                activity = act,
                cipher = cipher,
                title = "解锁 D-pwm",
                subtitle = "验证指纹",
                onSuccess = { unlocked ->
                    runCatching { unlocked.doFinal(ct) }
                        .onSuccess { vm.unlockWithDek(it) }
                        .onFailure { bioError = "指纹解锁失败：${it.message}" }
                },
                onError = { bioError = it },
                onCancel = {},
            )
        } catch (e: Exception) {
            // 新增指纹或恢复出厂后 Keystore 会作废这把密钥
            vm.disableBiometric()
            bioError = "指纹密钥已失效，请用主密码解锁后重新开启"
        }
    }

    // 进入解锁页就直接拉起指纹；用户取消或失败则落回主密码
    var autoPrompted by remember { mutableStateOf(false) }
    LaunchedEffect(biometricReady) {
        if (biometricReady && !autoPrompted && !vm.busy) {
            autoPrompted = true
            unlockWithBiometrics()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(strong = true)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Text("D-pwm", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "输入主密码解锁",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("主密码") },
            singleLine = true,
            enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty() && !vm.busy) vm.unlock(password) }),
            trailingIcon = {
                TextButton(onClick = { visible = !visible }, enabled = !vm.busy) {
                    Text(if (visible) "隐藏" else "显示")
                }
            },
        )

        val error = vm.error ?: bioError
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (vm.busy) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    vm.busyText ?: "正在解锁…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { vm.unlock(password) },
            enabled = password.isNotEmpty() && !vm.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (vm.busy) "解锁中…" else "解锁")
        }

        if (biometricReady) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { unlockWithBiometrics() },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("用指纹解锁")
            }
        }
    }
    }
}
