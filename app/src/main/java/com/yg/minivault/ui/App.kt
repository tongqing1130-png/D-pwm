package com.yg.minivault.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.yg.minivault.ui.theme.GlassBackground
import com.yg.minivault.ui.screens.EditScreen
import com.yg.minivault.ui.screens.ListScreen
import com.yg.minivault.ui.screens.SettingsScreen
import com.yg.minivault.ui.screens.SetupScreen
import com.yg.minivault.ui.screens.UnlockScreen

@Composable
fun AppRoot(
    vm: VaultViewModel,
    onExport: () -> Unit,
    onImportPick: () -> Unit,
    onCopy: (label: String, value: String) -> Unit,
) {
    val context = LocalContext.current

    LaunchedEffect(vm.message) {
        val msg = vm.message
        if (msg != null) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }

    // 设置/编辑页里返回键先回列表，而不是直接退出应用
    BackHandler(enabled = vm.screen is Screen.Settings || vm.screen is Screen.Edit) {
        vm.backToList()
    }

    Box(Modifier.fillMaxSize()) {
        GlassBackground()
        when (val screen = vm.screen) {
            is Screen.Setup -> SetupScreen(vm)
            is Screen.Unlock -> UnlockScreen(vm)
            is Screen.List -> ListScreen(vm, onExport = onExport, onCopy = onCopy)
            is Screen.Settings -> SettingsScreen(vm, onExport = onExport, onImportPick = onImportPick)
            is Screen.Edit -> EditScreen(vm, screen.item, screen.isNew, onCopy = onCopy)
        }
    }
}