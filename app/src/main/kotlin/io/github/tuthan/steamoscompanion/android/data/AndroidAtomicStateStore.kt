package io.github.tuthan.steamoscompanion.android.data

import android.content.Context
import android.util.AtomicFile
import io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository
import io.github.tuthan.steamoscompanion.core.client.PersistentStateLimits
import io.github.tuthan.steamoscompanion.core.client.StateEnvelopeCodec
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
    private val firstWriteMarker = File(baseFile.path + FIRST_WRITE_MARKER_SUFFIX)
    private val lock = pathLocks.computeIfAbsent(baseFile.absoluteFile.path) { ReentrantLock() }

    constructor(context: Context) : this(File(context.applicationContext.filesDir, STATE_FILE_NAME))

    override fun read(): ByteArray? = lock.withLock {
        val firstWritePending = firstWriteMarker.exists()
        val backup = File(baseFile.path + ".bak")
        val unfinished = File(baseFile.path + ".new")
        val backupWasPresent = backup.exists()
        val stream = try {
            atomicFile.openRead()
        } catch (error: FileNotFoundException) {
            if (baseFile.exists() || backup.exists()) throw IOException("Atomic state file recovery failed", error)
            if (unfinished.exists() && !unfinished.delete()) throw IOException("Could not remove an unfinished first state write", error)
            if (firstWritePending && firstWriteMarker.exists() && !firstWriteMarker.delete()) {
                throw IOException("Could not remove the first-write recovery marker", error)
            }
            return null
        }
        val value = stream.use { input ->
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
        if (firstWritePending) {
            // On API 26–29 AtomicFile can leave a first-write base file with no backup after a
            // process death. Only the marker created before that first write permits treating
            // an invalid envelope as an uncommitted empty state; ordinary corruption stays damaged.
            if (!backupWasPresent && runCatching {
                    StateEnvelopeCodec.decode(value, PersistentStateLimits.STATE_KEY_ALIAS)
                }.isFailure
            ) {
                atomicFile.delete()
                if (unfinished.exists()) unfinished.delete()
                if (!firstWriteMarker.delete()) throw IOException("Could not remove the first-write recovery marker")
                return null
            }
            if (!firstWriteMarker.delete()) throw IOException("Could not remove the first-write recovery marker")
        }
        value
    }

    override fun writeAtomically(value: ByteArray) = lock.withLock {
        if (value.size > PersistentStateLimits.MAX_ENVELOPE_BYTES) throw IOException("Encrypted state file exceeds 65 KiB")
        val firstWrite = !baseFile.exists() && !File(baseFile.path + ".bak").exists() && !File(baseFile.path + ".new").exists()
        if (firstWrite) {
            baseFile.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Could not create state directory") }
            try {
                firstWriteMarker.outputStream().use { marker ->
                    marker.write(1)
                    marker.flush()
                    marker.fd.sync()
                }
            } catch (error: Exception) {
                throw IOException("Could not record the first state write", error)
            }
        }
        val output = atomicFile.startWrite()
        try {
            output.write(value)
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            runCatching { atomicFile.failWrite(output) }.exceptionOrNull()?.let(error::addSuppressed)
            if (firstWrite) {
                runCatching { atomicFile.delete() }
                runCatching { firstWriteMarker.delete() }
            }
            if (error is IOException) throw error
            throw IOException("Atomic state write failed", error)
        }
        if (firstWrite) runCatching { firstWriteMarker.delete() }
    }

    override fun clear() = lock.withLock {
        atomicFile.delete()
        atomicSidecars().forEach { file ->
            if (file.exists() && !file.delete()) throw IOException("Could not remove app state file")
        }
        if (atomicSidecars().any(File::exists)) throw IOException("App state or AtomicFile backup remains")
    }

    internal fun testBaseFile(): File = baseFile

    private fun atomicSidecars(): List<File> = listOf(baseFile, File(baseFile.path + ".bak"), File(baseFile.path + ".new"), firstWriteMarker)

    companion object {
        const val STATE_FILE_NAME = "companion-state.bin"
        internal const val FIRST_WRITE_MARKER_SUFFIX = ".first-write"
        private val pathLocks = ConcurrentHashMap<String, ReentrantLock>()

        fun createRepository(context: Context): EncryptedStateRepository = EncryptedStateRepository(
            AndroidAtomicStateStore(context.applicationContext),
            AndroidKeystoreStateCipher(),
        )
    }
}
