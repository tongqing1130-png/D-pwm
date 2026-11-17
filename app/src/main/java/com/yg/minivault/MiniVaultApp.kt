package com.yg.minivault

import android.app.Application
import com.yg.minivault.data.VaultRepository
import com.yg.minivault.data.VaultStore
import com.yg.minivault.util.CrashLog
import java.io.File

class MiniVaultApp : Application() {

    lateinit var repository: VaultRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // 兜底：任何未捕获异常先落盘再交给系统，方便用户把日志发回来
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashLog.append(this, "uncaught in ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }

        repository = VaultRepository(VaultStore(File(filesDir, "vault")))
    }
}