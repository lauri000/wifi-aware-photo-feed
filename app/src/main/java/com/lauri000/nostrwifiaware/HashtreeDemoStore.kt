package com.lauri000.nostrwifiaware

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import uniffi.nearby_hashtree_ffi.computeNhashFromFile

data class AudioTrackInfo(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val mimeType: String,
    val durationSeconds: Int,
    val nhash: String,
    val sizeBytes: Long,
    val setLabel: String,
    val file: File,
)

data class StoredTrackResult(
    val success: Boolean,
    val actualNhash: String?,
    val track: AudioTrackInfo?,
    val alreadyPresent: Boolean,
    val message: String,
)

class HashtreeDemoStore(context: Context) {
    private val baseDir = File(context.filesDir, "demo")
    private val localDir = File(baseDir, "local")
    private val receivedDir = File(baseDir, "received")
    private val tmpDir = File(baseDir, "tmp")
    private val localManifestFile = File(localDir, "manifest.json")
    private val receivedManifestFile = File(receivedDir, "manifest.json")

    init {
        ensureDirs()
    }

    fun seedLocalSet(setId: AudioSetId): List<AudioTrackInfo> {
        clearDir(localDir)
        ensureDirs()

        val tracks =
            AudioDemoCatalog.tracksForSet(setId).map { spec ->
                val tempFile = File(tmpDir, "${spec.id}.wav")
                writeDeterministicWav(tempFile, spec)
                val nhash = computeNhashFromFile(tempFile.absolutePath)
                val target = File(localDir, "$nhash.wav")
                tempFile.copyTo(target, overwrite = true)
                tempFile.delete()
                AudioTrackInfo(
                    id = spec.id,
                    title = spec.title,
                    artist = spec.artist,
                    album = spec.album,
                    mimeType = "audio/wav",
                    durationSeconds = spec.renderDurationSeconds(),
                    nhash = nhash,
                    sizeBytes = target.length(),
                    setLabel = setId.label,
                    file = target,
                )
            }

        writeManifest(localManifestFile, tracks)
        return tracks
    }

    fun currentLocalTracks(): List<AudioTrackInfo> = readManifest(localManifestFile, localDir)

    fun receivedTracks(): List<AudioTrackInfo> = readManifest(receivedManifestFile, receivedDir)

    fun createIncomingTempFile(trackId: String): File {
        ensureDirs()
        return File(tmpDir, "incoming-$trackId-${System.currentTimeMillis()}.wav")
    }

    fun verifyAndStoreReceivedTrack(
        tempFile: File,
        trackId: String,
        announcedNhash: String,
        setLabel: String,
    ): StoredTrackResult {
        val actualNhash = computeNhashFromFile(tempFile.absolutePath)
        if (actualNhash != announcedNhash) {
            tempFile.delete()
            return StoredTrackResult(
                success = false,
                actualNhash = actualNhash,
                track = null,
                alreadyPresent = false,
                message = "Rejected track $trackId: announced $announcedNhash but computed $actualNhash",
            )
        }

        val target = File(receivedDir, "$actualNhash.wav")
        val alreadyPresent = target.exists()
        if (!alreadyPresent) {
            tempFile.copyTo(target, overwrite = false)
        }
        tempFile.delete()

        val spec = AudioDemoCatalog.specForId(trackId)
        val trackInfo =
            AudioTrackInfo(
                id = trackId,
                title = spec?.title ?: trackId,
                artist = spec?.artist ?: "Unknown Artist",
                album = spec?.album ?: "Unknown Album",
                mimeType = "audio/wav",
                durationSeconds = spec?.renderDurationSeconds() ?: 0,
                nhash = actualNhash,
                sizeBytes = target.length(),
                setLabel = setLabel,
                file = target,
            )
        upsertManifest(receivedManifestFile, receivedDir, trackInfo)

        return StoredTrackResult(
            success = true,
            actualNhash = actualNhash,
            track = trackInfo,
            alreadyPresent = alreadyPresent,
            message = if (alreadyPresent) {
                "Verified Wi-Fi Aware track $trackId ($actualNhash); already stored."
            } else {
                "Verified Wi-Fi Aware track $trackId ($actualNhash) and stored it."
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
            .filter { it.isFile && it.extension == "wav" }
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

    private fun writeManifest(manifestFile: File, tracks: List<AudioTrackInfo>) {
        val json =
            JSONArray().apply {
                tracks.forEach { track ->
                    put(
                        JSONObject().apply {
                            put("id", track.id)
                            put("title", track.title)
                            put("artist", track.artist)
                            put("album", track.album)
                            put("mimeType", track.mimeType)
                            put("durationSeconds", track.durationSeconds)
                            put("nhash", track.nhash)
                            put("sizeBytes", track.sizeBytes)
                            put("setLabel", track.setLabel)
                            put("relativePath", track.file.name)
                        },
                    )
                }
            }
        manifestFile.writeText(json.toString(2))
    }

    private fun readManifest(manifestFile: File, parentDir: File): List<AudioTrackInfo> {
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
                        AudioTrackInfo(
                            id = entry.getString("id"),
                            title = entry.getString("title"),
                            artist = entry.getString("artist"),
                            album = entry.getString("album"),
                            mimeType = entry.getString("mimeType"),
                            durationSeconds = entry.getInt("durationSeconds"),
                            nhash = entry.getString("nhash"),
                            sizeBytes = entry.getLong("sizeBytes"),
                            setLabel = entry.getString("setLabel"),
                            file = file,
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun upsertManifest(manifestFile: File, parentDir: File, track: AudioTrackInfo) {
        val existing = readManifest(manifestFile, parentDir)
        val updated =
            (existing.filterNot { it.nhash == track.nhash } + track)
                .sortedBy { it.id }
        writeManifest(manifestFile, updated)
    }

    private fun writeDeterministicWav(file: File, spec: AudioTrackSpec) {
        val sampleRate = 24_000
        val seconds = spec.renderDurationSeconds()
        val totalSamples = sampleRate * seconds
        val pcmData = ByteArray(totalSamples * 2)
        val beat = 60.0 / spec.bpm

        var pcmOffset = 0
        for (index in 0 until totalSamples) {
            val time = index.toDouble() / sampleRate
            val beatPhase = (time % beat) / beat
            val pulseEnvelope = exp(-6.0 * beatPhase)
            val slowEnvelope = 0.55 + 0.45 * sin((2.0 * PI * time) / (seconds / 2.0))
            val wobble = sin((2.0 * PI * spec.audio.wobble * time) / seconds)

            val bass = sin(2.0 * PI * spec.audio.baseFrequency * time) * 0.26
            val pulse = sin(2.0 * PI * spec.audio.pulseFrequency * time) * pulseEnvelope * 0.18
            val pad =
                sin(2.0 * PI * (spec.audio.padFrequency + wobble * 3.0) * time) * 0.12 * slowEnvelope
            val shimmer = sin(2.0 * PI * (spec.audio.pulseFrequency / 2.0) * time * 1.01) * 0.05

            val sample = (bass + pulse + pad + shimmer).toFloat()
            val clamped = max(-1.0f, min(1.0f, sample))
            val pcm =
                if (clamped < 0f) {
                    (clamped * 0x8000).toInt()
                } else {
                    (clamped * 0x7fff).toInt()
                }
            writeLittleEndianShort(pcmData, pcmOffset, pcm)
            pcmOffset += 2
        }

        val header = ByteArray(44)
        writeAscii(header, 0, "RIFF")
        writeLittleEndianInt(header, 4, 36 + pcmData.size)
        writeAscii(header, 8, "WAVE")
        writeAscii(header, 12, "fmt ")
        writeLittleEndianInt(header, 16, 16)
        writeLittleEndianShort(header, 20, 1)
        writeLittleEndianShort(header, 22, 1)
        writeLittleEndianInt(header, 24, sampleRate)
        writeLittleEndianInt(header, 28, sampleRate * 2)
        writeLittleEndianShort(header, 32, 2)
        writeLittleEndianShort(header, 34, 16)
        writeAscii(header, 36, "data")
        writeLittleEndianInt(header, 40, pcmData.size)

        file.outputStream().use { output ->
            output.write(header)
            output.write(pcmData)
        }
    }

    private fun writeAscii(buffer: ByteArray, offset: Int, value: String) {
        value.forEachIndexed { index, char ->
            buffer[offset + index] = char.code.toByte()
        }
    }

    private fun writeLittleEndianInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xff).toByte()
        buffer[offset + 2] = ((value ushr 16) and 0xff).toByte()
        buffer[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun writeLittleEndianShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }
}
