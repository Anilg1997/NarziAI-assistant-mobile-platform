package com.narzoai.assistant

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

/**
 * ChatAdapter - RecyclerView adapter for chat message display.
 *
 * Displays user and AI messages with different styles:
 * - User messages: Right-aligned, primary color background
 * - AI messages: Left-aligned, surface color background
 * - Timestamps for each message
 */
class ChatAdapter(
    private val messages: MutableList<MainActivity.ChatMessage>
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_AI = 2
        private const val MAX_MESSAGE_LENGTH = 5000
    }

    private val dateFormatter = SimpleDateFormat("hh:mm a", Locale.getDefault())

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_AI
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layoutId = if (viewType == VIEW_TYPE_USER) {
            R.layout.chat_item_user
        } else {
            R.layout.chat_item_ai
        }

        val view = LayoutInflater.from(parent.context)
            .inflate(layoutId, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.bind(message)
    }

    override fun getItemCount(): Int = messages.size

    /**
     * ViewHolder for chat messages.
     */
    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.message_text)
        private val timestampText: TextView = itemView.findViewById(R.id.timestamp_text)

        fun bind(message: MainActivity.ChatMessage) {
            // Set message text with max length protection
            val displayText = if (message.text.length > MAX_MESSAGE_LENGTH) {
                message.text.take(MAX_MESSAGE_LENGTH) + "..."
            } else {
                message.text
            }
            messageText.text = displayText

            // Set timestamp
            timestampText.text = dateFormatter.format(Date(message.timestamp))

            // Handle long click for copying
            itemView.setOnLongClickListener {
                copyToClipboard(message.text)
                true
            }
        }

        /**
         * Copy message text to clipboard.
         */
        private fun copyToClipboard(text: String) {
            val clipboard = itemView.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("NarzoAI Message", text)
            clipboard.setPrimaryClip(clip)

            android.widget.Toast.makeText(
                itemView.context,
                "Message copied to clipboard",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}
