package com.yg.minivault.net

import android.util.Base64
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * 极简 WebDAV 客户端：只做「整份 vault.json 上传 / 下载」。
 *
 * 上传的永远是**已经加密**的保险库文件，服务器端拿到也只是密文；
 * 但如果用别人的服务器，仍然建议开 HTTPS（地址不写 scheme 时默认 https://）。
 */
object WebDavClient {

    data class Credentials(
        val url: String,
        val user: String,
        val password: String,
        val path: String,
    )

    /** 拼出最终文件 URL；顺带容错用户少写 https:// 或多写斜杠。 */
    fun endpoint(c: Credentials): String {
        var base = c.url.trim()
        if (base.isEmpty()) throw IllegalArgumentException("请先填写 WebDAV 地址")
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) {
            base = "https://$base"
        }
        base = base.trimEnd('/')
        val p = c.path.trim().trimStart('/')
        return if (p.isEmpty()) base else "$base/$p"
    }

    private fun hostOf(url: String): String =
        runCatching { URL(url).host }.getOrNull() ?: url

    private fun open(url: String, c: Credentials, method: String): HttpURLConnection {
        val host = hostOf(url)
        if (host == "127.0.0.1" || host == "localhost" || host == "::1") {
            throw IllegalStateException(
                "手机上的 127.0.0.1 指的是手机自己，连不到电脑。\n" +
                    "请把服务器地址改成电脑的局域网地址，例如 http://192.168.1.10:5244/dav（电脑和手机要在同一个 Wi-Fi）。",
            )
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "*/*")
        if (c.user.isNotEmpty() || c.password.isNotEmpty()) {
            val raw = "${c.user}:${c.password}".toByteArray(Charsets.UTF_8)
            conn.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP))
        }
        return conn
    }

    private fun body(conn: HttpURLConnection): String = runCatching {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }.getOrDefault("")

    /** 单次 PUT，返回 (HTTP 状态码, 响应体)。 */
    private fun putOnce(url: String, c: Credentials, bytes: ByteArray): Pair<Int, String> {
        val conn = open(url, c, "PUT")
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            conn.responseCode to body(conn)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 用裸 Socket 发 MKCOL 建目录 —— HttpURLConnection 的方法白名单里没有 MKCOL，
     * 只能自己拼 HTTP 报文。仅支持明文 http（局域网 Alist 就是 http）。
     */
    private fun mkcol(url: String, c: Credentials): Int? {
        val u = URL(url)
        if (u.protocol != "http") return null
        val port = if (u.port > 0) u.port else 80
        val path = u.path.ifEmpty { "/" }
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(u.host, port), 10_000)
                socket.soTimeout = 10_000
                val sb = StringBuilder()
                sb.append("MKCOL ").append(path).append(" HTTP/1.1\r\n")
                sb.append("Host: ").append(u.host).append(':').append(port).append("\r\n")
                sb.append("Content-Length: 0\r\n")
                if (c.user.isNotEmpty() || c.password.isNotEmpty()) {
                    val raw = "${c.user}:${c.password}".toByteArray(Charsets.UTF_8)
                    sb.append("Authorization: Basic ")
                        .append(Base64.encodeToString(raw, Base64.NO_WRAP))
                        .append("\r\n")
                }
                sb.append("Connection: close\r\n\r\n")
                val out = socket.getOutputStream()
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
                out.flush()
                val line = socket.getInputStream().bufferedReader().readLine()
                line?.split(' ')?.getOrNull(1)?.toIntOrNull()
            }
        }.getOrNull()
    }

    /** 逐级创建 path 里的每一层目录（不含文件名）。 */
    private fun ensureDirs(c: Credentials) {
        val segs = c.path.trim().trim('/').split('/').filter { it.isNotBlank() }.dropLast(1)
        if (segs.isEmpty()) return
        var acc = c.url.trim()
        if (!acc.startsWith("http://", true) && !acc.startsWith("https://", true)) acc = "https://$acc"
        acc = acc.trimEnd('/')
        for (s in segs) {
            acc = "$acc/$s"
            mkcol(acc, c)
        }
    }

    fun upload(c: Credentials, bytes: ByteArray): String {
        val url = endpoint(c)
        var (code, text) = putOnce(url, c, bytes)
        if (code == 404 || code == 409) {
            // 目录不存在：先试着建目录再重传一次
            ensureDirs(c)
            val retry = putOnce(url, c, bytes)
            code = retry.first
            text = retry.second
        }
        if (code !in 200..299) {
            val hint = if (code == 404 || code == 409) {
                "\n目标目录不存在，且自动创建失败。请在 Alist 的文件管理里手动建好目录（路径里的文件夹名要与它完全一致，区分大小写）。"
            } else ""
            throw IllegalStateException("上传失败（$code）@ ${hostOf(url)}：${text.take(200)}$hint")
        }
        return "已上传 ${bytes.size / 1024} KB 到 ${hostOf(url)}"
    }

    fun download(c: Credentials): String {
        val url = endpoint(c)
        val conn = open(url, c, "GET")
        return try {
            val code = conn.responseCode
            val text = body(conn)
            if (code !in 200..299) {
                throw IllegalStateException("下载失败（$code）@ ${hostOf(url)}：${text.take(200)}")
            }
            if (text.startsWith("<!DOCTYPE", true) || text.startsWith("<html", true)) {
                throw IllegalStateException("服务器返回的是网页而不是保险库文件，请检查地址/路径（$url）")
            }
            text
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 探活。注意：Java 的 HttpURLConnection 只允许 OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE/PATCH，
     * 用 PROPFIND 会直接抛 "Expected one of [...] but was PROPFIND"，所以这里用 GET 探。
     */
    fun probe(c: Credentials): String {
        val url = endpoint(c)
        val host = hostOf(url)
        val conn = open(url, c, "GET")
        return try {
            val code = conn.responseCode
            val text = body(conn)
            val looksHtml = text.startsWith("<!DOCTYPE", true) || text.startsWith("<html", true)
            when {
                code in 200..299 && looksHtml -> "连接正常（$code，目录可访问）"
                code in 200..299 -> "连接正常（$code，云端已有保险库文件）"
                code == 404 -> "账号与地址正常，但云端还没有这个文件；首次请点「上传到云端」"
                code == 401 || code == 403 ->
                    throw IllegalStateException("认证失败（$code）：用户名/密码要是 Alist 或网盘的应用密码，不是网盘登录账号")
                code == 405 || code == 501 ->
                    "服务器接受了认证（$code），可以尝试直接上传"
                else -> throw IllegalStateException("连接失败（$code）@ $host：${text.take(200)}")
            }
        } finally {
            conn.disconnect()
        }
    }
}
