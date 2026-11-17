package com.yg.minivault.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yg.minivault.crypto.BiometricSeal
import com.yg.minivault.util.CrashLog
import com.yg.minivault.ui.BiometricPromptHelper
import com.yg.minivault.ui.VaultViewModel
import com.yg.minivault.ui.theme.FontScaleOptions
import com.yg.minivault.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: VaultViewModel,
    onExport: () -> Unit,
    onImportPick: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val canUseBiometric = remember(activity) { activity != null && BiometricPromptHelper.canUse(activity) }
    var bioOn by remember { mutableStateOf(vm.hasBiometricBinding()) }
    var bioError by remember { mutableStateOf<String?>(null) }

    var baseUrl by remember { mutableStateOf("https://vault.bitwarden.com") }
    var email by remember { mutableStateOf("") }
    var bwPassword by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var twoFactor by remember { mutableStateOf("") }
    var newMaster by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    var showTimeout by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showFont by remember { mutableStateOf(false) }

    fun toggleBiometric(want: Boolean) {
        if (!want) {
            vm.disableBiometric()
            bioOn = false
            return
        }
        val act = activity ?: return
        val dek = vm.dekBytesForBiometric()
        if (dek == null) {
            bioError = "保险库未解锁，无法绑定指纹"
            return
        }
        try {
            val cipher = BiometricSeal.encryptCipher()
            BiometricPromptHelper.authenticate(
                activity = act,
                cipher = cipher,
                title = "启用指纹解锁",
                subtitle = "验证一次指纹，用来包裹本地密钥",
                negativeLabel = "取消",
                onSuccess = { unlocked ->
                    runCatching { unlocked.doFinal(dek) }
                        .onSuccess { ct ->
                            vm.saveBiometricBinding(unlocked.iv, ct)
                            bioOn = true
                            bioError = null
                        }
                        .onFailure { bioError = "启用失败：${it.message}" }
                },
                onError = { bioError = it },
                onCancel = {},
            )
        } catch (e: Exception) {
            bioError = "无法使用生物识别：${e.message}"
        }
    }

    fun openAutofillSettings() {
        val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    if (showLog) {
        val log = remember { CrashLog.read(context) }
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("错误日志") },
            text = {
                SelectionContainer {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (log.isBlank()) "（没有记录）" else log.takeLast(4000),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(log))
                    vm.message = "日志已复制"
                    showLog = false
                }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { showLog = false }) { Text("关闭") } },
        )
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { TextButton(onClick = { vm.backToList() }) { Text("返回") } },
                actions = { TextButton(onClick = { vm.lock() }) { Text("锁定") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (vm.busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp).fillMaxWidth(0.06f), strokeWidth = 2.dp)
                    Text(vm.busyText ?: "处理中…", style = MaterialTheme.typography.bodySmall)
                }
            }
            vm.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // ------------------------------------------------ 外观
            Text("外观", style = MaterialTheme.typography.titleMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { showTheme = true },
            ) {
                Column(Modifier.weight(1f)) {
                    Text("主题", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        themeLabel(vm.themeMode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showTheme = true }) { Text("更改") }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { showFont = true },
            ) {
                Column(Modifier.weight(1f)) {
                    Text("文字大小", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        fontLabel(vm.fontScale),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showFont = true }) { Text("更改") }
            }
            if (showTheme) {
                AlertDialog(
                    onDismissRequest = { showTheme = false },
                    title = { Text("主题") },
                    text = {
                        Column {
                            listOf(
                                ThemeMode.SYSTEM to "跟随系统",
                                ThemeMode.LIGHT to "明亮",
                                ThemeMode.DARK to "黑色",
                            ).forEach { (mode, title) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { vm.applyThemeMode(mode); showTheme = false }
                                        .padding(vertical = 10.dp),
                                ) {
                                    RadioButton(
                                        selected = vm.themeMode == mode,
                                        onClick = { vm.applyThemeMode(mode); showTheme = false },
                                    )
                                    Text(title)
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showTheme = false }) { Text("取消") } },
                )
            }
            if (showFont) {
                AlertDialog(
                    onDismissRequest = { showFont = false },
                    title = { Text("文字大小") },
                    text = {
                        Column {
                            FontScaleOptions.forEach { (scale, title) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { vm.applyFontScale(scale); showFont = false }
                                        .padding(vertical = 10.dp),
                                ) {
                                    RadioButton(
                                        selected = kotlin.math.abs(vm.fontScale - scale) < 0.01f,
                                        onClick = { vm.applyFontScale(scale); showFont = false },
                                    )
                                    Text(title)
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showFont = false }) { Text("取消") } },
                )
            }

            HorizontalDivider()

            // ------------------------------------------------ 生物识别
            Text("生物识别解锁", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (canUseBiometric) "用指纹/人脸解锁，主密码始终可用" else "此设备没有可用的强生物识别",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = bioOn, enabled = canUseBiometric && !vm.busy, onCheckedChange = { toggleBiometric(it) })
            }
            bioError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            // ------------------------------------------------ 自动填充
            Text("自动填充", style = MaterialTheme.typography.titleMedium)
            Text(
                "在系统「密码自动填充与通行密钥服务」里选中 D-pwm 之后，登录页面就能直接填充账号密码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("每次填充都要求验证", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "关闭后，保险库已解锁时会直接填充（Bitwarden 的默认行为）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = vm.fillRequiresAuth,
                    onCheckedChange = { vm.applyFillRequiresAuth(it) },
                )
            }
            Button(onClick = { openAutofillSettings() }, modifier = Modifier.fillMaxWidth()) {
                Text("打开系统自动填充设置")
            }

            HorizontalDivider()

            // ------------------------------------------------ 会话超时
            Text("会话超时", style = MaterialTheme.typography.titleMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { showTimeout = true },
            ) {
                Column(Modifier.weight(1f)) {
                    Text("退出后台后多久自动锁定", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        timeoutLabel(vm.sessionTimeoutMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showTimeout = true }) { Text("更改") }
            }
            if (showTimeout) {
                AlertDialog(
                    onDismissRequest = { showTimeout = false },
                    title = { Text("会话超时") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            TIMEOUT_OPTIONS.forEach { (minutes, label) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            vm.setSessionTimeout(minutes)
                                            showTimeout = false
                                        }
                                        .padding(vertical = 10.dp),
                                ) {
                                    RadioButton(
                                        selected = vm.sessionTimeoutMinutes == minutes,
                                        onClick = {
                                            vm.setSessionTimeout(minutes)
                                            showTimeout = false
                                        },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(label)
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showTimeout = false }) { Text("取消") }
                    },
                )
            }

            HorizontalDivider()

            // ------------------------------------------------ Bitwarden 导入（可折叠）
            var showBw by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showBw = !showBw }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("从 Bitwarden 导入", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (showBw) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (showBw) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showBw) {
            Text(
                "直接在这里登录你自己的 Bitwarden 账户（自建服务器也可以），解密后的条目会写进本地保险库。主密码只用于这一次解密，不会被保存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("服务器地址") },
                singleLine = true,
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Bitwarden 邮箱") },
                singleLine = true,
                enabled = !vm.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = bwPassword,
                onValueChange = { bwPassword = it },
                label = { Text("Bitwarden 主密码") },
                singleLine = true,
                enabled = !vm.busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = twoFactor,
                onValueChange = { twoFactor = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("两步验证 / 新设备验证码（可选，6 位）") },
                singleLine = true,
                enabled = !vm.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    vm.importFromBitwarden(baseUrl, email, bwPassword, twoFactor)
                    bwPassword = ""
                    twoFactor = ""
                },
                enabled = !vm.busy && email.isNotBlank() && bwPassword.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (vm.busy) "导入中…" else "登录并导入")
            }

            }
            HorizontalDivider()

            // ------------------------------------------------ 备份
            Text("备份", style = MaterialTheme.typography.titleMedium)
            Text(
                "备份文件仍然是密文，恢复时需要它的主密码。换手机、清数据前一定先导出一份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onExport, enabled = !vm.busy, modifier = Modifier.weight(1f)) {
                    Text("导出备份")
                }
                OutlinedButton(
                    onClick = {
                        importPassword = ""
                        onImportPick()
                    },
                    enabled = !vm.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("导入备份")
                }
            }
            if (vm.pendingImportText != null) {
                Text(
                    "已读取备份文件，请输入这份备份的主密码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedTextField(
                    value = importPassword,
                    onValueChange = { importPassword = it },
                    label = { Text("备份主密码") },
                    singleLine = true,
                    enabled = !vm.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        vm.importPendingBackup(importPassword)
                        importPassword = ""
                    },
                    enabled = importPassword.isNotEmpty() && !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("确认导入（覆盖当前库）")
                }
            }

            HorizontalDivider()

            // ------------------------------------------------ 多设备同步
            Text("多设备同步", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "两台手机都装 D-pwm 时的用法：先在 A 台建库并「上传到云端」，再到 B 台用「从云端恢复」拿到同一份保险库（B 台用 A 台的主密码解锁）。之后任意一台点「立即同步」，就会把两边的增删改合并后写回云端。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (vm.lastSyncAt > 0L) "上次同步：${syncTimeText(vm.lastSyncAt)}" else "上次同步：从未",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { vm.syncNow() }, enabled = !vm.busy) {
                Text(if (vm.busy) "同步中…" else "立即同步（合并）")
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()

            // ------------------------------------------------ WebDAV 云同步
            var showDav by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDav = !showDav }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("WebDAV / 自建云同步", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (showDav) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (showDav) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (vm.webdavUrl.isBlank()) "未配置：点标题展开，填你自己的网盘 / NAS 地址"
                else "已配置：${vm.webdavUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showDav) {
                Text(
                    "把保险库文件（已经是密文）自动同步到你自己的网盘或 NAS。支持 Nextcloud、坚果云、群晖、Alist 等任何 WebDAV 服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var wdUrl by remember { mutableStateOf(vm.webdavUrl) }
                var wdUser by remember { mutableStateOf(vm.webdavUser) }
                var wdPass by remember { mutableStateOf(vm.webdavPass) }
                var wdPath by remember { mutableStateOf(vm.webdavPath) }
                OutlinedTextField(
                    value = wdUrl,
                    onValueChange = { wdUrl = it },
                    label = { Text("服务器地址（如 https://dav.jianguoyun.com/dav）") },
                    singleLine = true,
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = wdUser,
                        onValueChange = { wdUser = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        enabled = !vm.busy,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = wdPass,
                        onValueChange = { wdPass = it },
                        label = { Text("密码 / 应用密码") },
                        singleLine = true,
                        enabled = !vm.busy,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = wdPath,
                    onValueChange = { wdPath = it },
                    label = { Text("文件路径（如 d-pwn/vault.json）") },
                    singleLine = true,
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { vm.saveWebDav(wdUrl, wdUser, wdPass, wdPath) },
                        enabled = !vm.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("保存配置") }
                    OutlinedButton(
                        onClick = {
                            vm.saveWebDav(wdUrl, wdUser, wdPass, wdPath)
                            vm.webDavTest()
                        },
                        enabled = !vm.busy && wdUrl.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("测试连接") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            vm.saveWebDav(wdUrl, wdUser, wdPass, wdPath)
                            vm.webDavUpload()
                        },
                        enabled = !vm.busy && wdUrl.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("上传到云端") }
                    OutlinedButton(
                        onClick = {
                            vm.saveWebDav(wdUrl, wdUser, wdPass, wdPath)
                            vm.webDavRestore()
                        },
                        enabled = !vm.busy && wdUrl.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("从云端恢复") }
                }
                Text(
                    "恢复会覆盖本地保险库，之后需要输入那份备份的主密码。第一次用建议先「上传」验证一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()

            // ------------------------------------------------ 修改主密码
            Text("更换主密码", style = MaterialTheme.typography.titleMedium)
            Text(
                "只重新包裹数据密钥，不会重写所有条目，因此很快；旧密码立即失效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = newMaster,
                onValueChange = { newMaster = it },
                label = { Text("新主密码（至少 8 位）") },
                singleLine = true,
                enabled = !vm.busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    vm.changeMasterPassword(newMaster)
                    newMaster = ""
                },
                enabled = newMaster.length >= 8 && !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("更换主密码")
            }

            HorizontalDivider()

            Text("诊断", style = MaterialTheme.typography.titleMedium)
            Text(
                "自动填充或导入出问题时，把这里的日志发给我就能定位。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { showLog = true }, modifier = Modifier.weight(1f)) {
                    Text("查看错误日志")
                }
                OutlinedButton(
                    onClick = {
                        CrashLog.clear(context)
                        vm.message = "日志已清空"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("清空") }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "D-pwm 0.10.1 · 本地单机 · 除 Bitwarden 导入外不联网",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 会话超时选项：0 = 立即。 */
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "明亮"
    ThemeMode.DARK -> "黑色"
}

private fun fontLabel(scale: Float): String =
    FontScaleOptions.firstOrNull { kotlin.math.abs(it.first - scale) < 0.01f }?.second ?: "标准"

private val TIMEOUT_OPTIONS = listOf(
    0 to "立即",
    1 to "1 分钟",
    5 to "5 分钟",
    15 to "15 分钟",
    30 to "30 分钟",
    60 to "1 小时",
    240 to "4 小时",
)

private fun timeoutLabel(minutes: Int): String =
    TIMEOUT_OPTIONS.firstOrNull { it.first == minutes }?.second ?: "$minutes 分钟"

private fun syncTimeText(ts: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
