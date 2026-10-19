package com.personal.smspdfexport

/** One SMS thread, as shown in the conversation list. */
data class Conversation(
    val threadId: Long,
    val address: String,
    val displayName: String,
    val messageCount: Int,
    val lastDate: Long,
    val snippet: String
)
