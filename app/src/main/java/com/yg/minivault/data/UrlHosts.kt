package com.yg.minivault.data

/** 网址解析：一条记录可以写多个网址（每行一个，也接受 ; , 空格分隔）。 */
object UrlHosts {

    private val SPLIT = Regex("[\\s,;，；|]+")

    fun split(raw: String): List<String> =
        raw.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    fun rawHost(url: String): String = url
        .trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore('?')
        .lowercase()
        .removePrefix("www.")

    fun hosts(raw: String): List<String> =
        split(raw).map { rawHost(it) }.filter { it.isNotEmpty() }.distinct()

    /** 域名是否匹配（自身、子域、父域都算）。 */
    fun matches(host: String, domain: String): Boolean {
        val h = rawHost(host)
        val d = rawHost(domain)
        if (h.isEmpty() || d.isEmpty()) return false
        return h == d || h.endsWith(".$d") || d.endsWith(".$h")
    }
}
