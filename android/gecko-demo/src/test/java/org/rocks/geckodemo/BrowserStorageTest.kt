package org.rocks.geckodemo

import java.io.InputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.rocks.browser.SessionFile
import org.rocks.browser.SessionRepository
import org.rocks.browser.core.BrowserData

class BrowserStorageTest {
    private class MemoryFile(var data: ByteArray? = null) : SessionFile {
        var writes = 0
        override fun openRead(): InputStream? = data?.inputStream()
        override fun writeAtomically(contents: ByteArray) { data = contents; writes++ }
    }
    @Test fun finalSavePrecedesANewActivityRestore() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val file = MemoryFile()
            val store = BrowserStorage(SessionRepository(file), scope)
            assertNull(store.restore().getOrThrow())
            val first = BrowserData().openTab("https://example.org")
            val latest = first.openTab("https://mozilla.org")
            store.save(first); store.save(latest)
            assertEquals(latest, withTimeout(5000) { store.restore().getOrThrow() })
        } finally { scope.cancel() }
    }
    @Test fun unreadableFutureDataIsNeverReplacedByQueuedSaves() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val original = """{"schemaVersion":999,"tabs":[]}""".toByteArray()
            val file = MemoryFile(original)
            val store = BrowserStorage(SessionRepository(file), scope)
            assertTrue(store.restore().isFailure)
            store.save(BrowserData())
            assertTrue(withTimeout(5000) { store.restore() }.isFailure)
            assertArrayEquals(original, file.data); assertEquals(0, file.writes)
        } finally { scope.cancel() }
    }
}
