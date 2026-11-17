package com.yg.minivault.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yg.minivault.crypto.Totp
import kotlinx.coroutines.delay

/** 显示一个会每秒刷新的 6 位验证码 + 剩余秒数。secret 不合法时显示占位符。 */
@Composable
fun TotpCodeView(secret: String, modifier: Modifier = Modifier) {
    val spec = remember(secret) { Totp.parse(secret) }
    var now by remember(secret) { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(secret) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    if (spec == null) {
        Text(
            "验证码格式无法识别",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
        return
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = spec.code(now),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "${spec.remainingSeconds(now)} 秒后刷新",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 设置页/编辑页用的说明行。 */
@Composable
fun TotpHint() {
    Column {
        Text(
            "支持 otpauth:// 链接或裸 Base32 密钥。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}