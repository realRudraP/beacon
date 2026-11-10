package dev.realrudrap.beacon.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import dev.realrudrap.beacon.MainActivity
import dev.realrudrap.beacon.R
import dev.realrudrap.beacon.data.local.TokenManager
import dev.realrudrap.beacon.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class BeaconFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var tokenManager: TokenManager

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (tokenManager.getRefreshToken().isNullOrBlank()) {
            Timber.tag(TAG).d("FCM token update deferred until sign-in")
            return
        }
        Timber.tag(TAG).d("FCM token refreshed")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = userRepository.updateFcmToken(token)
                if (result.isSuccess) {
                    Timber.tag(TAG).d("FCM token registered with server")
                } else {
                    Timber.tag(TAG).w("Failed to register FCM token: %s", result.exceptionOrNull()?.message)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error registering FCM token")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Timber.tag(TAG).d("FCM message received")

        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: "Beacon"
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: ""

        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val channelId = "beacon_general_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "General Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use your notification icon
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        }.onSuccess {
            Timber.tag(TAG).d("FCM notification posted")
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Failed to post FCM notification")
        }
    }

    private companion object {
        const val TAG = "BeaconFcmService"
    }
}