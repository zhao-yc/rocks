package org.rocks.browser

import android.util.AtomicFile
import java.io.File
import java.io.InputStream

internal class AtomicSessionFile(path: File) : SessionFile {
    private val file = AtomicFile(path)

    override fun openRead(): InputStream? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead()
    }

    override fun writeAtomically(contents: ByteArray) {
        val stream = file.startWrite()
        try {
            stream.write(contents)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
