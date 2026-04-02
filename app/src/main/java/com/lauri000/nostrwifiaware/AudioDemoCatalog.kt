package com.lauri000.nostrwifiaware

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class AudioSetId(val label: String) {
    SET_A("Set A"),
    SET_B("Set B"),
}

data class AudioParams(
    val baseFrequency: Double,
    val pulseFrequency: Double,
    val padFrequency: Double,
    val wobble: Double,
)

data class AudioTrackSpec(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val coverSeed: String,
    val accentHex: String,
    val secondaryAccentHex: String,
    val duration: Int,
    val bpm: Int,
    val audio: AudioParams,
) {
    fun renderDurationSeconds(): Int = min(24, max(14, (duration / 10.0).roundToInt()))
}

object AudioDemoCatalog {
    private val trackSpecs =
        listOf(
            AudioTrackSpec(
                id = "tidal-dawn",
                title = "Tidal Dawn",
                artist = "Open Meridian",
                album = "Coastline Cache",
                genre = "Ambient Electronica",
                coverSeed = "TD",
                accentHex = "#ff784f",
                secondaryAccentHex = "#ffd166",
                duration = 158,
                bpm = 108,
                audio = AudioParams(220.0, 440.0, 330.0, 2.0),
            ),
            AudioTrackSpec(
                id = "relay-runner",
                title = "Relay Runner",
                artist = "Mesh Theory",
                album = "Packet Bloom",
                genre = "Synth Pop",
                coverSeed = "RR",
                accentHex = "#38bdf8",
                secondaryAccentHex = "#f59e0b",
                duration = 184,
                bpm = 122,
                audio = AudioParams(246.94, 493.88, 329.63, 3.0),
            ),
            AudioTrackSpec(
                id = "zero-knowledge-kiss",
                title = "Zero-Knowledge Kiss",
                artist = "Cipher Season",
                album = "Private Summer",
                genre = "Electro R&B",
                coverSeed = "ZK",
                accentHex = "#ec4899",
                secondaryAccentHex = "#8b5cf6",
                duration = 188,
                bpm = 118,
                audio = AudioParams(164.81, 329.63, 246.94, 5.0),
            ),
            AudioTrackSpec(
                id = "sapphire-echo",
                title = "Sapphire Echo",
                artist = "Mirrored Lake",
                album = "Elsewhere Club",
                genre = "Progressive House",
                coverSeed = "SE",
                accentHex = "#06b6d4",
                secondaryAccentHex = "#38bdf8",
                duration = 220,
                bpm = 128,
                audio = AudioParams(220.0, 440.0, 349.23, 5.0),
            ),
        ).associateBy { it.id }

    private val setAIds = listOf("tidal-dawn", "relay-runner")
    private val setBIds = listOf("zero-knowledge-kiss", "sapphire-echo")

    fun tracksForSet(setId: AudioSetId): List<AudioTrackSpec> {
        val ids =
            when (setId) {
                AudioSetId.SET_A -> setAIds
                AudioSetId.SET_B -> setBIds
            }
        return ids.mapNotNull(trackSpecs::get)
    }

    fun specForId(id: String): AudioTrackSpec? = trackSpecs[id]
}
