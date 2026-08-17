package com.musicstudio.app.viewmodel

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.*
import com.musicstudio.app.audio.AudioEngine
import com.musicstudio.app.data.ReverbPreset
import com.musicstudio.app.data.Scale
import com.musicstudio.app.data.SessionSettings
import com.musicstudio.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    val engine = AudioEngine(application)

    // ── Session settings ───────────────────────────────────────────────
    private val _settings = MutableLiveData(SessionSettings())
    val settings: LiveData<SessionSettings> = _settings

    // ── Selected track ─────────────────────────────────────────────────
    private val _selectedTrack = MutableLiveData<Track?>()
    val selectedTrack: LiveData<Track?> = _selectedTrack

    // ── Engine state ───────────────────────────────────────────────────
    private val _engineState = MutableLiveData(AudioEngine.State.IDLE)
    val engineState: LiveData<AudioEngine.State> = _engineState

    // ── Amplitude (for waveform) ───────────────────────────────────────
    private val _amplitude = MutableLiveData(0f)
    val amplitude: LiveData<Float> = _amplitude

    // ── Last exported file ─────────────────────────────────────────────
    private val _exportedFile = MutableLiveData<File?>()
    val exportedFile: LiveData<File?> = _exportedFile

    // ── Library ────────────────────────────────────────────────────────
    private val _tracks = MutableLiveData<List<Track>>(emptyList())
    val tracks: LiveData<List<Track>> = _tracks

    // ── Lyrics ─────────────────────────────────────────────────────────
    private val _lyrics = MutableLiveData<String>("")
    val lyrics: LiveData<String> = _lyrics

    fun setLyrics(text: String) {
        _lyrics.value = text
    }

    init {
        engine.onAmplitudeUpdate = { rms -> _amplitude.postValue(rms) }
    }

    // ── Library loading ────────────────────────────────────────────────

    fun loadTracks() = viewModelScope.launch(Dispatchers.IO) {
        _tracks.postValue(queryAudioFiles(getApplication()))
    }

    private fun queryAudioFiles(context: Context): List<Track> {
        val list = mutableListOf<Track>()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection  = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(collection, projection, selection, null, sortOrder)
            ?.use { cursor ->
                val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val id  = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    list += Track(
                        id          = id,
                        uri         = uri,
                        title       = cursor.getString(titleCol) ?: "Unknown",
                        artist      = cursor.getString(artistCol) ?: "Unknown",
                        album       = cursor.getString(albumCol) ?: "",
                        durationMs  = cursor.getLong(durationCol),
                        filePath    = cursor.getString(dataCol) ?: ""
                    )
                }
            }
        return list
    }

    // ── Track selection ────────────────────────────────────────────────

    fun selectTrack(track: Track) { _selectedTrack.value = track }

    // ── Session control ────────────────────────────────────────────────

    fun startMonitoring() {
        engine.applySettings(_settings.value ?: SessionSettings())
        engine.startMonitoring()
        _engineState.postValue(engine.state)
    }

    fun startRecording() {
        val track = _selectedTrack.value ?: return
        val ctx   = getApplication<Application>()
        val dir   = File(ctx.getExternalFilesDir(null), "recordings").also { it.mkdirs() }
        val file  = File(dir, "recording_${System.currentTimeMillis()}.wav")

        engine.applySettings(_settings.value ?: SessionSettings())
        engine.startRecordingSession(track.uri, file)
        _engineState.postValue(engine.state)
        _exportedFile.postValue(null)
    }

    fun stopSession() {
        val wasRecording = engine.state == AudioEngine.State.RECORDING
        engine.stop()
        _engineState.postValue(engine.state)
        if (wasRecording) {
            val dir  = File(getApplication<Application>().getExternalFilesDir(null), "recordings")
            val last = dir.listFiles()?.maxByOrNull { it.lastModified() }
            _exportedFile.postValue(last)
        }
    }

    // ── Settings updaters (called from SeekBar / Switch callbacks) ─────

    fun setPitch(semitones: Float) {
        _settings.value = _settings.value?.copy(pitchSemitones = semitones)
        engine.sonicProcessor.setPitchSemitones(semitones)
    }

    fun setTempo(multiplier: Float) {
        _settings.value = _settings.value?.copy(tempoMultiplier = multiplier)
        engine.sonicProcessor.speed = multiplier
    }

    fun setVocalVolume(v: Float) {
        _settings.value = _settings.value?.copy(vocalVolume = v)
        engine.vocalVolume = v
    }

    fun setTrackVolume(v: Float) {
        _settings.value = _settings.value?.copy(trackVolume = v)
        engine.trackVolume = v
    }

    fun setAutoTune(enabled: Boolean) {
        _settings.value = _settings.value?.copy(autoTuneEnabled = enabled)
        engine.autoTuneEngine.enabled = enabled
    }

    fun setAutoTuneStrength(v: Float) {
        _settings.value = _settings.value?.copy(autoTuneStrength = v)
        engine.autoTuneEngine.strength = v
    }

    fun setAutoTuneScale(scale: Scale) {
        _settings.value = _settings.value?.copy(autoTuneScale = scale)
        engine.autoTuneEngine.scale = scale
    }

    fun setReverb(preset: ReverbPreset) {
        _settings.value = _settings.value?.copy(reverbPreset = preset)
        engine.effectsChain.reverbPreset = preset
    }

    fun setEcho(delayMs: Int, decay: Float) {
        _settings.value = _settings.value?.copy(echoDelayMs = delayMs, echoDecay = decay)
        engine.effectsChain.echoDelayMs = delayMs
        engine.effectsChain.echoDecay   = decay
    }

    fun setEq(bass: Float, mid: Float, treble: Float) {
        _settings.value = _settings.value?.copy(eqBass = bass, eqMid = mid, eqTreble = treble)
        engine.effectsChain.applyEqSettings(bass, mid, treble)
    }

    override fun onCleared() {
        engine.release()
        super.onCleared()
    }
}
