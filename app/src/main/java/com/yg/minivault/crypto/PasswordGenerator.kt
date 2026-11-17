package com.yg.minivault.crypto

import java.security.SecureRandom

/**
 * 密码生成器。
 * 默认排除容易混淆的字符（0/O、1/l/I），保证复制粘贴到纸上也不出错。
 */
object PasswordGenerator {

    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?"

    private val rng = SecureRandom()

    fun generate(
        length: Int = 20,
        useLower: Boolean = true,
        useUpper: Boolean = true,
        useDigits: Boolean = true,
        useSymbols: Boolean = true,
    ): String {
        val pools = buildList {
            if (useLower) add(LOWER)
            if (useUpper) add(UPPER)
            if (useDigits) add(DIGITS)
            if (useSymbols) add(SYMBOLS)
        }
        if (pools.isEmpty()) return ""
        val wanted = length.coerceIn(pools.size, 128)
        val all = pools.joinToString("")

        // 先每个字符集各取一个，保证四类字符都出现，再补足长度，最后打乱。
        val chars = ArrayList<Char>(wanted)
        pools.forEach { pool -> chars.add(pool[rng.nextInt(pool.length)]) }
        while (chars.size < wanted) chars.add(all[rng.nextInt(all.length)])
        for (i in chars.lastIndex downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return String(chars.toCharArray())
    }

    /** 粗略强度评分，用于界面上的提示条。返回 0..4。 */
    fun score(password: String): Int {
        if (password.isEmpty()) return 0
        var classes = 0
        if (password.any { it.isLowerCase() }) classes++
        if (password.any { it.isUpperCase() }) classes++
        if (password.any { it.isDigit() }) classes++
        if (password.any { !it.isLetterOrDigit() }) classes++
        val lengthScore = when {
            password.length >= 20 -> 4
            password.length >= 16 -> 3
            password.length >= 12 -> 2
            password.length >= 8 -> 1
            else -> 0
        }
        val classScore = when (classes) {
            4 -> 4
            3 -> 3
            2 -> 2
            else -> 1
        }
        return minOf(lengthScore, classScore)
    }

    fun label(score: Int): String = when (score) {
        0 -> "极弱"
        1 -> "弱"
        2 -> "一般"
        3 -> "强"
        else -> "很强"
    }
}