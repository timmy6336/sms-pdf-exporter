package com.personal.smspdfexport

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.personal.smspdfexport.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = ConversationListAdapter { conversation ->
        startActivity(
            Intent(this, ConversationDetailActivity::class.java)
                .putExtra(ConversationDetailActivity.EXTRA_THREAD_ID, conversation.threadId)
                .putExtra(ConversationDetailActivity.EXTRA_DISPLAY_NAME, conversation.displayName)
        )
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onPermissionResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.conversationList.layoutManager = LinearLayoutManager(this)
        binding.conversationList.adapter = adapter
        binding.grantPermissionButton.setOnClickListener { requestSmsPermission() }
    }

    override fun onResume() {
        super.onResume()
        onPermissionResult()
    }

    private fun hasReadSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestSmsPermission() {
        requestPermissions.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS))
    }

    private fun onPermissionResult() {
        if (hasReadSmsPermission()) {
            showContent()
            loadConversations()
        } else {
            showPermissionRequest()
        }
    }

    private fun showPermissionRequest() {
        binding.permissionGroup.visibility = android.view.View.VISIBLE
        binding.conversationList.visibility = android.view.View.GONE
        binding.emptyView.visibility = android.view.View.GONE
        binding.progressBar.visibility = android.view.View.GONE
    }

    private fun showContent() {
        binding.permissionGroup.visibility = android.view.View.GONE
    }

    private fun loadConversations() {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.conversationList.visibility = android.view.View.GONE
        binding.emptyView.visibility = android.view.View.GONE

        lifecycleScope.launch {
            val conversations = withContext(Dispatchers.IO) {
                SmsRepository.getConversations(applicationContext)
            }
            binding.progressBar.visibility = android.view.View.GONE
            if (conversations.isEmpty()) {
                binding.emptyView.visibility = android.view.View.VISIBLE
            } else {
                binding.conversationList.visibility = android.view.View.VISIBLE
                adapter.submitList(conversations)
            }
        }
    }
}
