package org.rocks.browser

import org.rocks.browser.core.BrowserData
import java.io.InputStream

internal const val MAX_SESSION_BYTES = 4 * 1024 * 1024

/** null 只表示不存在；读取错误必须抛出，避免把不可读文件误当成新会话。 */
internal interface SessionFile {
    fun openRead(): InputStream?
    fun writeAtomically(contents: ByteArray)
}

/** 由单一 IO 消费者调用。恢复失败后保持只读，直到新建仓库并成功恢复。 */
internal class SessionRepository(private val file: SessionFile) {
    private var writable = false

    fun restore(): BrowserData? {
        writable = false
        val restored = file.openRead()?.use { stream ->
            // 在 JSON 解析之前限制读取量，不能先 readText 再检查长度。
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer, 0, minOf(buffer.size, MAX_SESSION_BYTES + 1 - bytes.size()))
                if (count == -1) break
                bytes.write(buffer, 0, count)
                require(bytes.size() <= MAX_SESSION_BYTES) { "Session file is too large" }
            }
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
            SessionCodec.decode(text)
        }
        writable = true
        return restored
    }

    /** 写入失败允许后续快照重试；恢复失败则禁止覆盖原件。 */
    fun save(data: BrowserData): Boolean {
        if (!writable) return false
        val bytes = SessionCodec.encode(data).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_SESSION_BYTES) { "Session file is too large" }
        file.writeAtomically(bytes)
        return true
    }
}
