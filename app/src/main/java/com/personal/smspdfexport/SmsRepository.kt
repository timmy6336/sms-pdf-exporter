package com.personal.smspdfexport

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony

/**
 * All reads go through the system SMS/MMS content providers — nothing is
 * ever written back, and nothing leaves the device.
 *
 * A thread's history is split across two tables on Android: plain text
 * exchanges live in `content://sms`, while anything the OS classified as
 * MMS — group texts, picture/attachment messages, or a long message your
 * carrier chose to send as MMS — lives in `content://mms` with its body
 * in a separate `part` table. Reading only `content://sms` (as an earlier
 * version of this file did) silently drops every MMS row, which looks
 * exactly like random missing messages in the middle of a real
 * conversation. Both are merged here, sorted together by timestamp.
 */
object SmsRepository {

    private class ThreadAggregate(
        var address: String = "",
        var lastDate: Long = -1L,
        var lastIsMms: Boolean = false,
        var lastMmsId: Long = -1L,
        var snippet: String = "",
        var count: Int = 0
    )

    fun getConversations(context: Context): List<Conversation> {
        val byThread = HashMap<Long, ThreadAggregate>()

        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.BODY),
            null,
            null,
            null
        )?.use { c ->
            val threadIdx = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addrIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)

            while (c.moveToNext()) {
                val threadId = c.getLong(threadIdx)
                val date = c.getLong(dateIdx)
                val address = c.getString(addrIdx) ?: ""
                val body = c.getString(bodyIdx) ?: ""

                val agg = byThread.getOrPut(threadId) { ThreadAggregate() }
                agg.count += 1
                if (agg.address.isBlank() && address.isNotBlank()) agg.address = address
                if (date > agg.lastDate) {
                    agg.lastDate = date
                    agg.lastIsMms = false
                    agg.snippet = body
                }
            }
        }

        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE),
            null,
            null,
            null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(Telephony.Mms._ID)
            val threadIdx = c.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
            val dateIdx = c.getColumnIndexOrThrow(Telephony.Mms.DATE)

            while (c.moveToNext()) {
                val threadId = c.getLong(threadIdx)
                val mmsId = c.getLong(idIdx)
                val dateMillis = c.getLong(dateIdx) * 1000L // Mms.DATE is in seconds, not millis

                val agg = byThread.getOrPut(threadId) { ThreadAggregate() }
                agg.count += 1
                if (dateMillis > agg.lastDate) {
                    agg.lastDate = dateMillis
                    agg.lastIsMms = true
                    agg.lastMmsId = mmsId
                }
            }
        }

        return byThread.entries
            .sortedByDescending { it.value.lastDate }
            .map { (threadId, agg) ->
                val snippet = if (agg.lastIsMms) {
                    mmsTextBody(context, agg.lastMmsId).ifBlank { "[Picture/attachment message]" }
                } else {
                    agg.snippet
                }
                val address = agg.address.ifBlank {
                    if (agg.lastIsMms) mmsFromAddress(context, agg.lastMmsId) else ""
                }
                Conversation(
                    threadId = threadId,
                    address = address,
                    displayName = resolveDisplayName(context, address),
                    messageCount = agg.count,
                    lastDate = agg.lastDate,
                    snippet = snippet
                )
            }
    }

    /** fromMillis/toMillis null => no bound on that side ("all messages" when both are null). */
    fun getMessages(context: Context, threadId: Long, fromMillis: Long?, toMillis: Long?): List<SmsMessage> {
        val sms = getSmsMessages(context, threadId, fromMillis, toMillis)
        val mms = getMmsMessages(context, threadId, fromMillis, toMillis)
        return (sms + mms).sortedBy { it.date }
    }

    private fun getSmsMessages(context: Context, threadId: Long, fromMillis: Long?, toMillis: Long?): List<SmsMessage> {
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
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.BODY, Telephony.Sms.TYPE),
            selectionParts.joinToString(" AND "),
            args.toTypedArray(),
            null
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

    private class MmsRow(val id: Long, val dateMillis: Long, val isSent: Boolean)

    private fun getMmsMessages(context: Context, threadId: Long, fromMillis: Long?, toMillis: Long?): List<SmsMessage> {
        val selectionParts = mutableListOf("${Telephony.Mms.THREAD_ID} = ?")
        val args = mutableListOf(threadId.toString())
        if (fromMillis != null) {
            selectionParts += "${Telephony.Mms.DATE} >= ?"
            args += (fromMillis / 1000).toString()
        }
        if (toMillis != null) {
            selectionParts += "${Telephony.Mms.DATE} <= ?"
            args += (toMillis / 1000).toString()
        }

        val rows = mutableListOf<MmsRow>()
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
            selectionParts.joinToString(" AND "),
            args.toTypedArray(),
            null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(Telephony.Mms._ID)
            val dateIdx = c.getColumnIndexOrThrow(Telephony.Mms.DATE)
            val boxIdx = c.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
            while (c.moveToNext()) {
                rows += MmsRow(
                    id = c.getLong(idIdx),
                    dateMillis = c.getLong(dateIdx) * 1000L,
                    isSent = c.getInt(boxIdx) == Telephony.Mms.MESSAGE_BOX_SENT
                )
            }
        }
        if (rows.isEmpty()) return emptyList()

        // One query covers every message's parts instead of two (text + attachment
        // check) per message — with hundreds of MMS rows that was hundreds of extra
        // round trips to the system process and was the main cause of slow loading.
        val partInfo = fetchMmsPartInfo(context, rows.map { it.id })

        return rows.map { row ->
            val (textBody, hasAttachment) = partInfo[row.id] ?: ("" to false)
            val body = when {
                textBody.isNotBlank() && hasAttachment -> "$textBody\n[+ picture/attachment — not included in this PDF]"
                textBody.isNotBlank() -> textBody
                hasAttachment -> "[Picture/attachment message — not included in this PDF]"
                else -> "[Empty MMS message]"
            }

            SmsMessage(
                date = row.dateMillis,
                address = if (row.isSent) "" else mmsFromAddress(context, row.id),
                body = body,
                isSent = row.isSent,
                typeLabel = if (row.isSent) "Sent" else "Received"
            )
        }
    }

    /** mmsId -> (text body, has a non-text/attachment part), for every id in one batched query (chunked to stay under SQLite's IN-clause limit). */
    private fun fetchMmsPartInfo(context: Context, mmsIds: List<Long>): Map<Long, Pair<String, Boolean>> {
        val textByMms = HashMap<Long, MutableList<String>>()
        val attachmentByMms = HashSet<Long>()

        mmsIds.chunked(400).forEach { chunk ->
            try {
                val placeholders = chunk.joinToString(",") { "?" }
                context.contentResolver.query(
                    Uri.parse("content://mms/part"),
                    arrayOf("mid", "ct", "text"),
                    "mid IN ($placeholders)",
                    chunk.map { it.toString() }.toTypedArray(),
                    null
                )?.use { c ->
                    val midIdx = c.getColumnIndexOrThrow("mid")
                    val ctIdx = c.getColumnIndexOrThrow("ct")
                    val textIdx = c.getColumnIndexOrThrow("text")
                    while (c.moveToNext()) {
                        val mid = c.getLong(midIdx)
                        val ct = c.getString(ctIdx)
                        if (ct == "text/plain") {
                            val text = c.getString(textIdx)
                            if (!text.isNullOrBlank()) {
                                textByMms.getOrPut(mid) { mutableListOf() } += text
                            }
                        } else if (ct != null && ct != "application/smil") {
                            attachmentByMms += mid
                        }
                    }
                }
            } catch (e: Exception) {
                // Fall through — missing part info just means that chunk's messages
                // show as empty/no-attachment rather than crashing the whole load.
            }
        }

        return mmsIds.associateWith { id ->
            (textByMms[id]?.joinToString("\n") ?: "") to attachmentByMms.contains(id)
        }
    }

    private fun mmsTextBody(context: Context, mmsId: Long): String = try {
        context.contentResolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("ct", "text"),
            "mid=?",
            arrayOf(mmsId.toString()),
            null
        )?.use { c ->
            val ctIdx = c.getColumnIndexOrThrow("ct")
            val textIdx = c.getColumnIndexOrThrow("text")
            val parts = mutableListOf<String>()
            while (c.moveToNext()) {
                if (c.getString(ctIdx) == "text/plain") {
                    c.getString(textIdx)?.let { if (it.isNotBlank()) parts += it }
                }
            }
            parts.joinToString("\n")
        } ?: ""
    } catch (e: Exception) {
        ""
    }

    // PduHeaders.FROM = 137, the standard MMS address-type constant for "sender".
    private fun mmsFromAddress(context: Context, mmsId: Long): String = try {
        context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            null,
            null,
            null
        )?.use { c ->
            val addrIdx = c.getColumnIndexOrThrow("address")
            val typeIdx = c.getColumnIndexOrThrow("type")
            var from = ""
            while (c.moveToNext()) {
                if (c.getInt(typeIdx) == 137) {
                    from = c.getString(addrIdx) ?: ""
                    break
                }
            }
            from
        } ?: ""
    } catch (e: Exception) {
        ""
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
