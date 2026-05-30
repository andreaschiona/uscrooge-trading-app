package com.uscrooge.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.uscrooge.app.MainActivity
import com.uscrooge.app.R
import com.uscrooge.app.data.model.Order
import com.uscrooge.app.data.model.TradingSignal
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val CHANNEL_ID = "trading_signals"
        private const val CHANNEL_NAME = "Trading Signals"
        private const val CHANNEL_DESCRIPTION = "Notifications for trading signals and order execution"
        private const val UPDATE_CHANNEL_ID = "app_updates"
        private const val UPDATE_CHANNEL_NAME = "App Updates"
        private const val UPDATE_CHANNEL_DESCRIPTION = "Notifications for new app versions"

        private const val SIGNAL_NOTIFICATION_ID_BASE = 1000
        private const val ORDER_NOTIFICATION_ID_BASE = 2000
        private const val ERROR_NOTIFICATION_ID = 9999
        private const val UPDATE_NOTIFICATION_ID = 8000
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = CHANNEL_DESCRIPTION
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)

            val updateChannel = NotificationChannel(UPDATE_CHANNEL_ID, UPDATE_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = UPDATE_CHANNEL_DESCRIPTION
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(updateChannel)
        }
    }

    fun sendSignalNotification(signal: TradingSignal) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("signal_id", signal.id)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            signal.id.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val strengthPercent = (signal.strength * 100).toInt()
        val title = when (signal.type) {
            com.uscrooge.app.data.model.SignalType.BUY -> "🟢 BUY Signal - ${signal.pair}"
            com.uscrooge.app.data.model.SignalType.SELL -> "🔴 SELL Signal - ${signal.pair}"
            else -> "Signal - ${signal.pair}"
        }

        val message = buildString {
            append("Strength: $strengthPercent%\n")
            append("Price: €${String.format("%.2f", signal.currentPrice)}\n")
            append("Amount: €${String.format("%.2f", signal.suggestedAmount)}\n")
            append("R/R: ${String.format("%.2f", signal.riskRewardRatio)}")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(
                SIGNAL_NOTIFICATION_ID_BASE + signal.id.toInt(),
                notification
            )
        }
    }

    fun sendOrderExecutedNotification(order: Order) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            order.orderId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = when (order.side) {
            com.uscrooge.app.data.model.OrderSide.BUY -> "✅ Order Executed - BUY ${order.pair}"
            com.uscrooge.app.data.model.OrderSide.SELL -> "✅ Order Executed - SELL ${order.pair}"
        }

        val message = buildString {
            append("Price: €${String.format("%.2f", order.price)}\n")
            append("Amount: ${String.format("%.6f", order.amount)}\n")
            append("Total: €${String.format("%.2f", order.cost)}\n")
            append("Fee: €${String.format("%.2f", order.fee)}")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(
                ORDER_NOTIFICATION_ID_BASE + order.orderId.hashCode(),
                notification
            )
        }
    }

    fun sendUpdateNotification(latestVersion: String, downloadUrl: String, releaseNotes: String?) {
        val intent = Intent(context, com.uscrooge.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("navigate_to_settings", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val message = buildString {
            append("Version $latestVersion is available")
            if (!releaseNotes.isNullOrBlank()) {
                append("\n\n${releaseNotes.take(200)}")
            }
        }

        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(com.uscrooge.app.R.drawable.ic_launcher_foreground)
            .setContentTitle("App Update Available")
            .setContentText("Version $latestVersion is available")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(UPDATE_NOTIFICATION_ID, notification)
        }
    }

    fun cancelErrorNotification() {
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).cancel(ERROR_NOTIFICATION_ID)
        }
    }

    fun sendErrorNotification(title: String, message: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            ERROR_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("⚠️ $title")
            .setContentText(message ?: "An error occurred")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message ?: "An error occurred"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(ERROR_NOTIFICATION_ID, notification)
        }
    }
}
