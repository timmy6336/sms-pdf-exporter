package com.personal.smspdfexport

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import kotlin.math.abs

class ConversationListAdapter(
    private val onClick: (Conversation) -> Unit
) : RecyclerView.Adapter<ConversationListAdapter.ViewHolder>() {

    private var items: List<Conversation> = emptyList()
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    fun submitList(newItems: List<Conversation>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conversation = items[position]
        holder.contactName.text = conversation.displayName
        holder.snippet.text = conversation.snippet
        holder.meta.text = holder.itemView.context.getString(
            R.string.messages_count,
            conversation.messageCount
        ) + " · " + dateFormat.format(conversation.lastDate)
        holder.itemView.setOnClickListener { onClick(conversation) }

        val initials = conversation.displayName
            .trim()
            .split(" ")
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "?" }
        holder.avatar.text = initials
        val color = DailyMessageBarChartView.PALETTE[
            abs(conversation.displayName.hashCode()) % DailyMessageBarChartView.PALETTE.size
        ]
        (holder.avatar.background.mutate() as GradientDrawable).setColor(color)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: TextView = view.findViewById(R.id.avatar)
        val contactName: TextView = view.findViewById(R.id.contactName)
        val snippet: TextView = view.findViewById(R.id.snippet)
        val meta: TextView = view.findViewById(R.id.meta)
    }
}
