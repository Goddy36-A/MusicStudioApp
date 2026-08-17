package com.musicstudio.app.ui.studio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.musicstudio.app.MainActivity
import com.musicstudio.app.R
import com.musicstudio.app.audio.AudioEngine
import com.musicstudio.app.data.ReverbPreset
import com.musicstudio.app.data.Scale
import com.musicstudio.app.databinding.FragmentStudioBinding
import com.musicstudio.app.viewmodel.StudioViewModel

class StudioFragment : Fragment() {

    private var _binding: FragmentStudioBinding? = null
    private val binding get() = _binding!!

    // Sub-bindings for included sections
    private val volumes    get() = binding.sectionVolumes
    private val pitchTempo get() = binding.sectionPitchTempo
    private val autotune   get() = binding.sectionAutotune
    private val reverbEcho get() = binding.sectionReverbEcho
    private val eq         get() = binding.sectionEq

    private val viewModel: StudioViewModel by activityViewModels()

    // ── Lifecycle ──────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStudioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSpinners()
        setupSliders()
        setupSwitches()
        setupButtons()
        observeViewModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Spinners ───────────────────────────────────────────────────────

    private fun setupSpinners() {
        // Reverb preset
        val reverbLabels = ReverbPreset.entries.map { it.label }
        reverbEcho.spinnerReverb.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, reverbLabels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        reverbEcho.spinnerReverb.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    viewModel.setReverb(ReverbPreset.entries[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

        // AutoTune scale
        val scaleLabels = Scale.entries.map { it.label }
        autotune.spinnerScale.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, scaleLabels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        autotune.spinnerScale.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    viewModel.setAutoTuneScale(Scale.entries[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
    }

    // ── Sliders ────────────────────────────────────────────────────────

    private fun setupSliders() {
        // Pitch: slider 0–240, centre=120 → -12 to +12 semitones
        pitchTempo.sliderPitch.addOnChangeListener { _, value, _ ->
            val semitones = (value - 120f) / 10f
            pitchTempo.tvPitchValue.text = formatSemitones(semitones)
            viewModel.setPitch(semitones)
        }

        // Tempo: 50–200 (%)
        pitchTempo.sliderTempo.addOnChangeListener { _, value, _ ->
            pitchTempo.tvTempoValue.text = "%.0f%%".format(value)
            viewModel.setTempo(value / 100f)
        }

        // Vocal / track volumes
        volumes.sliderVocalVol.addOnChangeListener { _, value, _ ->
            viewModel.setVocalVolume(value / 100f)
        }
        volumes.sliderTrackVol.addOnChangeListener { _, value, _ ->
            viewModel.setTrackVolume(value / 100f)
        }

        // AutoTune strength
        autotune.sliderAutoTuneStrength.addOnChangeListener { _, value, _ ->
            viewModel.setAutoTuneStrength(value / 100f)
        }

        // Echo delay + decay
        reverbEcho.sliderEchoDelay.addOnChangeListener { _, value, _ ->
            val decay = reverbEcho.sliderEchoDecay.value / 100f
            reverbEcho.tvEchoValue.text = if (value < 1f) "Off" else "%.0fms".format(value)
            viewModel.setEcho(value.toInt(), decay)
        }
        reverbEcho.sliderEchoDecay.addOnChangeListener { _, value, _ ->
            viewModel.setEcho(reverbEcho.sliderEchoDelay.value.toInt(), value / 100f)
        }

        // EQ
        eq.sliderEqBass.addOnChangeListener   { _, _, _ -> emitEq() }
        eq.sliderEqMid.addOnChangeListener    { _, _, _ -> emitEq() }
        eq.sliderEqTreble.addOnChangeListener { _, _, _ -> emitEq() }
    }

    private fun emitEq() {
        viewModel.setEq(
            bass   = eq.sliderEqBass.value,
            mid    = eq.sliderEqMid.value,
            treble = eq.sliderEqTreble.value
        )
    }

    // ── Switches ───────────────────────────────────────────────────────

    private fun setupSwitches() {
        autotune.switchAutoTune.setOnCheckedChangeListener { _, checked ->
            viewModel.setAutoTune(checked)
            autotune.groupAutoTuneOptions.visibility =
                if (checked) View.VISIBLE else View.GONE
        }
    }

    // ── Buttons ────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnPickTrack.setOnClickListener {
            findNavController().navigate(R.id.action_studio_to_library)
        }

        binding.btnRecord.setOnClickListener {
            if (!(requireActivity() as MainActivity).allPermissionsGranted()) {
                Snackbar.make(binding.root, "Permissions required", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            when (viewModel.engineState.value) {
                AudioEngine.State.IDLE       -> startOrMonitor()
                AudioEngine.State.MONITORING -> { viewModel.stopSession(); startOrMonitor() }
                AudioEngine.State.RECORDING  -> viewModel.stopSession()
                else                         -> Unit
            }
        }

        binding.btnMonitor.setOnClickListener {
            when (viewModel.engineState.value) {
                AudioEngine.State.IDLE -> viewModel.startMonitoring()
                else                   -> viewModel.stopSession()
            }
        }
    }

    private fun startOrMonitor() {
        if (viewModel.selectedTrack.value != null) viewModel.startRecording()
        else viewModel.startMonitoring()
    }

    // ── Observers ──────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.selectedTrack.observe(viewLifecycleOwner) { track ->
            binding.tvSelectedTrack.text = track
                ?.let { "${it.title} — ${it.artist}" }
                ?: "No track selected — tap to browse"
        }

        viewModel.engineState.observe(viewLifecycleOwner) { state ->
            val isRecording = state == AudioEngine.State.RECORDING
            val isMonitor   = state == AudioEngine.State.MONITORING

            binding.btnRecord.text = when {
                isRecording -> "⏹ Stop Recording"
                isMonitor   -> "⏺ Start Recording"
                else        -> "⏺ Record"
            }
            binding.btnMonitor.text =
                if (isMonitor || isRecording) "🔇 Stop Monitor" else "🎧 Monitor"

            binding.recordingIndicator.visibility =
                if (isRecording) View.VISIBLE else View.INVISIBLE
        }

        viewModel.amplitude.observe(viewLifecycleOwner) { rms ->
            binding.waveformView.addAmplitude(rms)
            binding.vuMeter.setLevel(rms)
        }

        viewModel.exportedFile.observe(viewLifecycleOwner) { file ->
            if (file != null) {
                Snackbar.make(binding.root, "Saved: ${file.name}", Snackbar.LENGTH_LONG)
                    .setAction("Recordings") {
                        findNavController().navigate(R.id.action_studio_to_export)
                    }.show()
            }
        }
    }

    // ── Formatters ─────────────────────────────────────────────────────

    private fun formatSemitones(st: Float): String {
        val i = st.toInt()
        return when {
            i == 0 -> "0 st"
            i > 0  -> "+$i st"
            else   -> "$i st"
        }
    }
}
