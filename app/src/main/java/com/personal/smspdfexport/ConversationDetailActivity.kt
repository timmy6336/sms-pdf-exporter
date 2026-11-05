package com.personal.smspdfexport

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.personal.smspdfexport.databinding.ActivityConversationDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

class ConversationDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_DISPLAY_NAME = "display_name"
    }

    private lateinit var binding: ActivityConversationDetailBinding
    private var threadId: Long = -1
    private var displayName: String = ""

    private var fromMillis: Long? = null
    private var toMillis: Long? = null
    private var lastSavedUri: Uri? = null
    private var lastSavedMimeType: String = "application/pdf"
    private var exportFormatIsPdf: Boolean = true

    // A fixed-mimeType CreateDocument contract can't switch between PDF and
    // text at launch time, so the create-document Intent is built by hand
    // instead, with whichever mime type/filename the selected format needs.
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            runExport(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1)
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: "Unknown"

        setSupportActionBar(binding.toolbar)
        binding.toolbar.title = getString(R.string.export_conversation)
        binding.conversationTitle.text = displayName
        binding.conversationSubtitle.text = ""

        binding.rangeGroup.setOnCheckedChangeListener { _, checkedId ->
            binding.dateRangeGroup.visibility =
                if (checkedId == binding.dateRangeRadio.id) View.VISIBLE else View.GONE
        }

        binding.fromDateButton.setOnClickListener { pickDate(isFrom = true) }
        binding.toDateButton.setOnClickListener { pickDate(isFrom = false) }
        binding.exportButton.setOnClickListener { onExportClicked() }

        binding.openPdfButton.setOnClickListener { openPdf() }
        binding.sharePdfButton.setOnClickListener { sharePdf() }

        loadStats()
    }

    private fun loadStats() {
        lifecycleScope.launch {
            val zone = ZoneId.systemDefault()

            val data = withContext(Dispatchers.IO) {
                val allMessages = SmsRepository.getMessages(applicationContext, threadId, null, null)
                val streak = MessageStats.compute(allMessages.map { it.date }, zone = zone)
                val insights = ConversationInsights.compute(
                    messages = allMessages,
                    selfLabel = getString(R.string.you),
                    resolveName = { address -> SmsRepository.resolveDisplayName(applicationContext, address) },
                    zone = zone
                )
                val analytics = streak?.let { s ->
                    val start = s.streakStartDate
                    val end = s.streakEndDate
                    if (start != null && end != null) {
                        StreakAnalyzer.compute(
                            messages = allMessages,
                            streakStartDate = start,
                            streakEndDate = end,
                            selfLabel = getString(R.string.you),
                            resolveName = { address -> SmsRepository.resolveDisplayName(applicationContext, address) },
                            zone = zone
                        )
                    } else null
                }
                Triple(streak, insights, analytics)
            }
            val (streak, insights, analytics) = data

            if (streak == null || insights == null) {
                binding.glanceCard.visibility = View.GONE
                binding.streakAnalyticsSection.visibility = View.GONE
                binding.funFactsSection.visibility = View.GONE
                return@launch
            }

            showGlanceStats(streak, insights)

            if (analytics != null && streak.streakStartDate != null && streak.streakEndDate != null) {
                showStreakAnalytics(streak.streakStartDate, streak.streakEndDate, analytics)
            } else {
                binding.streakAnalyticsSection.visibility = View.GONE
            }

            showFunFacts(insights)
        }
    }

    private fun showGlanceStats(streak: MessageStats.Result, insights: ConversationInsights.Result) {
        binding.daysSinceLastMessageValue.text = streak.daysSinceLastMessage.toString()
        binding.streakValue.text = streak.streakDays.toString()
        binding.longestStreakValue.text = insights.longestStreakDays.toString()
        binding.totalMessagesValue.text = insights.totalMessages.toString()

        val sinceFormat = DateTimeFormatter.ofPattern("MMM d, yyyy")
        binding.conversationSubtitle.text = getString(R.string.texting_since, insights.firstMessageDate.format(sinceFormat))
        binding.glanceCard.visibility = View.VISIBLE
    }

    private fun showStreakAnalytics(start: LocalDate, end: LocalDate, analytics: StreakAnalyzer.Analytics) {
        val dateLabelFormat = DateTimeFormatter.ofPattern("MMM d")
        binding.streakSectionTitle.text = getString(
            R.string.streak_activity_title,
            start.format(dateLabelFormat),
            end.format(dateLabelFormat)
        )

        val senderOrder = analytics.senderBreakdowns.map { it.senderKey }
        binding.streakChart.setData(analytics.days, analytics.dailyCounts, senderOrder)

        binding.streakLegend.removeAllViews()
        analytics.senderBreakdowns.forEachIndexed { index, breakdown ->
            addLegendChip(binding.streakLegend, binding.streakChart.colorForIndex(index), breakdown.label)
        }

        binding.streakStatsList.removeAllViews()
        addStatRow(
            binding.streakStatsList,
            getString(R.string.combined_label),
            analytics.combinedTotal,
            analytics.combinedAveragePerDay,
            bold = true
        )
        analytics.senderBreakdowns.forEach { breakdown ->
            addStatRow(binding.streakStatsList, breakdown.label, breakdown.total, breakdown.averagePerDay, bold = false)
        }

        binding.streakAnalyticsSection.visibility = View.VISIBLE
    }

    private fun showFunFacts(insights: ConversationInsights.Result) {
        binding.funFactsList.removeAllViews()

        insights.busiestWeekdayLabel?.let { addFactRow(getString(R.string.busiest_day_label), it) }
        insights.busiestHourLabel?.let { addFactRow(getString(R.string.most_active_label), it) }
        insights.senderTotals.forEach { sender ->
            sender.medianReplyMinutes?.let { minutes ->
                addFactRow(
                    getString(R.string.reply_speed_label, sender.label),
                    ConversationInsights.formatReplyMinutes(minutes)
                )
            }
        }

        binding.funFactsSection.visibility = if (binding.funFactsList.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun addFactRow(label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val labelView = TextView(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(ContextCompat.getColor(this@ConversationDetailActivity, R.color.text_secondary))
            textSize = 13f
        }
        val valueView = TextView(this).apply {
            text = value
            setTextColor(ContextCompat.getColor(this@ConversationDetailActivity, R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }
        row.addView(labelView)
        row.addView(valueView)
        binding.funFactsList.addView(row)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun addLegendChip(container: LinearLayout, color: Int, label: String) {
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(16), dp(4))
        }
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(4) }
            background = ShapeDrawable(OvalShape()).apply { paint.color = color }
        }
        val text = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@ConversationDetailActivity, R.color.text_secondary))
        }
        chip.addView(dot)
        chip.addView(text)
        container.addView(chip)
    }

    private fun addStatRow(container: LinearLayout, label: String, total: Int, averagePerDay: Double, bold: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val nameView = TextView(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(ContextCompat.getColor(this@ConversationDetailActivity, R.color.text_primary))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        val statsView = TextView(this).apply {
            text = getString(R.string.stat_row_detail, total, String.format(Locale.getDefault(), "%.1f", averagePerDay))
            setTextColor(ContextCompat.getColor(this@ConversationDetailActivity, R.color.text_secondary))
            textSize = 13f
        }
        row.addView(nameView)
        row.addView(statsView)
        container.addView(row)
    }

    private fun pickDate(isFrom: Boolean) {
        val calendar = Calendar.getInstance()
        val existing = if (isFrom) fromMillis else toMillis
        if (existing != null) calendar.timeInMillis = existing

        DatePickerDialog(
            this,
            { _, year, month, day ->
                val cal = Calendar.getInstance()
                cal.set(year, month, day, 0, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                if (isFrom) {
                    fromMillis = cal.timeInMillis
                    binding.fromDateButton.text = formatButtonDate(cal.timeInMillis)
                } else {
                    // Make "to" inclusive of the whole day.
                    cal.set(Calendar.HOUR_OF_DAY, 23)
                    cal.set(Calendar.MINUTE, 59)
                    cal.set(Calendar.SECOND, 59)
                    toMillis = cal.timeInMillis
                    binding.toDateButton.text = formatButtonDate(cal.timeInMillis)
                }
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun formatButtonDate(millis: Long): String =
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(millis)

    private fun onExportClicked() {
        val useRange = binding.dateRangeRadio.isChecked
        if (useRange && (fromMillis == null || toMillis == null)) {
            Toast.makeText(this, "Pick both a from and to date, or choose \"All messages\".", Toast.LENGTH_LONG).show()
            return
        }
        if (useRange && fromMillis!! > toMillis!!) {
            Toast.makeText(this, "The from date must be before the to date.", Toast.LENGTH_LONG).show()
            return
        }

        exportFormatIsPdf = binding.pdfFormatRadio.isChecked
        val mimeType = if (exportFormatIsPdf) "application/pdf" else "text/plain"
        val extension = if (exportFormatIsPdf) "pdf" else "txt"

        val safeName = displayName.filter { it.isLetterOrDigit() || it == ' ' }.trim().ifBlank { "conversation" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis())
        val filename = "SMS_${safeName.replace(' ', '_')}_$stamp.$extension"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, filename)
        }
        createDocumentLauncher.launch(intent)
    }

    private fun runExport(uri: Uri) {
        setBusy(true)
        val useRange = binding.dateRangeRadio.isChecked
        val from = if (useRange) fromMillis else null
        val to = if (useRange) toMillis else null

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val messages = SmsRepository.getMessages(applicationContext, threadId, from, to)
                    if (messages.isEmpty()) {
                        return@withContext Result.success(0)
                    }
                    val rangeDescription = if (useRange) {
                        "${formatButtonDate(from!!)} – ${formatButtonDate(to!!)}"
                    } else {
                        "all messages"
                    }
                    contentResolver.openOutputStream(uri)?.use { out ->
                        if (exportFormatIsPdf) {
                            PdfExporter.export(
                                outputStream = out,
                                conversationLabel = displayName,
                                rangeDescription = rangeDescription,
                                messages = messages,
                                selfLabel = getString(R.string.you),
                                resolveName = { address -> SmsRepository.resolveDisplayName(applicationContext, address) }
                            )
                        } else {
                            TextExporter.export(
                                outputStream = out,
                                conversationLabel = displayName,
                                rangeDescription = rangeDescription,
                                messages = messages,
                                selfLabel = getString(R.string.you),
                                resolveName = { address -> SmsRepository.resolveDisplayName(applicationContext, address) }
                            )
                        }
                    }
                    Result.success(messages.size)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            setBusy(false)
            outcome.onSuccess { count ->
                if (count == 0) {
                    showResult(getString(R.string.no_messages_in_range), showActions = false)
                } else {
                    lastSavedUri = uri
                    lastSavedMimeType = if (exportFormatIsPdf) "application/pdf" else "text/plain"
                    showResult(getString(R.string.export_done, count), showActions = true)
                }
            }.onFailure { e ->
                showResult(getString(R.string.export_failed, e.message ?: "unknown error"), showActions = false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.exportProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.exportButton.isEnabled = !busy
    }

    private fun showResult(message: String, showActions: Boolean) {
        binding.resultText.visibility = View.VISIBLE
        binding.resultText.text = message
        binding.resultActions.visibility = if (showActions) View.VISIBLE else View.GONE
    }

    private fun openPdf() {
        val uri = lastSavedUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, lastSavedMimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open it.", Toast.LENGTH_LONG).show()
        }
    }

    private fun sharePdf() {
        val uri = lastSavedUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = lastSavedMimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_pdf)))
    }
}
