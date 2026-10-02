package com.luno.mobile.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.luno.mobile.MainActivity
import com.luno.mobile.R

internal object UpdateCompletionNotifier {
    private const val CHANNEL = "app_updates"
    private const val NOTIFICATION_ID = 1701

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    @android.annotation.SuppressLint("MissingPermission")
    fun show(context: Context, version: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Luno $version installed")
            .setContentText("Update complete. Tap to open Luno.")
            .setContentIntent(open)
            .addAction(0, "Open Luno", open)
            .setAutoCancel(true)
            .build())
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
