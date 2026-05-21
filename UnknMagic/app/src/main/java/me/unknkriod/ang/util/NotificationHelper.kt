package me.unknkriod.ang.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import me.unknkriod.ang.R
import me.unknkriod.ang.enums.NotificationChannelType
import java.util.concurrent.ConcurrentHashMap

/**
 * Radically optimized unified notification helper.
 *
 * OPTIMIZATIONS:
 * 1. Rate Limiting: Minimum 3 seconds between notify() calls for the same ID.
 * 2. State Caching: Skips notify() if content hasn't changed.
 * 3. Builder Caching: Reuses NotificationCompat.Builder to minimize allocations.
 * 4. Manager Caching: Single NotificationManager instance.
 */
object NotificationHelper {

    private const val MIN_UPDATE_INTERVAL_MS = 3000L

    private var cachedNotificationManager: NotificationManager? = null
    private val builderCache = ConcurrentHashMap<Int, NotificationCompat.Builder>()
    private val lastContentCache = ConcurrentHashMap<Int, String>()
    private val lastUpdateTimeCache = ConcurrentHashMap<Int, Long>()

    /**
     * Notify with rate limiting and change detection.
     */
    fun notify(
        channelType: NotificationChannelType,
        context: Context,
        title: String,
        content: String
    ) {
        val notificationId = channelType.notificationId

        // Change detection
        if (lastContentCache[notificationId] == content) return

        // Rate limiting
        val now = SystemClock.elapsedRealtime()
        val lastUpdate = lastUpdateTimeCache[notificationId] ?: 0L
        if (now - lastUpdate < MIN_UPDATE_INTERVAL_MS) return

        ensureChannelCreated(channelType, context)
        val notificationManager = getNotificationManager(context)

        val builder = builderCache.getOrPut(notificationId) {
            buildNotificationBuilder(channelType, context, title, content)
        }
        builder.setContentTitle(title)
        builder.setContentText(content)

        notificationManager.notify(notificationId, builder.build())

        lastContentCache[notificationId] = content
        lastUpdateTimeCache[notificationId] = now
    }

    /**
     * Optimized for high-frequency updates with throttling.
     */
    fun updateNotification(
        channelType: NotificationChannelType,
        context: Context,
        content: String
    ) {
        val notificationId = channelType.notificationId

        // 1. Content check (cheapest)
        if (lastContentCache[notificationId] == content) return

        // 2. Time check (throttling)
        val now = SystemClock.elapsedRealtime()
        val lastUpdate = lastUpdateTimeCache[notificationId] ?: 0L
        if (now - lastUpdate < MIN_UPDATE_INTERVAL_MS) return

        val notificationManager = getNotificationManager(context)

        val builder = builderCache.getOrPut(notificationId) {
            buildNotificationBuilder(channelType, context, "", content)
        }

        builder.setContentText(content)
        notificationManager.notify(notificationId, builder.build())

        lastContentCache[notificationId] = content
        lastUpdateTimeCache[notificationId] = now
    }

    /**
     * Start a foreground service with a notification.
     * Always bypasses rate limiting for the initial start.
     */
    fun startForeground(
        service: Service,
        channelType: NotificationChannelType,
        title: String,
        content: String
    ) {
        val notificationId = channelType.notificationId
        ensureChannelCreated(channelType, service)

        val builder = buildNotificationBuilder(channelType, service, title, content)
        builderCache[notificationId] = builder

        service.startForeground(notificationId, builder.build())

        lastContentCache[notificationId] = content
        lastUpdateTimeCache[notificationId] = SystemClock.elapsedRealtime()
    }

    fun stopForeground(service: Service) {
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    fun cancel(
        channelType: NotificationChannelType,
        context: Context
    ) {
        val id = channelType.notificationId
        getNotificationManager(context).cancel(id)
        builderCache.remove(id)
        lastContentCache.remove(id)
        lastUpdateTimeCache.remove(id)
    }

    // ====== Private helper methods ======

    private fun getNotificationManager(context: Context): NotificationManager {
        return cachedNotificationManager ?: synchronized(this) {
            cachedNotificationManager ?: (context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).also {
                cachedNotificationManager = it
            }
        }
    }

    private fun ensureChannelCreated(channelType: NotificationChannelType, context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = getNotificationManager(context)
        if (notificationManager.getNotificationChannel(channelType.channelId) != null) return

        val channel = NotificationChannel(
            channelType.channelId,
            channelType.channelName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotificationBuilder(
        channelType: NotificationChannelType,
        context: Context,
        title: String,
        content: String
    ): NotificationCompat.Builder {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channelType.channelId
        } else {
            ""
        }

        val displayTitle = title.ifEmpty { context.getString(R.string.app_name) }
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(displayTitle)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
    }
}
