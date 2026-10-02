package com.luno.mobile.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.luno.mobile.LunoApp

/** Protected system replacement event arrives in the new APK after the old process is killed. */
class UpdateReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            (context.applicationContext as LunoApp).appUpdateManager.onPackageReplaced()
        }
    }
}
