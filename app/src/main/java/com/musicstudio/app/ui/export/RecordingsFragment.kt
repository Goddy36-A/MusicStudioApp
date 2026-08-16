package com.musicstudio.app.ui.export

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.*
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.musicstudio.app.databinding.FragmentRecordingsBinding
import com.musicstudio.app.databinding.ItemRecordingBinding
import com.musicstudio.app.viewmodel.StudioViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingsFragment : Fragment() {

    private var _binding: FragmentRecordingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StudioViewModel by activityViewModels()

    private lateinit var adapter: RecordingAdapter
    private var mediaPlayer: MediaPlayer? = null
    private var playingFile: File? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        loadRecordings()

        // Refresh if a new recording just finished
        viewModel.exportedFile.observe(viewLifecycleOwner) {
            if (it != null) loadRecordings()
        }
    }

    override fun onDestroyView() {
        stopPlayback()
        _binding = null
        super.onDestroyView()
    }

    // ── Load from disk ─────────────────────────────────────────────────

    private fun loadRecordings() {
        val dir   = File(requireContext().getExternalFilesDir(null), "recordings")
        val files = dir.listFiles { f -> f.extension == "wav" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

        binding.emptyState.visibility      = if (files.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerRecordings.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitList(files)
    }

    // ── RecyclerView ───────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = RecordingAdapter(
            onPlay   = { file -> togglePlayback(file) },
            onShare  = { file -> shareRecording(file) },
            onDelete = { file -> confirmDelete(file) }
        )
        binding.recyclerRecordings.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRecordings.adapter = adapter
    }

    // ── Playback ───────────────────────────────────────────────────────

    private fun togglePlayback(file: File) {
        if (playingFile == file && mediaPlayer?.isPlaying == true) {
            stopPlayback()
            return
        }
        stopPlayback()
        playingFile = file
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.path)
            prepare()
            start()
            setOnCompletionListener { stopPlayback() }
        }
        adapter.setPlayingFile(file)
    }

    private fun stopPlayback() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer  = null
        playingFile  = null
        adapter.setPlayingFile(null)
    }

    // ── Share ──────────────────────────────────────────────────────────

    private fun shareRecording(file: File) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type     = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Recording"))
    }

    // ── Delete ─────────────────────────────────────────────────────────

    private fun confirmDelete(file: File) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Recording?")
            .setMessage("\"${file.nameWithoutExtension}\" will be permanently deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (file == playingFile) stopPlayback()
                file.delete()
                loadRecordings()
                Snackbar.make(binding.root, "Deleted", Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }
}

// ── Adapter ────────────────────────────────────────────────────────────

class RecordingAdapter(
    private val onPlay:   (File) -> Unit,
    private val onShare:  (File) -> Unit,
    private val onDelete: (File) -> Unit
) : ListAdapter<File, RecordingAdapter.VH>(DIFF) {

    private var playingFile: File? = null

    fun setPlayingFile(file: File?) {
        playingFile = file
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemRecordingBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: File) {
            binding.tvName.text = formatName(file.name)
            binding.tvMeta.text = buildMeta(file)
            binding.btnPlay.setImageResource(
                if (file == playingFile) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            binding.btnPlay.setOnClickListener  { onPlay(file) }
            binding.btnShare.setOnClickListener { onShare(file) }
            binding.btnDelete.setOnClickListener{ onDelete(file) }
        }

        private fun formatName(raw: String): String {
            // e.g. "recording_1714321234567.wav" → "Recording · 28 Apr 2024 14:20"
            val ts   = raw.removePrefix("recording_").removeSuffix(".wav").toLongOrNull()
            return if (ts != null) {
                val sdf = SimpleDateFormat("d MMM yyyy  HH:mm", Locale.getDefault())
                "Recording · ${sdf.format(Date(ts))}"
            } else raw.removeSuffix(".wav")
        }

        private fun buildMeta(file: File): String {
            val sizeMb = file.length() / 1_048_576.0
            return "%.1f MB  ·  WAV".format(sizeMb)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<File>() {
            override fun areItemsTheSame(a: File, b: File) = a.path == b.path
            override fun areContentsTheSame(a: File, b: File) =
                a.path == b.path && a.lastModified() == b.lastModified()
        }
    }
}
