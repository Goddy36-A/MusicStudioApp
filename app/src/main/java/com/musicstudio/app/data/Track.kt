package com.musicstudio.app.data

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val filePath: String
) : Parcelable {

    val durationFormatted: String
        get() {
            val totalSec = durationMs / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return "%d:%02d".format(min, sec)
        }
}

/** Holds all the studio session settings the user has dialled in. */
data class SessionSettings(
    val vocalVolume: Float        = 1.0f,   // 0.0 – 1.0
    val trackVolume: Float        = 0.8f,   // 0.0 – 1.0
    val pitchSemitones: Float     = 0f,     // -12 to +12
    val tempoMultiplier: Float    = 1.0f,   // 0.5 to 2.0
    val autoTuneEnabled: Boolean  = false,
    val autoTuneStrength: Float   = 0.5f,   // 0.0 – 1.0
    val autoTuneScale: Scale      = Scale.CHROMATIC,
    val reverbPreset: ReverbPreset = ReverbPreset.NONE,
    val echoDelayMs: Int          = 0,      // 0 = off
    val echoDecay: Float          = 0.4f,   // 0.0 – 1.0
    val eqBass: Float             = 0f,     // dB: -12 to +12
    val eqMid: Float              = 0f,
    val eqTreble: Float           = 0f
)

enum class Scale(val label: String, val semitones: IntArray) {
    CHROMATIC("Chromatic",  intArrayOf(0,1,2,3,4,5,6,7,8,9,10,11)),
    MAJOR("Major",          intArrayOf(0,2,4,5,7,9,11)),
    MINOR("Minor",          intArrayOf(0,2,3,5,7,8,10)),
    PENTATONIC("Pentatonic",intArrayOf(0,2,4,7,9)),
    BLUES("Blues",          intArrayOf(0,3,5,6,7,10))
}

enum class ReverbPreset(val label: String) {
    NONE("None"),
    SMALL_ROOM("Small Room"),
    LARGE_ROOM("Large Room"),
    HALL("Concert Hall"),
    CATHEDRAL("Cathedral"),
    BATHROOM("Bathroom"),
    SPRING("Spring")
}
