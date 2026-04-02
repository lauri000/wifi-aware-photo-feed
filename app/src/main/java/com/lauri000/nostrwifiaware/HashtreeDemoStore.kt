package com.lauri000.nostrwifiaware

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import uniffi.nearby_hashtree_ffi.computeNhashFromFile

data class DemoBlobInfo(
    val nhash: String,
    val sizeBytes: Long,
    val file: File,
)

data class StoredBlobResult(
    val success: Boolean,
    val actualNhash: String?,
    val file: File?,
    val alreadyPresent: Boolean,
    val message: String,
)

class HashtreeDemoStore(context: Context) {
    private val baseDir = File(context.filesDir, "demo")
    private val localDir = File(baseDir, "local")
    private val receivedDir = File(baseDir, "received")
    private val tmpDir = File(baseDir, "tmp")

    init {
        ensureDirs()
    }

    fun seedLocalBlob(): DemoBlobInfo {
        clearDir(localDir)
        ensureDirs()

        val tempFile = File(tmpDir, "seed-local.bin")
        writeDeterministicBlob(tempFile)
        val nhash = computeNhashFromFile(tempFile.absolutePath)
        val target = File(localDir, "$nhash.bin")
        tempFile.copyTo(target, overwrite = true)
        tempFile.delete()

        return DemoBlobInfo(
            nhash = nhash,
            sizeBytes = target.length(),
            file = target,
        )
    }

    fun currentLocalBlob(): DemoBlobInfo? = firstBlobIn(localDir)

    fun lastReceivedBlob(): DemoBlobInfo? =
        receivedDir
            .listFiles()
            ?.filter { it.isFile && it.extension == "bin" }
            ?.maxByOrNull { it.lastModified() }
            ?.toBlobInfo()

    fun createIncomingTempFile(): File {
        ensureDirs()
        return File(tmpDir, "incoming-${System.currentTimeMillis()}.bin")
    }

    fun verifyAndStoreReceivedBlob(tempFile: File, announcedNhash: String): StoredBlobResult {
        val actualNhash = computeNhashFromFile(tempFile.absolutePath)
        if (actualNhash != announcedNhash) {
            tempFile.delete()
            return StoredBlobResult(
                success = false,
                actualNhash = actualNhash,
                file = null,
                alreadyPresent = false,
                message = "Rejected blob: announced $announcedNhash but computed $actualNhash",
            )
        }

        val target = File(receivedDir, "$actualNhash.bin")
        val alreadyPresent = target.exists()
        if (!alreadyPresent) {
            tempFile.copyTo(target, overwrite = false)
        }
        tempFile.delete()

        return StoredBlobResult(
            success = true,
            actualNhash = actualNhash,
            file = target,
            alreadyPresent = alreadyPresent,
            message = if (alreadyPresent) {
                "Verified Wi-Fi Aware blob $actualNhash; already stored."
            } else {
                "Verified Wi-Fi Aware blob $actualNhash and stored it."
            },
        )
    }

    fun clearAll() {
        if (baseDir.exists()) {
            baseDir.deleteRecursively()
        }
        ensureDirs()
    }

    fun totalStorageBytes(): Long =
        sequenceOf(localDir, receivedDir)
            .flatMap { dir -> dir.listFiles().orEmpty().asSequence() }
            .filter { it.isFile }
            .sumOf { it.length() }

    private fun ensureDirs() {
        localDir.mkdirs()
        receivedDir.mkdirs()
        tmpDir.mkdirs()
    }

    private fun clearDir(dir: File) {
        if (!dir.exists()) {
            return
        }
        dir.listFiles().orEmpty().forEach { child ->
            if (child.isDirectory) {
                child.deleteRecursively()
            } else {
                child.delete()
            }
        }
    }

    private fun firstBlobIn(dir: File): DemoBlobInfo? =
        dir.listFiles()
            ?.filter { it.isFile && it.extension == "bin" }
            ?.sortedBy { it.name }
            ?.firstOrNull()
            ?.toBlobInfo()

    private fun File.toBlobInfo(): DemoBlobInfo =
        DemoBlobInfo(
            nhash = nameWithoutExtension,
            sizeBytes = length(),
            file = this,
        )

    private fun writeDeterministicBlob(file: File) {
        val header = "nostr-wifi-aware|nearby-hashtree|wifi-aware-transfer\n".toByteArray()
        val totalSize = DEMO_BLOB_SIZE_BYTES.toInt()
        val buffer = ByteArray(8 * 1024)

        FileOutputStream(file).use { output ->
            var written = 0
            while (written < totalSize) {
                val chunkSize = min(buffer.size, totalSize - written)
                for (index in 0 until chunkSize) {
                    val absoluteIndex = written + index
                    buffer[index] = when {
                        absoluteIndex < header.size -> header[absoluteIndex]
                        else -> ((absoluteIndex * 31 + 17) and 0xff).toByte()
                    }
                }
                output.write(buffer, 0, chunkSize)
                written += chunkSize
            }
        }
    }

    companion object {
        const val DEMO_BLOB_SIZE_BYTES = 1_048_576L
    }
}
