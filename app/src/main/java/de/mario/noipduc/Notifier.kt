package de.mario.noipduc

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

/**
 * Meldet dauerhafte Fehler (z. B. falsches Passwort), damit ein Stopp im Hintergrund
 * nicht unbemerkt bleibt. Die Benachrichtigung enthält keine Zugangsdaten.
 */
object Notifier {
    private const val CHANNEL = "errors"
    private const val ID = 1

    fun fatal(context: Context, message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Fehler", NotificationManager.IMPORTANCE_DEFAULT)
        )
        // Expliziter Intent + FLAG_IMMUTABLE: PendingIntent kann nicht von fremden Apps verändert werden.
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("No-IP-Updates gestoppt")
            .setContentText(message)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID, n)
        } catch (_: SecurityException) {
            // Berechtigung zwischenzeitlich entzogen – Protokoll in der App reicht dann.
        }
    }
}
