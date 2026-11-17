package com.yg.minivault.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yg.minivault.MiniVaultApp
import com.yg.minivault.crypto.BiometricSeal
import com.yg.minivault.crypto.WrongPasswordException
import com.yg.minivault.data.VaultItem
import com.yg.minivault.data.VaultRepository
import com.yg.minivault.net.BitwardenClient
import com.yg.minivault.net.WebDavClient
import com.yg.minivault.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Setup : Screen
    data object Unlock : Screen
    data object List : Screen
    data object Settings : Screen
    data class Edit(val item: VaultItem, val isNew: Boolean) : Screen
}

class VaultViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: VaultRepository = (app as MiniVaultApp).repository

    var screen by mutableStateOf<Screen>(currentScreen())
        private set

    var items by mutableStateOf<List<VaultItem>>(emptyList())
        private set

    var query by mutableStateOf("")
    /** 主界面分类筛选：null = 全部。 */
    var typeFilter by mutableStateOf<com.yg.minivault.data.ItemType?>(null)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var corruptedCount by mutableStateOf(0)
        private set

    /** 长任务（KDF / 网络）进行中：界面据此禁用按钮、显示进度。 */
    var busy by mutableStateOf(false)
        private set

    var busyText by mutableStateOf<String?>(null)
        private set

    private var clipboardClearJob: Job? = null

    private fun currentScreen(): Screen = when {
        !repo.isInitialized() -> Screen.Setup
        repo.isUnlocked() -> Screen.List
        else -> Screen.Unlock
    }

    val filteredItems: List<VaultItem>
        get() = items.filter { item ->
            (typeFilter == null || item.type == typeFilter) && item.matches(query)
        }

    fun countOf(type: com.yg.minivault.data.ItemType?): Int =
        if (type == null) items.size else items.count { it.type == type }

    fun selectType(type: com.yg.minivault.data.ItemType?) {
        typeFilter = type
    }

    fun refresh() {
        items = repo.items
        corruptedCount = repo.corruptedCount
        if (screen !is Screen.Edit) screen = currentScreen()
    }

    /**
     * 所有耗时动作的唯一入口：同一时刻只允许一个，重复点击直接忽略。
     * 之前「点几次以后提示主密码错误」就是多个解锁协程互相覆盖状态导致的。
     */
    private fun runBusy(text: String, block: suspend () -> Unit, onDone: () -> Unit = {}) {
        if (busy) return
        busy = true
        busyText = text
        error = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                onDone()
            } catch (e: Exception) {
                error = when (e) {
                    is WrongPasswordException -> "主密码不正确"
                    else -> e.message ?: "操作失败"
                }
            } finally {
                busy = false
                busyText = null
            }
        }
    }

    fun createVault(password: String) {
        if (password.length < 8) {
            error = "主密码至少 8 位"
            return
        }
        runBusy("正在创建保险库…", { repo.createVault(password.toCharArray()) }) {
            items = emptyList()
            screen = Screen.List
            message = "保险库已创建，请立刻做一次备份"
        }
    }

    fun unlock(password: String) {
        if (repo.isUnlocked()) {
            screen = Screen.List
            return
        }
        if (password.isEmpty()) return
        runBusy("正在解锁…", { repo.unlock(password.toCharArray()) }) {
            afterUnlock()
        }
    }

    /** 生物识别解出 DEK 之后调用。 */
    fun unlockWithDek(dekBytes: ByteArray) {
        if (repo.isUnlocked()) {
            screen = Screen.List
            return
        }
        runBusy("正在解锁…", { repo.unlockWithDek(dekBytes) }) {
            afterUnlock()
        }
    }

    private fun afterUnlock() {
        items = repo.items
        corruptedCount = repo.corruptedCount
        screen = Screen.List
        if (corruptedCount > 0) message = "有 $corruptedCount 条数据无法解密"
    }

    /** 自动填充前的二次验证：只校验主密码，不改变解锁状态。 */
    fun verifyPassword(password: String): Boolean =
        runCatching { repo.verifyPassword(password.toCharArray()) }.getOrDefault(false)

    fun lock() {
        repo.lock()
        items = emptyList()
        query = ""
        error = null
        screen = currentScreen()
    }

    fun openNew() {
        screen = Screen.Edit(VaultItem(), true)
    }

    fun openItem(item: VaultItem) {
        screen = Screen.Edit(item, false)
    }

    fun openSettings() {
        error = null
        screen = Screen.Settings
    }

    fun backToList() {
        screen = if (repo.isUnlocked()) Screen.List else currentScreen()
        error = null
    }

    fun save(item: VaultItem) {
        if (busy) return
        runBusy("正在保存…", { repo.save(item) }) {
            items = repo.items
            screen = Screen.List
            message = "已保存"
        }
    }

    fun delete(id: String) {
        if (busy) return
        runBusy("正在删除…", { repo.delete(id) }) {
            items = repo.items
            screen = Screen.List
            message = "已删除"
        }
    }

    fun toggleFavorite(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        if (busy) return
        runBusy("", { repo.setFavorite(id, !item.favorite) }) {
            items = repo.items
        }
    }

    fun exportText(): String = repo.exportText()

    /** 已读入内存、等待用户输入该备份密码的备份文本。 */
    var pendingImportText by mutableStateOf<String?>(null)
        private set

    fun loadBackupText(text: String) {
        pendingImportText = text
    }

    fun importPendingBackup(password: String) {
        val text = pendingImportText ?: return
        importText(text, password)
        pendingImportText = null
    }

    fun importText(text: String, password: String) {
        runBusy("正在导入备份…", { repo.importText(text, password.toCharArray()) }) {
            items = repo.items
            screen = Screen.List
            message = "已导入 ${items.size} 条"
        }
    }

    fun changeMasterPassword(newPassword: String) {
        if (newPassword.length < 8) {
            error = "新主密码至少 8 位"
            return
        }
        runBusy("正在更换主密码…", { repo.changePassword(newPassword.toCharArray()) }) {
            message = "主密码已更换"
        }
    }

    // ---------------- 生物识别 ----------------

    fun hasBiometricBinding(): Boolean = repo.hasBiometricBinding()

    fun biometricIv(): ByteArray? = repo.biometricIv()

    fun biometricCt(): ByteArray? = repo.biometricCt()

    fun dekBytesForBiometric(): ByteArray? = repo.dekBytesCopy()

    fun saveBiometricBinding(iv: ByteArray, ct: ByteArray) {
        repo.saveBiometricBinding(iv, ct)
        message = "已启用生物识别解锁"
    }

    fun disableBiometric() {
        repo.clearBiometricBinding()
        BiometricSeal.deleteKey()
        message = "已关闭生物识别解锁"
    }

    // ---------------- Bitwarden 导入 ----------------

    /** 固定的一份设备标识：新设备验证码与它绑定，随机生成会导致验证码永远对不上。 */
    /** 会话超时（分钟）：0 = 一离开就锁。来自 SharedPreferences，默认 15 分钟（与 Bitwarden 一致）。 */
    var sessionTimeoutMinutes by mutableStateOf(readTimeoutPref())

    /** 主题：跟随系统 / 明亮 / 黑色。 */
    var themeMode by mutableStateOf(readThemeModePref())

    /** 文字大小倍率（在系统字号之上再乘）。 */
    var fontScale by mutableStateOf(readFontScalePref())

    private fun readThemeModePref(): ThemeMode = runCatching {
        ThemeMode.valueOf(prefs().getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
    }.getOrDefault(ThemeMode.SYSTEM)

    /** 立即生效并落盘，主界面会随状态重组。 */
    fun applyThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs().edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    private fun readFontScalePref(): Float =
        runCatching { prefs().getFloat(KEY_FONT_SCALE, 1f) }.getOrDefault(1f)

    fun applyFontScale(scale: Float) {
        fontScale = scale.coerceIn(0.8f, 1.6f)
        prefs().edit().putFloat(KEY_FONT_SCALE, fontScale).apply()
    }

    private fun prefs() = getApplication<Application>()
        .getSharedPreferences("minivault", Context.MODE_PRIVATE)

    private fun readTimeoutPref(): Int = runCatching { prefs().getInt(KEY_SESSION_TIMEOUT, 15) }
        .getOrDefault(15)

    /** 每次自动填充都要求验证（指纹或主密码）。默认开启。 */
    var fillRequiresAuth by mutableStateOf(runCatching { prefs().getBoolean(KEY_FILL_AUTH, true) }.getOrDefault(true))

    fun applyFillRequiresAuth(enabled: Boolean) {
        fillRequiresAuth = enabled
        prefs().edit().putBoolean(KEY_FILL_AUTH, enabled).apply()
    }

    fun setSessionTimeout(minutes: Int) {
        sessionTimeoutMinutes = minutes.coerceAtLeast(0)
        prefs().edit().putInt(KEY_SESSION_TIMEOUT, sessionTimeoutMinutes).apply()
    }

    private fun bitwardenDeviceId(): String {
        val prefs = getApplication<Application>().getSharedPreferences("minivault", Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_BW_DEVICE, null)
        if (existing != null) return existing
        val created = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_BW_DEVICE, created).apply()
        return created
    }

    fun importFromBitwarden(
        baseUrl: String,
        email: String,
        password: String,
        twoFactorCode: String = "",
    ) {
        if (busy) return
        busy = true
        busyText = "正在连接 Bitwarden…"
        error = null
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    BitwardenClient.importAll(
                        baseUrl = baseUrl,
                        email = email,
                        password = password.toCharArray(),
                        twoFactorCode = twoFactorCode,
                        deviceIdentifier = bitwardenDeviceId(),
                    ) { progress ->
                        busyText = progress
                    }
                }
                val added = withContext(Dispatchers.IO) { repo.importItems(list) }
                items = repo.items
                message = "已从 Bitwarden 导入 $added 条"
            } catch (e: Exception) {
                error = e.message ?: "导入失败"
            } finally {
                busy = false
                busyText = null
            }
        }
    }

    // ---------------- WebDAV 云同步 ----------------

    var webdavUrl by mutableStateOf(prefs().getString(KEY_WEBDAV_URL, "") ?: "")
    var webdavUser by mutableStateOf(prefs().getString(KEY_WEBDAV_USER, "") ?: "")
    var webdavPass by mutableStateOf(prefs().getString(KEY_WEBDAV_PASS, "") ?: "")
    var webdavPath by mutableStateOf(prefs().getString(KEY_WEBDAV_PATH, "d-pwn/vault.json") ?: "d-pwn/vault.json")

    private fun webdavCredentials(): WebDavClient.Credentials =
        WebDavClient.Credentials(webdavUrl, webdavUser, webdavPass, webdavPath)

    fun saveWebDav(url: String, user: String, pass: String, path: String) {
        webdavUrl = url.trim()
        webdavUser = user.trim()
        webdavPass = pass
        webdavPath = path.trim()
        prefs().edit()
            .putString(KEY_WEBDAV_URL, webdavUrl)
            .putString(KEY_WEBDAV_USER, webdavUser)
            .putString(KEY_WEBDAV_PASS, webdavPass)
            .putString(KEY_WEBDAV_PATH, webdavPath)
            .apply()
        message = "已保存 WebDAV 配置"
    }

    fun webDavTest() {
        runBusy("正在连接 WebDAV…", { WebDavClient.probe(webdavCredentials()) }) {
            message = "WebDAV 连接正常"
        }
    }

    /** 上传当前保险库（密文原样上传，服务器看不到明文）。 */
    fun webDavUpload() {
        if (!repo.isInitialized()) {
            error = "还没有保险库"
            return
        }
        runBusy("正在上传到 WebDAV…", { WebDavClient.upload(webdavCredentials(), repo.rawText().toByteArray(Charsets.UTF_8)) }) {
            message = "已上传到 WebDAV"
        }
    }

    /** 从 WebDAV 恢复：覆盖本地库，然后用那份备份的主密码解锁。 */
    fun webDavRestore() {
        runBusy("正在从 WebDAV 下载…", {
            val text = WebDavClient.download(webdavCredentials())
            repo.replaceRaw(text)
        }) {
            items = emptyList()
            corruptedCount = 0
            screen = Screen.Unlock
            message = "已恢复云端备份，请用该备份的主密码解锁"
        }
    }

    // ---------------- 多设备同步 ----------------

    var lastSyncAt by mutableStateOf(runCatching { prefs().getLong("last_sync_at", 0L) }.getOrDefault(0L))
        private set

    /**
     * 双向同步：下载云端 → 与本机合并 → 写回云端。
     * 前提是两台设备用的是同一份保险库（同一把 DEK），否则会明确报冲突而不是悄悄毁数据。
     */
    fun syncNow() {
        if (!repo.isInitialized()) { error = "还没有保险库"; return }
        if (!repo.isUnlocked()) { error = "请先解锁，再点同步"; return }
        runBusy("正在同步…", {
            val c = webdavCredentials()
            val remoteText = WebDavClient.download(c)
            val report = repo.mergeFrom(remoteText)
            if (report.conflict) {
                throw IllegalStateException(
                    "云端那份属于另一个保险库（主密码/密钥不同），不能直接合并。" +
                        "请在其中一台上用「从云端恢复」改用同一份，或者确认要覆盖时用「上传到云端」。",
                )
            }
            WebDavClient.upload(c, repo.rawText().toByteArray(Charsets.UTF_8))
            val now = System.currentTimeMillis()
            prefs().edit().putLong("last_sync_at", now).apply()
            lastSyncAt = now
            "新增 ${report.added} · 更新 ${report.updated} · 删除 ${report.removed}"
        }) {
            items = repo.items
            corruptedCount = repo.corruptedCount
            message = "同步完成"
        }
    }

    /**
     * 复制到剪贴板，并在 [clearAfterMs] 后尝试清空。
     * 只有当前剪贴板内容仍是我们写进去的那串字符时才会清，避免误删用户后来复制的东西。
     */
    fun scheduleClipboardClear(copied: String, clearAfterMs: Long = 30_000L, clear: () -> Unit) {
        clipboardClearJob?.cancel()
        clipboardClearJob = viewModelScope.launch {
            delay(clearAfterMs)
            clear()
        }
    }

    fun consumeMessage() {
        message = null
    }

    fun clearError() {
        error = null
    }

    companion object {
        private const val KEY_BW_DEVICE = "bitwarden_device_id"
        private const val KEY_SESSION_TIMEOUT = "session_timeout_minutes"
        private const val KEY_FILL_AUTH = "fill_requires_auth"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_WEBDAV_URL = "webdav_url"
        private const val KEY_WEBDAV_USER = "webdav_user"
        private const val KEY_WEBDAV_PASS = "webdav_pass"
        private const val KEY_WEBDAV_PATH = "webdav_path"

        val Factory = viewModelFactory {
            initializer {
                VaultViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application)
            }
        }
    }
}