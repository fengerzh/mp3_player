package com.buzz.mp3player

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TrackAdapter(private val onClick: (Int) -> Unit) : RecyclerView.Adapter<TrackAdapter.ViewHolder>() {

    var fileNames: List<String> = emptyList()
    var docUris: List<Uri> = emptyList()
    var highlightIndex: Int = -1
    var durations: List<String> = emptyList()

    fun updateFiles(names: List<String>, uris: List<Uri>, durs: List<String> = emptyList()) {
        fileNames = names
        docUris = uris
        durations = durs
        highlightIndex = -1
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val indexText: TextView = view.findViewById(R.id.trackIndex)
        val nameText: TextView = view.findViewById(R.id.trackName)
        val durationText: TextView = view.findViewById(R.id.trackDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        val holder = ViewHolder(view)
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onClick(pos)
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.indexText.text = "${position + 1}"
        holder.nameText.text = fileNames[position]
        if (position < durations.size) {
            holder.durationText.text = durations[position]
            holder.durationText.visibility = View.VISIBLE
        } else {
            holder.durationText.visibility = View.GONE
        }
        if (position == highlightIndex) {
            holder.itemView.setBackgroundResource(R.drawable.bg_track_highlight)
            holder.nameText.setTextColor(0xFF2A6DF7.toInt())
            holder.indexText.setTextColor(0xFF2A6DF7.toInt())
        } else {
            holder.itemView.setBackgroundResource(R.drawable.bg_track_normal)
            holder.nameText.setTextColor(0xFF1A1A2E.toInt())
            holder.indexText.setTextColor(0xFF8888A0.toInt())
        }
    }

    override fun getItemCount() = fileNames.size
}
