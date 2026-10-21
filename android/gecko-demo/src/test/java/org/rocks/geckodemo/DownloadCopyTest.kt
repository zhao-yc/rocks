package org.rocks.geckodemo

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DownloadCopyTest {
    @Test fun largeDownloadStreamsExactlyAndReportsProgress() = runBlocking {
        val bytes = ByteArray(600_000) { (it % 253).toByte() }
        val target = ByteArrayOutputStream()
        val updates = mutableListOf<Long>()
        assertEquals(bytes.size.toLong(), copyDownload(bytes.inputStream(), target, bytes.size.toLong()) { updates.add(it) })
        assertArrayEquals(bytes, target.toByteArray())
        assertTrue(updates.isNotEmpty())
    }
    @Test fun truncatedResponseCannotBeReportedAsSuccessful() = runBlocking {
        val result = runCatching { copyDownload(byteArrayOf(1,2).inputStream(), ByteArrayOutputStream(), 8) {} }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }
    @Test fun cancellationStopsFurtherWrites() = runBlocking {
        val target = ByteArrayOutputStream()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            copyDownload(ByteArray(1_000_000).inputStream(), target, -1) { currentCoroutineContext().cancel() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(target.size() < 1_000_000)
    }
}
