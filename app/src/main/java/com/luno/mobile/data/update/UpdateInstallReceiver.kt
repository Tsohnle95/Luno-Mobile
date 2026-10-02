package com.luno.mobile.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.luno.mobile.LunoApp

class UpdateInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as LunoApp).appUpdateManager.installResult(
            sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
            status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT),
            message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        )
    }
}
