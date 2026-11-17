package com.yg.minivault.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 保险库文件读写：`filesDir/vault.json`，原子替换写入。
 * 整个文件只有一个，里面的每条记录都是独立密文。
 */
class VaultStore(private val dir: File) {

    private val file = File(dir, FILE_NAME)
    private val tmp = File(dir, "$FILE_NAME.tmp")

    fun exists(): Boolean = file.exists()

    fun read(): VaultDocument {
        if (!file.exists()) throw VaultCorruptedMissingFile()
        return VaultJson.decode(file.readText(Charsets.UTF_8))
    }

    fun write(doc: VaultDocument) {
        if (!dir.exists()) dir.mkdirs()
        tmp.writeText(VaultJson.encode(doc), Charsets.UTF_8)
        restrict(tmp)
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        restrict(file)
    }

    /** 只读文件头（不解锁），用于判断有没有绑定生物识别。 */
    fun peekHeader(): VaultHeader? = runCatching {
        if (file.exists()) VaultJson.decode(file.readText(Charsets.UTF_8)).header else null
    }.getOrNull()

    fun readRaw(): String = if (file.exists()) file.readText(Charsets.UTF_8) else ""

    /** 写入原始 JSON 文本（导入备份时用）。 */
    fun writeRaw(text: String) {
        if (!dir.exists()) dir.mkdirs()
        tmp.writeText(text, Charsets.UTF_8)
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        restrict(file)
    }

    /** 仅属主可读写（belt and braces，应用私有目录本身已经隔离）。 */
    private fun restrict(f: File) {
        runCatching {
            f.setReadable(false, false)
            f.setWritable(false, false)
            f.setReadable(true, true)
            f.setWritable(true, true)
        }
    }

    companion object {
        const val FILE_NAME = "vault.json"
    }
}

class VaultCorruptedMissingFile : Exception("保险库文件不存在")