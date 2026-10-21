package org.rocks.browser

import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test
import org.rocks.browser.core.*

class SessionRepositoryTest {
    private class MemoryFile(var contents: ByteArray? = null) : SessionFile {
        var failRead = false
        var failWrite = false
        var writes = 0
        override fun openRead(): InputStream? {
            if (failRead) throw IOException("Read failed")
            return contents?.inputStream()
        }
        override fun writeAtomically(contents: ByteArray) {
            if (failWrite) throw IOException("Write failed")
            this.contents = contents.copyOf()
            writes++
        }
    }

    @Test fun firstLaunchCanPersistAndRestoreTheLatestSnapshot() {
        val file = MemoryFile()
        val repository = SessionRepository(file)
        assertNull(repository.restore())
        val first = BrowserData().openTab("https://example.com/")
        val latest = first.recordVisit(first.activeTabId, first.activeTab.url, "示例", 123)
            .toggleBookmark(124).copy(settings = BrowserSettings("baidu"))
        assertTrue(repository.save(first))
        assertTrue(repository.save(latest))
        assertEquals(latest, SessionRepository(file).restore())
    }

    @Test fun noWritesAreAllowedBeforeRestore() {
        val file = MemoryFile()
        assertFalse(SessionRepository(file).save(BrowserData()))
        assertEquals(0, file.writes)
    }

    @Test fun corruptAndFutureFilesArePreservedWhileBrowsingInMemory() {
        listOf("broken JSON", """{"schemaVersion":2,"tabs":[]}""").forEach { text ->
            val original = text.toByteArray()
            val file = MemoryFile(original)
            val repository = SessionRepository(file)
            assertTrue(runCatching { repository.restore() }.isFailure)
            assertFalse(repository.save(BrowserData().openTab("https://example.com/")))
            assertArrayEquals(original, file.contents)
            assertEquals(0, file.writes)
        }
    }

    @Test fun readFailureIsNotTreatedAsAnEmptySession() {
        val file = MemoryFile().apply { failRead = true }
        val repository = SessionRepository(file)
        assertTrue(runCatching { repository.restore() }.exceptionOrNull() is IOException)
        assertFalse(repository.save(BrowserData()))
        assertEquals(0, file.writes)
    }

    @Test fun oversizedReadStopsAtTheLimitAndClosesTheStream() {
        var consumed = 0
        var closed = false
        val file = object : SessionFile {
            override fun openRead() = object : InputStream() {
                override fun read(): Int { consumed++; return ' '.code }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    bytes.fill(' '.code.toByte(), offset, offset + length)
                    consumed += length
                    return length
                }
                override fun close() { closed = true }
            }
            override fun writeAtomically(contents: ByteArray) { fail("Must preserve oversized file") }
        }
        val repository = SessionRepository(file)
        assertTrue(runCatching { repository.restore() }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(MAX_SESSION_BYTES + 1, consumed)
        assertTrue(closed)
        assertFalse(repository.save(BrowserData()))
    }

    @Test fun invalidUtf8IsPreservedInsteadOfReplacingCharactersAndOverwriting() {
        val original = """{"schemaVersion":1,"tabs":[],"note":"""".toByteArray() +
            byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
        val file = MemoryFile(original)
        val repository = SessionRepository(file)
        assertTrue(runCatching { repository.restore() }.isFailure)
        assertFalse(repository.save(BrowserData()))
        assertArrayEquals(original, file.contents)
    }

    @Test fun validSessionExactlyAtTheByteLimitCanBeRestored() {
        val data = BrowserData()
        val json = SessionCodec.encode(data).toByteArray()
        val original = json + ByteArray(MAX_SESSION_BYTES - json.size) { ' '.code.toByte() }
        assertEquals(data, SessionRepository(MemoryFile(original)).restore())
    }

    @Test fun failedWriteLeavesPreviousSessionAndAllowsTheNextSnapshotToRetry() {
        val first = BrowserData()
        val file = MemoryFile(SessionCodec.encode(first).toByteArray())
        val repository = SessionRepository(file)
        assertEquals(first, repository.restore())
        val next = first.openTab("https://example.com/")
        file.failWrite = true
        assertTrue(runCatching { repository.save(next) }.exceptionOrNull() is IOException)
        assertEquals(first, SessionRepository(file).restore())
        file.failWrite = false
        assertTrue(repository.save(next))
        assertEquals(next, SessionRepository(file).restore())
    }

    @Test fun oversizedUtf8WriteIsRejectedBeforeTouchingTheFile() {
        val file = MemoryFile()
        val repository = SessionRepository(file)
        repository.restore()
        val huge = BrowserData(history = List(MAX_HISTORY) { index ->
            PageRecord("https://example.com/$index/" + "a".repeat(7900), "中".repeat(512), index.toLong())
        })
        assertTrue(runCatching { repository.save(huge) }.exceptionOrNull() is IllegalArgumentException)
        assertNull(file.contents)
        assertEquals(0, file.writes)
        assertTrue(repository.save(BrowserData()))
    }
}
