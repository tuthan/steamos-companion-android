package io.github.tuthan.steamoscompanion.android.data

import android.content.Context
import android.util.AtomicFile
import io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository
import io.github.tuthan.steamoscompanion.core.client.PersistentStateLimits
import io.github.tuthan.steamoscompanion.core.ports.StateStore
import io.github.tuthan.steamoscompanion.core.ports.StateStoreInvalidDataException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** App-private AtomicFile adapter. It never writes plaintext state to disk. */
class AndroidAtomicStateStore internal constructor(private val baseFile: File) : StateStore {
    private val atomicFile = AtomicFile(baseFile)
    private val lock = pathLocks.computeIfAbsent(baseFile.absoluteFile.path) { ReentrantLock() }

    constructor(context: Context) : this(File(context.applicationContext.filesDir, STATE_FILE_NAME))

    override fun read(): ByteArray? = lock.withLock {
        val stream = try {
            atomicFile.openRead()
        } catch (error: FileNotFoundException) {
            val backup = File(baseFile.path + ".bak")
            val unfinished = File(baseFile.path + ".new")
            if (baseFile.exists() || backup.exists()) throw IOException("Atomic state file recovery failed", error)
            if (unfinished.exists() && !unfinished.delete()) throw IOException("Could not remove an unfinished first state write", error)
            return null
        }
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > PersistentStateLimits.MAX_ENVELOPE_BYTES) {
                    throw StateStoreInvalidDataException("Encrypted state file exceeds 65 KiB")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    override fun writeAtomically(value: ByteArray) = lock.withLock {
        if (value.size > PersistentStateLimits.MAX_ENVELOPE_BYTES) throw IOException("Encrypted state file exceeds 65 KiB")
        val output = atomicFile.startWrite()
        try {
            output.write(value)
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            runCatching { atomicFile.failWrite(output) }.exceptionOrNull()?.let(error::addSuppressed)
            if (error is IOException) throw error
            throw IOException("Atomic state write failed", error)
        }
    }

    override fun clear() = lock.withLock {
        atomicFile.delete()
        atomicSidecars().forEach { file ->
            if (file.exists() && !file.delete()) throw IOException("Could not remove app state file")
        }
        if (atomicSidecars().any(File::exists)) throw IOException("App state or AtomicFile backup remains")
    }

    internal fun testBaseFile(): File = baseFile

    private fun atomicSidecars(): List<File> = listOf(baseFile, File(baseFile.path + ".bak"), File(baseFile.path + ".new"))

    companion object {
        const val STATE_FILE_NAME = "companion-state.bin"
        private val pathLocks = ConcurrentHashMap<String, ReentrantLock>()

        fun createRepository(context: Context): EncryptedStateRepository = EncryptedStateRepository(
            AndroidAtomicStateStore(context.applicationContext),
            AndroidKeystoreStateCipher(),
        )
    }
}
