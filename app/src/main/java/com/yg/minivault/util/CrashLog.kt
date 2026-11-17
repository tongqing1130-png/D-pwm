package com.yg.minivault.util

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Date

/**
 * 极简崩溃/异常日志。
 *
 * 为什么需要：自动填充是系统在别的应用里回调我们，出错时用户只看到「闪退」，
 * 没有 logcat 就完全没法定位。这里把异常落到应用私有目录，设置页可以查看，
 * 用户复制几行发过来就能复现问题。
 */
object CrashLog {

    private const val FILE_NAME = "errors.log"
    private const val MAX_BYTES = 256 * 1024

    /**
     * 面包屑：记录「走到了哪一步」，不是错误。不写堆栈，避免诊断页里看起来像崩溃。
     */
    fun note(context: Context, tag: String, message: String) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) file.delete()
            file.appendText("[${Date()}] $tag $message\n")
        }
    }

    fun append(context: Context, tag: String, throwable: Throwable) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) file.delete()
            file.appendText("[${Date()}] $tag\n${Log.getStackTraceString(throwable)}\n\n")
        }
    }

    fun read(context: Context): String = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.readText().takeLast(20_000) else ""
    }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}