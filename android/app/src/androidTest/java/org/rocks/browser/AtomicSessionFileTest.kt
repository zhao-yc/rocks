package org.rocks.browser

import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.rocks.browser.core.BrowserData

/** 需要设备上的真实 AtomicFile；JVM 存储测试不能替代此验证。 */
class AtomicSessionFileTest {
    private val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "session-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val path = File(directory, "state.json")

    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun missingFileCanBeWrittenAndRestored() {
        val repository = SessionRepository(AtomicSessionFile(path))
        assertNull(repository.restore())
        val data = BrowserData().openTab("https://example.com/")
        assertTrue(repository.save(data))
        assertEquals(data, SessionRepository(AtomicSessionFile(path)).restore())
    }

    @Test fun legacyBackupIsRecoveredWhenBaseFileIsMissing() {
        val data = BrowserData().openTab("https://example.com/")
        File(path.path + ".bak").writeText(SessionCodec.encode(data))
        assertEquals(data, SessionRepository(AtomicSessionFile(path)).restore())
    }

    @Test fun abandonedWriteRestoresThePreviouslyCommittedSession() {
        val data = BrowserData().openTab("https://example.com/")
        val repository = SessionRepository(AtomicSessionFile(path))
        repository.restore()
        assertTrue(repository.save(data))
        // 关闭流但不提交或回滚，模拟写入过程中断后留下的磁盘状态。
        AtomicFile(path).startWrite().use { it.write("incomplete JSON".toByteArray()) }
        assertEquals(data, SessionRepository(AtomicSessionFile(path)).restore())
    }

    @Test fun unknownVersionRemainsUntouchedAfterAnAttemptedSave() {
        val original = """{"schemaVersion":99,"tabs":[]}"""
        path.writeText(original)
        val repository = SessionRepository(AtomicSessionFile(path))
        assertTrue(runCatching { repository.restore() }.isFailure)
        assertFalse(repository.save(BrowserData()))
        assertEquals(original, path.readText())
    }
}
