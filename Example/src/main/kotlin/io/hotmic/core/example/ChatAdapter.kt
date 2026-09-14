package io.hotmic.core.example

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.hotmic.core.example.databinding.ItemChatMessageBinding
import io.hotmic.core.models.HotMicChatMessage

/** Chat transcript: the session snapshot's messages plus each `ChatBatchReceived` batch. */
internal class ChatAdapter : RecyclerView.Adapter<ChatAdapter.Holder>() {
    private val items = mutableListOf<HotMicChatMessage>()

    fun replaceAll(messages: List<HotMicChatMessage>) {
        items.clear()
        items += messages
        notifyDataSetChanged()
    }

    /** Appends new messages, skipping ids already shown. Returns how many were added. */
    fun append(messages: List<HotMicChatMessage>): Int {
        val known = items.mapTo(HashSet()) { it.id }
        val fresh = messages.filter { known.add(it.id) }
        if (fresh.isEmpty()) return 0
        val start = items.size
        items += fresh
        notifyItemRangeInserted(start, fresh.size)
        return fresh.size
    }

    fun remove(chatId: String) {
        val index = items.indexOfFirst { it.id == chatId }
        if (index < 0) return
        items.removeAt(index)
        notifyItemRemoved(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class Holder(
        private val binding: ItemChatMessageBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: HotMicChatMessage) {
            val tags = buildList {
                if (message.broadcaster) add("host")
                if (message.admin) add("admin")
                if (message.verified) add("verified")
            }
            binding.author.text = if (tags.isEmpty()) {
                message.userName
            } else {
                "${message.userName} · ${tags.joinToString(" · ")}"
            }
            binding.message.text = message.message
        }
    }
}
