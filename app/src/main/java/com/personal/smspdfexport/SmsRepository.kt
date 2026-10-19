package com.personal.smspdfexport

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony

/**
 * All reads go through the system SMS content provider — nothing is ever
 * written back, and nothing leaves the device.
 */
object SmsRepository {

    private class ConversationAccumulator(
        var address: String,
        val lastDate: Long,
        val snippet: String,
        var count: Int
    )

    fun getConversations(context: Context): List<Conversation> {
        val projection = arrayOf(
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.BODY
        )
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        ) ?: return emptyList()

        // LinkedHashMap preserves insertion order, which — since the cursor is
        // sorted newest-first — means "most recently active thread first".
        val byThread = LinkedHashMap<Long, ConversationAccumulator>()

        cursor.use { c ->
            val threadIdIdx = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)

            while (c.moveToNext()) {
                val threadId = c.getLong(threadIdIdx)
                val address = c.getString(addressIdx) ?: ""
                val date = c.getLong(dateIdx)
                val body = c.getString(bodyIdx) ?: ""

                val existing = byThread[threadId]
                if (existing == null) {
                    byThread[threadId] = ConversationAccumulator(address, date, body, 1)
                } else {
                    existing.count += 1
                    if (existing.address.isBlank() && address.isNotBlank()) {
                        existing.address = address
                    }
                }
            }
        }

        return byThread.map { (threadId, acc) ->
            Conversation(
                threadId = threadId,
                address = acc.address,
                displayName = resolveDisplayName(context, acc.address),
                messageCount = acc.count,
                lastDate = acc.lastDate,
                snippet = acc.snippet
            )
        }
    }

    /** fromMillis/toMillis null => no bound on that side ("all messages" when both are null). */
    fun getMessages(context: Context, threadId: Long, fromMillis: Long?, toMillis: Long?): List<SmsMessage> {
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.BODY,
            Telephony.Sms.TYPE
        )

        val selectionParts = mutableListOf("${Telephony.Sms.THREAD_ID} = ?")
        val args = mutableListOf(threadId.toString())
        if (fromMillis != null) {
            selectionParts += "${Telephony.Sms.DATE} >= ?"
            args += fromMillis.toString()
        }
        if (toMillis != null) {
            selectionParts += "${Telephony.Sms.DATE} <= ?"
            args += toMillis.toString()
        }

        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            selectionParts.joinToString(" AND "),
            args.toTypedArray(),
            "${Telephony.Sms.DATE} ASC"
        ) ?: return emptyList()

        val messages = mutableListOf<SmsMessage>()
        cursor.use { c ->
            val addressIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val typeIdx = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (c.moveToNext()) {
                val type = c.getInt(typeIdx)
                val isSent = type == Telephony.Sms.MESSAGE_TYPE_SENT || type == Telephony.Sms.MESSAGE_TYPE_OUTBOX
                val label = when (type) {
                    Telephony.Sms.MESSAGE_TYPE_INBOX -> "Received"
                    Telephony.Sms.MESSAGE_TYPE_SENT -> "Sent"
                    Telephony.Sms.MESSAGE_TYPE_DRAFT -> "Draft"
                    Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "Outbox"
                    Telephony.Sms.MESSAGE_TYPE_FAILED -> "Failed"
                    Telephony.Sms.MESSAGE_TYPE_QUEUED -> "Queued"
                    else -> "Other"
                }
                messages += SmsMessage(
                    date = c.getLong(dateIdx),
                    address = c.getString(addressIdx) ?: "",
                    body = c.getString(bodyIdx) ?: "",
                    isSent = isSent,
                    typeLabel = label
                )
            }
        }
        return messages
    }

    /** Falls back to the raw address whenever contacts aren't readable or there's no match. */
    fun resolveDisplayName(context: Context, address: String): String {
        if (address.isBlank()) return "Unknown"
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: address
        } catch (e: SecurityException) {
            address
        }
    }
}
