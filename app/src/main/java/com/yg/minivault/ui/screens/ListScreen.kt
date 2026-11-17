package com.yg.minivault.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yg.minivault.data.ItemType
import com.yg.minivault.data.VaultItem
import com.yg.minivault.ui.VaultViewModel
import com.yg.minivault.ui.theme.glassSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    vm: VaultViewModel,
    onExport: () -> Unit,
    onCopy: (label: String, value: String) -> Unit,
) {
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("D-pwm") },
                actions = {
                    TextButton(onClick = { vm.openSettings() }) { Text("设置") }
                },
            )
        },
        floatingActionButton = {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .glassSurface(shape = CircleShape, strong = true)
                    .clickable { vm.openNew() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "新建条目",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // 类型筛选（像 Bitwarden 首页的分类），带各自条目数
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TypeChip(
                    label = "全部",
                    count = vm.countOf(null),
                    selected = vm.typeFilter == null,
                    onClick = { vm.selectType(null) },
                )
                ItemType.entries.forEach { type ->
                    TypeChip(
                        label = type.label,
                        count = vm.countOf(type),
                        selected = vm.typeFilter == type,
                        onClick = { vm.selectType(type) },
                    )
                }
            }

            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                label = { Text("搜索标题 / 用户名 / 网址 / 备注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (vm.corruptedCount > 0) {
                Text(
                    "有 ${vm.corruptedCount} 条记录无法解密（文件可能被改动过）",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            val list = vm.filteredItems
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            vm.items.isEmpty() -> "还没有条目，点右下角 + 添加第一条"
                            vm.typeFilter != null && vm.query.isBlank() ->
                                "「${vm.typeFilter?.label}」分类下还没有条目"
                            else -> "没有匹配的结果"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.id }) { item ->
                        ItemCard(
                            item = item,
                            onOpen = { vm.openItem(item) },
                            onCopy = onCopy,
                            onToggleFavorite = { vm.toggleFavorite(item.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text("$label $count") },
    )
}

@Composable
private fun ItemCard(
    item: VaultItem,
    onOpen: () -> Unit,
    onCopy: (String, String) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface()
            .clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title.ifBlank { "(无标题)" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = item.type.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.username.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.username,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.url.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.totp.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "TOTP",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    TotpCodeView(secret = item.totp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onCopy(item.title.ifBlank { "密码" }, item.password) }) {
                    Text("复制密码")
                }
                TextButton(onClick = { onCopy("用户名", item.username) }) {
                    Text("复制账号")
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onToggleFavorite) {
                    Text(if (item.favorite) "★ 已收藏" else "☆ 收藏")
                }
            }
        }
    }
}