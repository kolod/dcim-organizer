package com.kolod.dcimorganizer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kolod.dcimorganizer.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val organizer by lazy { PhotoOrganizer(this) }

    // Permission request for Android < 13
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            startOrganizing()
        } else {
            showStatus(getString(R.string.permission_required))
        }
    }

    // Manage External Storage request result (Android 11+)
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            startOrganizing()
        } else {
            // Fall back to MediaStore approach
            startOrganizing()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.organizeButton.setOnClickListener {
            checkPermissionsAndOrganize()
        }
    }

    private fun checkPermissionsAndOrganize() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    startOrganizing()
                } else {
                    showManageStorageDialog()
                }
            }
            else -> {
                requestStoragePermissions()
            }
        }
    }

    private fun requestStoragePermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            startOrganizing()
        } else {
            requestPermissionsLauncher.launch(permissions)
        }
    }

    private fun showManageStorageDialog() {
        AlertDialog.Builder(this)
            .setMessage(R.string.manage_storage_required)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                manageStorageLauncher.launch(intent)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                // Fall back to MediaStore approach without full manage permission
                startOrganizing()
            }
            .show()
    }

    private fun startOrganizing() {
        binding.organizeButton.isEnabled = false
        showStatus(getString(R.string.organizing))

        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    organizer.organize()
                } catch (e: Exception) {
                    -1
                }
            }

            binding.organizeButton.isEnabled = true
            when {
                count < 0 -> showStatus(getString(R.string.error_occurred))
                count == 0 -> showStatus(getString(R.string.already_organized))
                else -> showStatus(getString(R.string.done_format, count))
            }
        }
    }

    private fun showStatus(message: String) {
        binding.statusText.text = message
        binding.statusText.visibility = View.VISIBLE
    }
}
