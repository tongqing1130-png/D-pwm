package com.yg.minivault.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.yg.minivault.ui.theme.MiniVaultTheme

class MainActivity : ComponentActivity() {

    private val vm: VaultViewModel by viewModels { VaultViewModel.Factory }

    /** 拉起系统文件选择器时不要立即锁定，否则回不到当前界面。 */
    private var suppressLock = false

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            suppressLock = false
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(vm.exportText().toByteArray(Charsets.UTF_8))
                } ?: error("无法写入所选文件")
            }.onSuccess {
                vm.message = "备份已导出，请妥善保管"
            }.onFailure {
                vm.message = "导出失败：${it.message}"
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            suppressLock = false
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader -> reader.readText() }
                    ?: error("无法读取所选文件")
            }.onSuccess { text ->
                vm.loadBackupText(text)
            }.onFailure {
                vm.message = "读取备份失败：${it.message}"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 禁止截屏，同时让「最近任务」里的缩略图变成空白（否则后台卡片能直接看到账号密码）
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContent {
            MiniVaultTheme(mode = vm.themeMode, fontScale = vm.fontScale) {
                AppRoot(
                    vm = vm,
                    onExport = {
                        suppressLock = true
                        exportLauncher.launch("minivault-backup-${System.currentTimeMillis()}.json")
                    },
                    onImportPick = {
                        suppressLock = true
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                    onCopy = ::copyWithAutoClear,
                )
            }
        }
    }

    /** 离开前台的时刻；回来时超过「会话超时」才锁。 */
    private var backgroundedAt = 0L

    override fun onPause() {
        super.onPause()
        // 记下离开前台的时刻：自动填充据此判断是否已经超过会话超时
        getSharedPreferences("minivault", MODE_PRIVATE)
            .edit()
            .putLong("last_foreground_at", System.currentTimeMillis())
            .apply()
        if (suppressLock) return
        // 0 分钟 = 立即：一离开前台就锁（最近任务里不会留下可读的列表）
        if (vm.sessionTimeoutMinutes <= 0) {
            vm.lock()
        } else {
            backgroundedAt = System.currentTimeMillis()
        }
    }

    override fun onResume() {
        super.onResume()
        val timeout = vm.sessionTimeoutMinutes
        if (backgroundedAt > 0L && timeout > 0 &&
            System.currentTimeMillis() - backgroundedAt >= timeout * 60_000L
        ) {
            vm.lock()
        }
        backgroundedAt = 0L
    }

    /**
     * 复制并在 30 秒后清空剪贴板。
     * 只有当剪贴板内容仍然是这次复制的那串字符时才清，避免误删用户后来复制的内容。
     */
    private fun copyWithAutoClear(label: String, value: String) {
        if (value.isEmpty()) return
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        vm.message = "已复制，30 秒后自动清空剪贴板"

        vm.scheduleClipboardClear(value) {
            runOnUiThread {
                val current = cm.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(this)?.toString()
                if (current == value) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        cm.clearPrimaryClip()
                    } else {
                        cm.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                }
            }
        }
    }
}