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

    fun updateFiles(names: List<String>, uris: List<Uri>) {
        fileNames = names
        docUris = uris
        highlightIndex = -1
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.trackName)
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
        holder.nameText.text = fileNames[position]
        if (position == highlightIndex) {
            holder.nameText.setBackgroundColor(0x3300AA00)
        } else {
            holder.nameText.setBackgroundColor(0x00000000)
        }
    }

    override fun getItemCount() = fileNames.size
}