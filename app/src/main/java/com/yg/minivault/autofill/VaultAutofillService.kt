package com.yg.minivault.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.autofill.AutofillId
import com.yg.minivault.util.CrashLog

/**
 * 系统自动填充服务。
 *
 * 规则：
 *  1. 只有当输入页面对应「我们库里确实有这条登录」时才出候选，其余一律不弹；
 *  2. 未解锁 / 开启「填充前验证」/ 已超过会话超时 → 先走 FillResponse 级认证，
 *     认证通过后才把数据交回去；
 *  3. 任何异常都吞掉并返回空，绝不让系统把本服务判定为坏服务（那会直接导致填充不可用）。
 */
class VaultAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        try {
            handleFill(request, callback)
        } catch (t: Throwable) {
            CrashLog.append(this, "onFillRequest", t)
            runCatching { callback.onSuccess(null) }
        }
    }

    private fun handleFill(request: FillRequest, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) return callback.onSuccess(null)

        val hostPackage = structure.activityComponent?.packageName
        // 不要在自己的输入框上再弹自己的填充（会互相触发）
        if (hostPackage == packageName) return callback.onSuccess(null)

        val fields = Fields.collect(structure)
        if (fields.username == null && fields.password == null) return callback.onSuccess(null)

        val repo = FillSupport.repository(this)
        val matched = FillSupport.candidates(repo.items, fields.webDomain, hostPackage)
        // 没有对应条目：不出填充候选（用户要求「没账号的网站别弹」），
        // 但仍然要给出只含 SaveInfo 的响应 —— 否则新网站注册/改密码后系统不会调 onSaveRequest，
        // 用户就永远等不到「要不要存进密码库」的提醒。
        if (matched.isEmpty()) {
            callback.onSuccess(FillSupport.saveOnlyResponse(fields.username, fields.password))
            return
        }

        val response = if (FillSupport.needsAuth(this, repo.isUnlocked())) {
            FillSupport.authResponse(this, fields.username, fields.password, fields.webDomain)
        } else {
            FillSupport.dataResponse(this, fields.username, fields.password, matched)
        }
        callback.onSuccess(response)
    }

    /** 用户在网页/App 里提交了新的账号密码 → 弹确认页，问要不要存进保险库。 */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        try {
            val structure = request.fillContexts.lastOrNull()?.structure
            val values = structure?.let { Values.collect(it) }
            if (values == null || values.password.isBlank()) {
                callback.onSuccess()
                return
            }
            val intent = Intent(this, AutofillSaveActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(FillSupport.EXTRA_USERNAME_VALUE, values.username)
                putExtra(FillSupport.EXTRA_PASSWORD_VALUE, values.password)
                putExtra(FillSupport.EXTRA_DOMAIN, values.domain)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pending = PendingIntent.getActivity(
                    this,
                    FillSupport.RC_SAVE,
                    intent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                callback.onSuccess(pending.intentSender)
            } else {
                callback.onSuccess()
            }
        } catch (t: Throwable) {
            CrashLog.append(this, "onSaveRequest", t)
            runCatching { callback.onSuccess() }
        }
    }

    /** 从保存请求里取出用户刚填的值。 */
    class Values(val username: String, val password: String, val domain: String) {
        companion object {
            fun collect(structure: AssistStructure): Values {
                var user = ""
                var pass = ""
                var domain = ""
                fun visit(node: AssistStructure.ViewNode) {
                    val text = node.autofillValue?.takeIf { it.isText }?.textValue?.toString().orEmpty()
                    if (text.isNotBlank()) {
                        val inputType = node.inputType
                        val hints = node.autofillHints?.joinToString(" ").orEmpty().lowercase()
                        val hintText = (node.hint ?: "").lowercase()
                        val looksPassword = inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0 ||
                            inputType and InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD != 0 ||
                            hints.contains("password") || hintText.contains("password") || hintText.contains("密码")
                        if (looksPassword && pass.isBlank()) {
                            pass = text
                        } else if (user.isBlank()) {
                            user = text
                        }
                    }
                    if (domain.isBlank() && !node.webDomain.isNullOrEmpty()) domain = node.webDomain!!
                    for (i in 0 until node.childCount) visit(node.getChildAt(i))
                }
                for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode)
                return Values(user, pass, domain)
            }
        }
    }

    /** 一个请求里我们关心的字段。 */
    class Fields(
        val username: AutofillId?,
        val password: AutofillId?,
        val webDomain: String?,
    ) {
        companion object {
            fun collect(structure: AssistStructure): Fields {
                val collector = Collector()
                for (i in 0 until structure.windowNodeCount) {
                    collector.visit(structure.getWindowNodeAt(i).rootViewNode)
                }
                return Fields(collector.username, collector.password, collector.domain)
            }
        }
    }

    private class Collector {
        var username: AutofillId? = null
        var password: AutofillId? = null
        var domain: String? = null

        fun visit(node: AssistStructure.ViewNode) {
            val id = node.autofillId
            if (id != null && (username == null || password == null)) {
                val hintText = (node.hint ?: "").lowercase()
                val hints = node.autofillHints?.joinToString(" ").orEmpty().lowercase()
                val inputType = node.inputType
                val looksLikePassword = inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0 ||
                    inputType and InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD != 0 ||
                    hints.contains("password") ||
                    hintText.contains("password") ||
                    hintText.contains("密码")
                val looksLikeUser = hints.contains("username") ||
                    hints.contains("email") ||
                    hints.contains("phone") ||
                    hintText.contains("账号") ||
                    hintText.contains("帐号") ||
                    hintText.contains("邮箱") ||
                    hintText.contains("用户名") ||
                    hintText.contains("username") ||
                    hintText.contains("email")

                if (looksLikePassword && password == null) {
                    password = id
                } else if (looksLikeUser && username == null) {
                    username = id
                }
            }
            if (domain == null && !node.webDomain.isNullOrEmpty()) {
                domain = node.webDomain
            }
            for (i in 0 until node.childCount) {
                visit(node.getChildAt(i))
            }
        }
    }
}
