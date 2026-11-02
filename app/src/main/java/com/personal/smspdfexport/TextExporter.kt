package com.personal.smspdfexport

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-text export: same header, ordering, sender, and timestamp info as
 * PdfExporter, just without any layout/pagination — a .txt file anyone can
 * open, diff, or search without a PDF viewer.
 */
object TextExporter {

    fun export(
        outputStream: OutputStream,
        conversationLabel: String,
        rangeDescription: String,
        messages: List<SmsMessage>,
        selfLabel: String,
        resolveName: (String) -> String
    ) {
        val timeFormatter = SimpleDateFormat("EEE, MMM d, yyyy h:mm a", Locale.getDefault())
        val exportedAtFormatter = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())

        val sb = StringBuilder()
        sb.append("Conversation with ").append(conversationLabel).append('\n')
        sb.append(rangeDescription)
            .append("  ·  ").append(messages.size).append(" messages")
            .append("  ·  exported ").append(exportedAtFormatter.format(Date()))
            .append('\n')
        sb.append("=".repeat(60)).append("\n\n")

        for (message in messages) {
            val senderName = if (message.isSent) selfLabel else resolveName(message.address).ifBlank { conversationLabel }
            val body = message.body.ifBlank { "[${message.typeLabel.lowercase()} message with no text]" }

            sb.append('[').append(timeFormatter.format(Date(message.date))).append("] ")
                .append(senderName).append(":\n")
            sb.append(body).append("\n\n")
        }

        outputStream.write(sb.toString().toByteArray(Charsets.UTF_8))
    }
}
