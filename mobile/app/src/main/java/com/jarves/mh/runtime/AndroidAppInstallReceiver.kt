package com.jarves.mh.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

class AndroidAppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidAppInstaller.ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val message = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "Waiting for installation approval."
            PackageInstaller.STATUS_SUCCESS -> "APK installed."
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation was cancelled."
            else -> "Installation failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unknown installer error"}"
        }
        val result = AndroidInstallResults.record(context, sessionId, status, message,
            intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)) ?: return
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            runCatching {
                @Suppress("DEPRECATION")
                val userAction = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                checkNotNull(userAction) { "Installer did not provide an approval prompt" }
                context.startActivity(userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                AndroidInstallResults.record(context, sessionId, PackageInstaller.STATUS_FAILURE,
                    "Could not open installation prompt: ${it.message}")
                runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
            }
            return
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return
        }
        val packageName = result.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                context.packageManager.getLaunchIntentSenderForPackage(packageName).sendIntent(
                    context, 0, null, null, null,
                )
            }.onFailure {
                Toast.makeText(context, "Installed $packageName. Open it from your launcher.", Toast.LENGTH_LONG).show()
            }
            return
        }
        runCatching {
            val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(packageName))
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(context, "Installed $packageName. Open it from your launcher.", Toast.LENGTH_LONG).show()
        }
    }
}
