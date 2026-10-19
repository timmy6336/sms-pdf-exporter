package com.personal.smspdfexport

/** One text message, in the shape the PDF renderer and the SMS provider both use. */
data class SmsMessage(
    val date: Long,
    val address: String,
    val body: String,
    val isSent: Boolean,
    val typeLabel: String
)
