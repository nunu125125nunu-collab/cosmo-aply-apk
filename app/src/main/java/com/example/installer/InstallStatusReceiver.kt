package com.example.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class InstallStatusReceiver : BroadcastReceiver() {

    sealed class InstallEvent {
        data class PendingUserAction(val intent: Intent) : InstallEvent()
        data class Success(val packageName: String) : InstallEvent()
        data class Failure(val statusCode: Int, val message: String) : InstallEvent()
    }

    companion object {
        private const val TAG = "InstallStatusReceiver"
        const val ACTION_INSTALL_STATUS = "com.example.installer.ACTION_INSTALL_STATUS"

        private val _events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 10)
        val events: SharedFlow<InstallEvent> = _events.asSharedFlow()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unknown status"
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: ""

        Log.d(TAG, "PackageInstaller callback received: status=$status, message=$message, package=$packageName")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // System requires user consent to proceed with installation
                val confirmationIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmationIntent != null) {
                    confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmationIntent)
                    _events.tryEmit(InstallEvent.PendingUserAction(confirmationIntent))
                } else {
                    Log.e(TAG, "STATUS_PENDING_USER_ACTION received without confirmation intent")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Package installation succeeded: $packageName")
                _events.tryEmit(InstallEvent.Success(packageName))
            }

            else -> {
                Log.e(TAG, "Package installation failed: code=$status, message=$message")
                _events.tryEmit(InstallEvent.Failure(status, message))
            }
        }
    }
}
