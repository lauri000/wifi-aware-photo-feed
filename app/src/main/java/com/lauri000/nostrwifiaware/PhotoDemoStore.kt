package com.lauri000.nostrwifiaware

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import uniffi.nearby_hashtree_ffi.computeNhashFromFile

data class PhotoItem(
    val id: String,
    val nhash: String,
    val sizeBytes: Long,
    val createdAtMs: Long,
    val sourceLabel: String,
    val mimeType: String,
    val file: File,
)

data class StoredPhotoResult(
    val success: Boolean,
    val actualNhash: String?,
    val photo: PhotoItem?,
    val alreadyPresent: Boolean,
    val message: String,
)

class PhotoDemoStore(context: Context) {
    private val baseDir = File(context.filesDir, "demo")
    private val localDir = File(baseDir, "local")
    private val receivedDir = File(baseDir, "received")
    private val tmpDir = File(baseDir, "tmp")
    private val captureDir = File(tmpDir, "capture")
    private val localManifestFile = File(localDir, "manifest.json")
    private val receivedManifestFile = File(receivedDir, "manifest.json")

    init {
        ensureDirs()
    }

    fun createCaptureTempFile(): File {
        ensureDirs()
        return File(captureDir, "capture-${System.currentTimeMillis()}.jpg")
    }

    fun finalizeCapturedPhoto(tempFile: File): PhotoItem {
        ensureDirs()
        val nhash = computeNhashFromFile(tempFile.absolutePath)
        val createdAtMs = System.currentTimeMillis()
        val target = File(localDir, "$nhash.jpg")
        tempFile.copyTo(target, overwrite = true)
        tempFile.delete()

        val photo =
            PhotoItem(
                id = "photo-$createdAtMs",
                nhash = nhash,
                sizeBytes = target.length(),
                createdAtMs = createdAtMs,
                sourceLabel = "Taken Here",
                mimeType = "image/jpeg",
                file = target,
            )
        upsertManifest(localManifestFile, localDir, photo)
        return photo
    }

    fun currentLocalPhotos(): List<PhotoItem> = readManifest(localManifestFile, localDir)

    fun receivedPhotos(): List<PhotoItem> = readManifest(receivedManifestFile, receivedDir)

    fun createIncomingTempFile(photoId: String): File {
        ensureDirs()
        return File(tmpDir, "incoming-$photoId-${System.currentTimeMillis()}.jpg")
    }

    fun verifyAndStoreReceivedPhoto(
        tempFile: File,
        photoId: String,
        createdAtMs: Long,
        announcedNhash: String,
        sourceLabel: String,
    ): StoredPhotoResult {
        val actualNhash = computeNhashFromFile(tempFile.absolutePath)
        if (actualNhash != announcedNhash) {
            tempFile.delete()
            return StoredPhotoResult(
                success = false,
                actualNhash = actualNhash,
                photo = null,
                alreadyPresent = false,
                message = "Rejected photo $photoId: announced $announcedNhash but computed $actualNhash",
            )
        }

        val target = File(receivedDir, "$actualNhash.jpg")
        val alreadyPresent = target.exists()
        if (!alreadyPresent) {
            tempFile.copyTo(target, overwrite = false)
        }
        tempFile.delete()

        val photo =
            PhotoItem(
                id = photoId,
                nhash = actualNhash,
                sizeBytes = target.length(),
                createdAtMs = createdAtMs,
                sourceLabel = sourceLabel,
                mimeType = "image/jpeg",
                file = target,
            )
        upsertManifest(receivedManifestFile, receivedDir, photo)

        return StoredPhotoResult(
            success = true,
            actualNhash = actualNhash,
            photo = photo,
            alreadyPresent = alreadyPresent,
            message = if (alreadyPresent) {
                "Verified Wi-Fi Aware photo $photoId ($actualNhash); already stored."
            } else {
                "Verified Wi-Fi Aware photo $photoId ($actualNhash) and stored it."
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
        captureDir.mkdirs()
    }

    private fun writeManifest(manifestFile: File, photos: List<PhotoItem>) {
        val json =
            JSONArray().apply {
                photos.forEach { photo ->
                    put(
                        JSONObject().apply {
                            put("id", photo.id)
                            put("nhash", photo.nhash)
                            put("sizeBytes", photo.sizeBytes)
                            put("createdAtMs", photo.createdAtMs)
                            put("sourceLabel", photo.sourceLabel)
                            put("mimeType", photo.mimeType)
                            put("relativePath", photo.file.name)
                        },
                    )
                }
            }
        manifestFile.writeText(json.toString(2))
    }

    private fun readManifest(manifestFile: File, parentDir: File): List<PhotoItem> {
        if (!manifestFile.exists()) {
            return emptyList()
        }

        return try {
            val parsed = JSONArray(manifestFile.readText())
            buildList {
                for (index in 0 until parsed.length()) {
                    val entry = parsed.getJSONObject(index)
                    val relativePath = entry.getString("relativePath")
                    val file = File(parentDir, relativePath)
                    if (!file.exists()) {
                        continue
                    }
                    add(
                        PhotoItem(
                            id = entry.getString("id"),
                            nhash = entry.getString("nhash"),
                            sizeBytes = entry.getLong("sizeBytes"),
                            createdAtMs = entry.getLong("createdAtMs"),
                            sourceLabel = entry.getString("sourceLabel"),
                            mimeType = entry.getString("mimeType"),
                            file = file,
                        ),
                    )
                }
            }.sortedByDescending { it.createdAtMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun upsertManifest(
        manifestFile: File,
        parentDir: File,
        photo: PhotoItem,
    ) {
        val existing = readManifest(manifestFile, parentDir)
        val updated =
            (existing.filterNot { it.nhash == photo.nhash } + photo)
                .sortedByDescending { it.createdAtMs }
        writeManifest(manifestFile, updated)
    }
}
