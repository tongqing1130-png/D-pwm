package com.yg.minivault.data

import java.util.UUID

enum class ItemType(val label: String) {
    LOGIN("登录"),
    NOTE("笔记"),
    CARD("卡片");

    companion object {
        fun fromName(name: String): ItemType =
            entries.firstOrNull { it.name == name } ?: LOGIN
    }
}

/**
 * 内存中的一条明文条目。
 * 只有 [id] / [type] / [favorite] / [updatedAt] 会以明文写进文件，
 * 其余字段全部位于该条目的密文里。
 */
data class VaultItem(
    val id: String = UUID.randomUUID().toString(),
    val type: ItemType = ItemType.LOGIN,
    val favorite: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val title: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    /** TOTP 密钥：可以是 otpauth:// 链接，也可以是裸 Base32。 */
    val totp: String = "",
) {
    /** 供列表搜索使用的可检索文本。 */
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return title.contains(q, ignoreCase = true) ||
            username.contains(q, ignoreCase = true) ||
            url.contains(q, ignoreCase = true) ||
            notes.contains(q, ignoreCase = true)
    }

    /** 用于自动填充/URL 匹配的主机名（取第一个网址）。 */
    fun host(): String = UrlHosts.hosts(url).firstOrNull().orEmpty()

    /** 一条记录可以有多个网址（编辑时每行一个）。 */
    fun hosts(): List<String> = UrlHosts.hosts(url)

    companion object {
        fun empty(): VaultItem = VaultItem()
    }
}