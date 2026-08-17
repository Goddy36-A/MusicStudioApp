package com.musicstudio.app.ui.library

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musicstudio.app.data.Track
import com.musicstudio.app.databinding.FragmentLibraryBinding
import com.musicstudio.app.databinding.ItemTrackBinding
import com.musicstudio.app.viewmodel.StudioViewModel

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StudioViewModel by activityViewModels()
    private lateinit var adapter: TrackAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSearch()
        observeViewModel()
        viewModel.loadTracks()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        adapter = TrackAdapter { track ->
            viewModel.selectTrack(track)
            findNavController().navigateUp()
        }
        binding.recyclerTracks.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerTracks.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.lowercase() ?: ""
                val all = viewModel.tracks.value ?: emptyList()
                adapter.submitList(
                    if (q.isEmpty()) all
                    else all.filter {
                        it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
                    }
                )
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun observeViewModel() {
        viewModel.tracks.observe(viewLifecycleOwner) { tracks ->
            binding.emptyState.visibility     = if (tracks.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerTracks.visibility = if (tracks.isEmpty()) View.GONE    else View.VISIBLE
            adapter.submitList(tracks)
        }
    }
}

// ── Track RecyclerView Adapter ─────────────────────────────────────────

class TrackAdapter(
    private val onSelect: (Track) -> Unit
) : ListAdapter<Track, TrackAdapter.TrackViewHolder>(DIFF) {

    inner class TrackViewHolder(val binding: ItemTrackBinding)
        : RecyclerView.ViewHolder(binding.root) {

        fun bind(track: Track) {
            binding.tvTitle.text    = track.title
            binding.tvArtist.text   = track.artist
            binding.tvDuration.text = track.durationFormatted
            binding.root.setOnClickListener { onSelect(track) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        TrackViewHolder(
            ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Track>() {
            override fun areItemsTheSame(a: Track, b: Track) = a.id == b.id
            override fun areContentsTheSame(a: Track, b: Track) = a == b
        }
    }
}
