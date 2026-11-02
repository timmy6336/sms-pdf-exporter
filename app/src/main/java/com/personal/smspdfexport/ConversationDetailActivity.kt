package com.personal.smspdfexport

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.personal.smspdfexport.databinding.ActivityConversationDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
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
            val result = withContext(Dispatchers.IO) {
                val timestamps = SmsRepository.getAllMessageTimestamps(applicationContext, threadId)
                MessageStats.compute(timestamps)
            }
            if (result == null) {
                binding.statsRow.visibility = View.GONE
                return@launch
            }
            binding.daysSinceLastMessageValue.text = result.daysSinceLastMessage.toString()
            binding.streakValue.text = result.streakDays.toString()
        }
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
