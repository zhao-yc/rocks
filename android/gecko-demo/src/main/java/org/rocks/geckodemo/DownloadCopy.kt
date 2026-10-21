package org.rocks.geckodemo

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Streams bytes without buffering an entire download; rejects a truncated identity response. */
internal suspend fun copyDownload(input: InputStream, output: OutputStream, expected: Long,
    progress: suspend (Long) -> Unit): Long {
    val buffer = ByteArray(64 * 1024)
    var written = 0L
    var reported = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count); written += count
        if (written - reported >= 256 * 1024) { reported = written; progress(written) }
    }
    if (expected >= 0) check(written == expected) { "Incomplete download" }
    return written
}
