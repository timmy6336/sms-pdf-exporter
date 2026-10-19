package com.personal.smspdfexport

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a list of SmsMessage into a paginated PDF using Android's built-in
 * PdfDocument/Canvas APIs — no third-party PDF library needed.
 */
object PdfExporter {

    private const val PAGE_WIDTH = 595 // A4 at 72dpi, in points
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f

    fun export(
        outputStream: OutputStream,
        conversationLabel: String,
        rangeDescription: String,
        messages: List<SmsMessage>,
        selfLabel: String,
        resolveName: (String) -> String
    ) {
        val contentWidth = (PAGE_WIDTH - MARGIN * 2).toInt()
        val document = PdfDocument()

        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        var page = document.startPage(pageInfo)
        var canvas = page.canvas
        var y = MARGIN

        val titlePaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            color = Color.BLACK
        }
        val subtitlePaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 11f
            color = Color.DKGRAY
        }
        val senderPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            color = Color.BLACK
        }
        val timePaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 10f
            color = Color.GRAY
        }
        val bodyPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 12f
            color = Color.BLACK
        }
        val timeFormatter = SimpleDateFormat("EEE, MMM d, yyyy h:mm a", Locale.getDefault())
        val exportedAtFormatter = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())

        fun startNewPage() {
            document.finishPage(page)
            pageNumber += 1
            pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            page = document.startPage(pageInfo)
            canvas = page.canvas
            y = MARGIN
        }

        // Title block (first page only)
        canvas.drawText("Conversation with $conversationLabel", MARGIN, y + titlePaint.textSize, titlePaint)
        y += titlePaint.textSize + 6f
        val subtitle = "$rangeDescription  ·  ${messages.size} messages  ·  exported ${exportedAtFormatter.format(Date())}"
        canvas.drawText(subtitle, MARGIN, y + subtitlePaint.textSize, subtitlePaint)
        y += subtitlePaint.textSize + 20f

        for (message in messages) {
            val senderName = if (message.isSent) selfLabel else resolveName(message.address).ifBlank { conversationLabel }
            val headerHeight = senderPaint.fontSpacing

            val body = message.body.ifBlank { "[${message.typeLabel.lowercase()} message with no text]" }
            val staticLayout = buildStaticLayout(body, bodyPaint, contentWidth)

            val blockHeight = headerHeight + 4f + staticLayout.height + 14f
            if (y + blockHeight > PAGE_HEIGHT - MARGIN) {
                startNewPage()
            }

            canvas.drawText(senderName, MARGIN, y + senderPaint.textSize, senderPaint)
            val nameWidth = senderPaint.measureText(senderName)
            canvas.drawText(
                "   ${timeFormatter.format(Date(message.date))}",
                MARGIN + nameWidth,
                y + senderPaint.textSize,
                timePaint
            )
            y += headerHeight + 4f

            canvas.save()
            canvas.translate(MARGIN, y)
            staticLayout.draw(canvas)
            canvas.restore()
            y += staticLayout.height + 14f
        }

        document.finishPage(page)
        document.writeTo(outputStream)
        document.close()
    }

    private fun buildStaticLayout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(false)
            .build()
    }
}
