package com.yg.minivault.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.yg.minivault.MiniVaultApp
import com.yg.minivault.R
import com.yg.minivault.data.ItemType
import com.yg.minivault.data.UrlHosts
import com.yg.minivault.data.VaultItem

/**
 * 自动填充的公共构件：候选筛选、认证请求、数据集构建。
 *
 * 关键点：锁定／需要验证时返回的是 **FillResponse 级认证**（setAuthentication(ids, ...)），
 * 而不是一个没有 value 的 Dataset —— 后者会被系统直接丢弃，表现为「候选根本不出现」。
 */
object FillSupport {

    const val RC_AUTH = 1001

    const val EXTRA_USERNAME_ID = "com.yg.minivault.extra.USERNAME_ID"
    const val EXTRA_PASSWORD_ID = "com.yg.minivault.extra.PASSWORD_ID"
    const val EXTRA_DOMAIN = "com.yg.minivault.extra.DOMAIN"
    const val EXTRA_USERNAME_VALUE = "com.yg.minivault.extra.USERNAME_VALUE"
    const val EXTRA_PASSWORD_VALUE = "com.yg.minivault.extra.PASSWORD_VALUE"
    const val RC_SAVE = 1002

    private const val PREFS = "minivault"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 每次填充都验证（默认开启）。 */
    fun fillRequiresAuth(context: Context): Boolean =
        runCatching { prefs(context).getBoolean("fill_requires_auth", true) }.getOrDefault(true)

    fun sessionTimeoutMinutes(context: Context): Int =
        runCatching { prefs(context).getInt("session_timeout_minutes", 15) }.getOrDefault(15)

    /** 是否需要先验证：未解锁 / 每次验证 / 已超过会话超时。 */
    fun needsAuth(context: Context, unlocked: Boolean): Boolean {
        if (!unlocked) return true
        if (fillRequiresAuth(context)) return true
        val timeout = sessionTimeoutMinutes(context)
        if (timeout <= 0) return true
        val last = runCatching { prefs(context).getLong("last_foreground_at", 0L) }.getOrDefault(0L)
        return last > 0L && System.currentTimeMillis() - last >= timeout * 60_000L
    }

    fun presentation(context: Context, item: VaultItem): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, item.title.ifBlank { item.host().ifBlank { "D-pwm 条目" } })
            setTextViewText(R.id.autofill_subtitle, item.username.ifBlank { "点击填充" })
        }

    fun dataset(context: Context, item: VaultItem, usernameId: AutofillId?, passwordId: AutofillId?): Dataset {
        val views = presentation(context, item)
        val builder = Dataset.Builder(views)
        usernameId?.let { builder.setValue(it, AutofillValue.forText(item.username), views) }
        passwordId?.let { builder.setValue(it, AutofillValue.forText(item.password), views) }
        return builder.build()
    }

    fun dataResponse(
        context: Context,
        usernameId: AutofillId?,
        passwordId: AutofillId?,
        items: List<VaultItem>,
    ): FillResponse? {
        val ids = listOfNotNull(usernameId, passwordId)
        if (ids.isEmpty() || items.isEmpty()) return null
        val builder = FillResponse.Builder()
        items.take(10).forEach { builder.addDataset(dataset(context, it, usernameId, passwordId)) }
        saveInfo(usernameId, passwordId)?.let { builder.setSaveInfo(it) }
        return builder.build()
    }

    /** 回调给系统的「先认证」响应。 */
    fun authResponse(
        context: Context,
        usernameId: AutofillId?,
        passwordId: AutofillId?,
        domain: String?,
    ): FillResponse? {
        val ids = listOfNotNull(usernameId, passwordId).toTypedArray()
        if (ids.isEmpty()) return null
        val intent = Intent(context, AutofillAuthActivity::class.java).apply {
            // 绝不能加 FLAG_ACTIVITY_NEW_TASK：一旦跨任务，系统会取消这次
            // 认证的 ActivityResult，于是表现为「跳回网页但什么都没填」。
            putExtra(EXTRA_USERNAME_ID, usernameId)
            putExtra(EXTRA_PASSWORD_ID, passwordId)
            putExtra(EXTRA_DOMAIN, domain)
        }
        val sender = PendingIntent.getActivity(
            context,
            RC_AUTH,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        )
        val views = RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, "D-pwm")
            setTextViewText(R.id.autofill_subtitle, "点击验证指纹或主密码后填充")
        }
        val builder = FillResponse.Builder().setAuthentication(ids, sender.intentSender, views)
        saveInfo(usernameId, passwordId)?.let { builder.setSaveInfo(it) }
        return builder.build()
    }

    /**
     * 只带「保存能力」的响应：新网站上没匹配到任何条目时也要返回它，
     * 否则系统根本不会在用户提交表单后回调 onSaveRequest ——
     * 那正是「新注册账号不会被提醒保存」的原因。
     */
    fun saveOnlyResponse(usernameId: AutofillId?, passwordId: AutofillId?): FillResponse? {
        val info = saveInfo(usernameId, passwordId) ?: return null
        return FillResponse.Builder().setSaveInfo(info).build()
    }

    /** 让系统在用户提交表单后回调 onSaveRequest。 */
    fun saveInfo(usernameId: AutofillId?, passwordId: AutofillId?): SaveInfo? {
        val pid = passwordId ?: return null
        val required = listOfNotNull(pid).toTypedArray()
        val optional = listOfNotNull(usernameId).takeIf { it.isNotEmpty() }?.toTypedArray()
        val builder = SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD, required)
        optional?.let { builder.setOptionalIds(it) }
        return builder.build()
    }

    /** 全部可填充的登录条目（按上限截断）。 */
    fun logins(items: List<VaultItem>, limit: Int = 10): List<VaultItem> =
        items.filter { it.type == ItemType.LOGIN && it.password.isNotBlank() }.take(limit)

    /**
     * 候选筛选：
     *  - 有 webDomain：只认域名匹配（多网址也支持）；没命中就返回空 → 系统不弹候选
     *  - 原生 App（没有 webDomain）：用包名与条目网址做匹配，匹配不上也不弹
     */
    fun candidates(
        items: List<VaultItem>,
        webDomain: String?,
        packageName: String? = null,
    ): List<VaultItem> {
        val logins = items.filter { it.type == ItemType.LOGIN && it.password.isNotBlank() }
        val domain = webDomain?.trim()?.takeIf { it.isNotEmpty() }
        if (domain != null) {
            return logins.filter { item -> item.hosts().any { UrlHosts.matches(it, domain) } }
        }
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return logins.filter { item ->
            item.hosts().any { host ->
                host.isNotEmpty() && (pkg.contains(host) || host.contains(pkg))
            }
        }
    }

    /** 从应用进程里取仓库（Activity 冷启动也安全：MiniVaultApp 会重建它）。 */
    fun repository(context: Context) = (context.applicationContext as MiniVaultApp).repository
}
