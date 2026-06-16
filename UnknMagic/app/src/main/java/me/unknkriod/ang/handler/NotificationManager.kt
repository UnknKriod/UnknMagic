package me.unknkriod.ang.handler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import me.unknkriod.ang.AppConfig
import me.unknkriod.ang.R
import me.unknkriod.ang.core.CoreServiceManager
import me.unknkriod.ang.dto.ProfileItem
import me.unknkriod.ang.extension.toSpeedString
import me.unknkriod.ang.ui.MainActivity
import me.unknkriod.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

object NotificationManager {
    private const val NOTIFICATION_ID = 1
    private const val EVENT_NOTIFICATION_ID = 2
    private const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
    private const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 1
    private const val NOTIFICATION_PENDING_INTENT_RESTART_V2RAY = 2
    private const val NOTIFICATION_PENDING_INTENT_PAUSE_V2RAY = 3
    private const val NOTIFICATION_PENDING_INTENT_RESUME_V2RAY = 4
    private const val QUERY_INTERVAL_MS = 2000L // Slightly faster but still efficient

    private var lastQueryTime = 0L
    private var speedNotificationJob: Job? = null
    private var currentRemarks: String? = null
    private var currentChannelId: String? = null

    // Cache components to avoid allocations in the update loop
    private var cachedContentIntent: PendingIntent? = null
    private var cachedStopIntent: PendingIntent? = null
    private var cachedRestartIntent: PendingIntent? = null
    private var cachedPauseIntent: PendingIntent? = null
    private var cachedResumeIntent: PendingIntent? = null
    private var builder: NotificationCompat.Builder? = null
    
    private val speedStringBuilder = StringBuilder()

    /**
     * Starts the speed notification.
     * @param currentConfig The current profile configuration.
     */
    fun startSpeedNotification(currentConfig: ProfileItem?) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) return
        if (speedNotificationJob != null || CoreServiceManager.isRunning() == false) return

        var lastZeroSpeed = false
        val outboundTags = currentConfig?.getAllOutboundTags()
        outboundTags?.remove(AppConfig.TAG_DIRECT)

        speedNotificationJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val queryTime = System.currentTimeMillis()
                val sinceLastQueryMs = queryTime - lastQueryTime
                
                if (sinceLastQueryMs < 500) { // Safety check against too frequent updates
                    delay(QUERY_INTERVAL_MS)
                    continue
                }
                
                val sinceLastQueryInSeconds = sinceLastQueryMs / 1000.0

                var proxyTotal = 0L
                speedStringBuilder.setLength(0)
                
                outboundTags?.forEach {
                    val up = CoreServiceManager.queryStats(it, AppConfig.UPLINK)
                    val down = CoreServiceManager.queryStats(it, AppConfig.DOWNLINK)
                    if (up + down > 0) {
                        appendSpeedString(speedStringBuilder, it, up / sinceLastQueryInSeconds, down / sinceLastQueryInSeconds)
                        proxyTotal += up + down
                    }
                }
                
                val directUplink = CoreServiceManager.queryStats(AppConfig.TAG_DIRECT, AppConfig.UPLINK)
                val directDownlink = CoreServiceManager.queryStats(AppConfig.TAG_DIRECT, AppConfig.DOWNLINK)
                val zeroSpeed = proxyTotal == 0L && directUplink == 0L && directDownlink == 0L
                
                if (!zeroSpeed || !lastZeroSpeed) {
                    if (proxyTotal == 0L) {
                        appendSpeedString(speedStringBuilder, outboundTags?.firstOrNull(), 0.0, 0.0)
                    }
                    appendSpeedString(
                        speedStringBuilder, AppConfig.TAG_DIRECT, directUplink / sinceLastQueryInSeconds,
                        directDownlink / sinceLastQueryInSeconds
                    )
                    updateNotification(speedStringBuilder.toString())
                }
                
                lastZeroSpeed = zeroSpeed
                lastQueryTime = queryTime
                delay(QUERY_INTERVAL_MS)
            }
        }
    }

    /**
     * Shows the notification and makes the service foreground.
     */
    fun showNotification(currentConfig: ProfileItem?) {
        val service = getService() ?: return

        lastQueryTime = System.currentTimeMillis()
        currentRemarks = currentConfig?.remarks ?: "Unknown Magic"

        preparePendingIntents(service)

        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(service)
        } else {
            ""
        }
        currentChannelId = channelId

        val isPaused = CoreServiceManager.isPaused()
        val notificationBuilder = NotificationCompat.Builder(service, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(currentRemarks)
            .setContentText(if (isPaused) service.getString(R.string.connection_paused) else null)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Changed from MIN to LOW for better visibility
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(cachedContentIntent)

        if (CoreServiceManager.isPaused()) {
            notificationBuilder.addAction(
                R.drawable.ic_play_24dp,
                service.getString(R.string.notification_action_resume_v2ray),
                cachedResumeIntent
            )
        } else {
            notificationBuilder.addAction(
                android.R.drawable.ic_media_pause,
                service.getString(R.string.notification_action_pause_v2ray),
                cachedPauseIntent
            )
        }

        notificationBuilder
            .addAction(R.drawable.ic_stop_24dp, service.getString(R.string.notification_action_stop_v2ray), cachedStopIntent)
            .addAction(R.drawable.ic_refresh_24dp, service.getString(R.string.title_service_restart), cachedRestartIntent)
        
        builder = notificationBuilder
        service.startForeground(NOTIFICATION_ID, notificationBuilder.build())
    }

    private fun preparePendingIntents(context: Context) {
        if (cachedContentIntent != null) return
        
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val startMainIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        cachedContentIntent = PendingIntent.getActivity(context, NOTIFICATION_PENDING_INTENT_CONTENT, startMainIntent, flags)

        val stopV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE).apply {
            `package` = AppConfig.ANG_PACKAGE
            putExtra("key", AppConfig.MSG_STATE_STOP)
        }
        cachedStopIntent = PendingIntent.getBroadcast(context, NOTIFICATION_PENDING_INTENT_STOP_V2RAY, stopV2RayIntent, flags)

        val restartV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE).apply {
            `package` = AppConfig.ANG_PACKAGE
            putExtra("key", AppConfig.MSG_STATE_RESTART)
        }
        cachedRestartIntent = PendingIntent.getBroadcast(context, NOTIFICATION_PENDING_INTENT_RESTART_V2RAY, restartV2RayIntent, flags)

        val pauseV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE).apply {
            `package` = AppConfig.ANG_PACKAGE
            putExtra("key", AppConfig.MSG_STATE_PAUSE)
        }
        cachedPauseIntent = PendingIntent.getBroadcast(context, NOTIFICATION_PENDING_INTENT_PAUSE_V2RAY, pauseV2RayIntent, flags)

        val resumeV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE).apply {
            `package` = AppConfig.ANG_PACKAGE
            putExtra("key", AppConfig.MSG_STATE_RESUME)
        }
        cachedResumeIntent = PendingIntent.getBroadcast(context, NOTIFICATION_PENDING_INTENT_RESUME_V2RAY, resumeV2RayIntent, flags)
    }

    /**
     * Cancels the notification.
     */
    fun cancelNotification() {
        val service = getService() ?: return
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)

        speedNotificationJob?.cancel()
        speedNotificationJob = null
        currentRemarks = null
        currentChannelId = null
        builder = null
        // We keep cachedIntents for reuse
    }

    /**
     * Stops the speed notification.
     */
    fun stopSpeedNotification(currentConfig: ProfileItem?) {
        speedNotificationJob?.let {
            it.cancel()
            speedNotificationJob = null
            updateNotification(currentConfig?.remarks)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(service: Service): String {
        val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Foreground Service Channel
        val channelId = AppConfig.RAY_NG_CHANNEL_ID
        val channelName = AppConfig.RAY_NG_CHANNEL_NAME
        val chan = NotificationChannel(
            channelId,
            channelName, 
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            lightColor = Color.DKGRAY
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(chan)

        // Event Channel (High Importance for heads-up)
        val eventChan = NotificationChannel(
            AppConfig.RAY_NG_EVENT_CHANNEL_ID,
            AppConfig.RAY_NG_EVENT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            lightColor = Color.BLUE
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(eventChan)

        return channelId
    }

    /**
     * Shows a high-priority event notification (banner).
     */
    fun showEventNotification(context: Context, title: String, content: String) {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Ensure channel exists
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val eventChan = NotificationChannel(
                AppConfig.RAY_NG_EVENT_CHANNEL_ID,
                AppConfig.RAY_NG_EVENT_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                lightColor = Color.BLUE
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(eventChan)
            AppConfig.RAY_NG_EVENT_CHANNEL_ID
        } else {
            ""
        }

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val startMainIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(context, 100, startMainIntent, flags)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setDefaults(Notification.DEFAULT_ALL)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(EVENT_NOTIFICATION_ID, builder.build())
    }

    /**
     * Updates the notification text with minimal overhead.
     */
    private fun updateNotification(contentText: String?) {
        val service = getService() ?: return
        val currentBuilder = builder ?: return // Should have been initialized in showNotification

        currentBuilder.setContentText(contentText)
        
        val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Optimization: notify() can be expensive, but calling it on the same ID is the way to update.
        // The Android system handles the throttle internally to some extent, but we have our 2s delay.
        notificationManager.notify(NOTIFICATION_ID, currentBuilder.build())
    }

    private fun appendSpeedString(text: StringBuilder, name: String?, up: Double, down: Double) {
        val n = (name ?: "proxy").take(6).padEnd(6)
        text.append(n)
            .append("  ")
            .append(up.toLong().toSpeedString()).append("↑  ")
            .append(down.toLong().toSpeedString()).append("↓\n")
    }

    private fun getService(): Service? {
        return CoreServiceManager.serviceControl?.get()?.getService()
    }
}
