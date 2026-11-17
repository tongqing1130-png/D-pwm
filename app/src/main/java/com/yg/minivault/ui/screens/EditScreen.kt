package com.yg.minivault.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yg.minivault.crypto.PasswordGenerator
import com.yg.minivault.data.ItemType
import com.yg.minivault.data.VaultItem
import com.yg.minivault.ui.VaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    vm: VaultViewModel,
    original: VaultItem,
    isNew: Boolean,
    onCopy: (label: String, value: String) -> Unit,
) {
    var title by remember(original.id) { mutableStateOf(original.title) }
    var username by remember(original.id) { mutableStateOf(original.username) }
    var password by remember(original.id) { mutableStateOf(original.password) }
    var url by remember(original.id) { mutableStateOf(original.url) }
    var notes by remember(original.id) { mutableStateOf(original.notes) }
    var totp by remember(original.id) { mutableStateOf(original.totp) }
    var type by remember(original.id) { mutableStateOf(original.type) }
    var visible by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var passwordLength by remember { mutableStateOf(20f) }

    fun build() = original.copy(
        type = type,
        title = title.trim(),
        username = username,
        password = password,
        url = url.trim(),
        notes = notes,
        totp = totp.trim(),
    )

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新建条目" else "编辑条目") },
                navigationIcon = {
                    TextButton(onClick = { vm.backToList() }) { Text("返回") }
                },
                actions = {
                    TextButton(onClick = { vm.save(build()) }) { Text("保存") }
                    if (!isNew) {
                        TextButton(onClick = { showDelete = true }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ItemType.entries.forEach { t ->
                    FilterChip(
                        selected = type == t,
                        onClick = { type = t },
                        label = { Text(t.label) },
                    )
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题（例如 淘宝 / 邮箱）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名 / 邮箱") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                trailingIcon = {
                    TextButton(onClick = { visible = !visible }) {
                        Text(if (visible) "隐藏" else "显示")
                    }
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        password = PasswordGenerator.generate(passwordLength.toInt())
                        visible = true
                    },
                ) { Text("生成 ${passwordLength.toInt()} 位") }
                TextButton(onClick = { onCopy("密码", password) }) { Text("复制") }
                TextButton(onClick = { passwordLength = (passwordLength + 4f).coerceAtMost(48f) }) {
                    Text("长度 +4")
                }
            }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("网址（可选，可填多个）") },
                placeholder = { Text("每行一个，例如：\na.example.com\nlogin.a.cn") },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            if (url.isNotBlank()) {
                Text(
                    "自动填充会匹配这些域名：" + com.yg.minivault.data.UrlHosts.hosts(url).joinToString("、"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = totp,
                onValueChange = { totp = it },
                label = { Text("TOTP 验证器密钥（可选）") },
                placeholder = { Text("otpauth://totp/... 或 JBSWY3DPEHPK3PXP") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (totp.isNotBlank()) {
                TotpCodeView(secret = totp)
            }
            TotpHint()

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("备注（可选）") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))
            Text(
                "字段内容整体加密后写入本机文件，数据库里没有明文。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除这条记录？") },
            text = { Text("删除后无法恢复（除非你有备份）。") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    vm.delete(original.id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            },
        )
    }
}