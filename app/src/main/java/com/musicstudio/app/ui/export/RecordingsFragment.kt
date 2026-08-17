package com.musicstudio.app.ui.export

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.musicstudio.app.databinding.FragmentRecordingsBinding
import com.musicstudio.app.databinding.ItemRecordingBinding
import com.musicstudio.app.viewmodel.StudioViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        viewModel.exportedFile.observe(viewLifecycleOwner) { if (it != null) loadRecordings() }
    }

    override fun onDestroyView() {
        stopPlayback()
        _binding = null
        super.onDestroyView()
    }

    private fun loadRecordings() {
        val dir   = File(requireContext().getExternalFilesDir(null), "recordings")
        val files = dir.listFiles { f -> f.extension == "wav" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        binding.emptyState.visibility         = if (files.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerRecordings.visibility = if (files.isEmpty()) View.GONE    else View.VISIBLE
        adapter.submitList(files)
    }

    private fun setupRecyclerView() {
        adapter = RecordingAdapter(
            onPlay   = ::togglePlayback,
            onShare  = ::shareRecording,
            onDelete = ::confirmDelete
        )
        binding.recyclerRecordings.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRecordings.adapter = adapter
    }

    private fun togglePlayback(file: File) {
        if (playingFile == file && mediaPlayer?.isPlaying == true) { stopPlayback(); return }
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
        mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null
        playingFile = null
        if (::adapter.isInitialized) adapter.setPlayingFile(null)
    }

    private fun shareRecording(file: File) {
        val uri = FileProvider.getUriForFile(
            requireContext(), "${requireContext().packageName}.fileprovider", file
        )
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share Recording"
        ))
    }

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
            }.show()
    }
}

// ── Adapter ────────────────────────────────────────────────────────────

class RecordingAdapter(
    private val onPlay:   (File) -> Unit,
    private val onShare:  (File) -> Unit,
    private val onDelete: (File) -> Unit
) : ListAdapter<File, RecordingAdapter.VH>(DIFF) {

    private var playingFile: File? = null

    fun setPlayingFile(file: File?) { playingFile = file; notifyDataSetChanged() }

    inner class VH(val binding: ItemRecordingBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: File) {
            binding.tvName.text = formatName(file.name)
            binding.tvMeta.text = "%.1f MB  ·  WAV".format(file.length() / 1_048_576.0)
            binding.btnPlay.setImageResource(
                if (file == playingFile) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            binding.btnPlay.setOnClickListener  { onPlay(file) }
            binding.btnShare.setOnClickListener { onShare(file) }
            binding.btnDelete.setOnClickListener{ onDelete(file) }
        }

        private fun formatName(raw: String): String {
            val ts = raw.removePrefix("recording_").removeSuffix(".wav").toLongOrNull()
            return if (ts != null)
                "Recording · ${SimpleDateFormat("d MMM yyyy  HH:mm", Locale.getDefault()).format(Date(ts))}"
            else raw.removeSuffix(".wav")
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<File>() {
            override fun areItemsTheSame(a: File, b: File) = a.path == b.path
            override fun areContentsTheSame(a: File, b: File) =
                a.path == b.path && a.lastModified() == b.lastModified()
        }
    }
}
