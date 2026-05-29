package me.unknkriod.ang.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import me.unknkriod.ang.AppConfig
import me.unknkriod.ang.R
import me.unknkriod.ang.core.CoreNativeManager
import me.unknkriod.ang.dto.RealPingEvent
import me.unknkriod.ang.dto.TestServiceMessage
import me.unknkriod.ang.enums.NotificationChannelType
import me.unknkriod.ang.extension.serializable
import me.unknkriod.ang.handler.MmkvManager
import me.unknkriod.ang.util.LogUtil
import me.unknkriod.ang.util.MessageUtil
import me.unknkriod.ang.util.NotificationHelper
import java.util.Collections

class CoreTestService : Service() {

    // manage active batch workers so each batch is independent and cancellable
    private val activeWorkers = Collections.synchronizedList(mutableListOf<RealPingWorkerService>())
    private var isFinishSent = false

    /**
     * Initializes the V2Ray environment.
     */
    override fun onCreate() {
        super.onCreate()
        CoreNativeManager.initCoreEnv(this)
    }

    /**
     * Binds the service.
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Cleans up resources when the service is destroyed.
     */
    override fun onDestroy() {
        LogUtil.i(AppConfig.TAG, "CoreTestService is being destroyed, cancelling ${activeWorkers.size} active workers")
        // cancel any active workers
        val snapshot = ArrayList(activeWorkers)
        snapshot.forEach { it.cancel() }
        activeWorkers.clear()

        if (!isFinishSent) {
            isFinishSent = true
            MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_FINISH, "STOPPED")
        }

        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.serializable<TestServiceMessage>("content")
        
        LogUtil.i(AppConfig.TAG, "CoreTestService onStartCommand key=${message?.key}")

        if (message == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (message.key) {
            AppConfig.MSG_MEASURE_CONFIG_START -> {
                isFinishSent = false
                handleMeasureStart(message, startId)
            }
            AppConfig.MSG_MEASURE_CONFIG_CANCEL -> handleMeasureCancel(startId)
            else -> {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun handleMeasureStart(message: TestServiceMessage, startId: Int) {
        LogUtil.i(AppConfig.TAG, "CoreTestService handleMeasureStart subscription ${message.subscriptionId}")

        // Always call startForeground first to satisfy Android 8+ requirement
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.CORE_TEST,
            getString(R.string.app_name),
            getString(R.string.title_real_ping_all_server)
        )

        val guidsList = when {
            message.serverGuids.isNotEmpty() -> message.serverGuids
            message.subscriptionId.isNotEmpty() -> MmkvManager.decodeServerList(message.subscriptionId)
            else -> MmkvManager.decodeAllServerList()
        }

        if (guidsList.isNotEmpty()) {
            lateinit var worker: RealPingWorkerService
            worker = RealPingWorkerService(
                context = this,
                guids = guidsList,
                onEvent = { event -> handleWorkerEvent(event) { activeWorkers.remove(worker) } }
            )
            activeWorkers.add(worker)
            worker.start()
        } else {
            LogUtil.w(AppConfig.TAG, "CoreTestService: guidsList is empty, finishing")
            if (!isFinishSent) {
                isFinishSent = true
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_FINISH, "EMPTY")
            }
            // Do not stop immediately, let the system process startForeground
            // A small delay or just relying on stopSelf(startId) is safer.
            stopSelf(startId)
        }
    }

    private fun handleWorkerEvent(event: RealPingEvent, onWorkerDone: () -> Unit) {
        when (event) {
            is RealPingEvent.Progress -> {
                val progressText = getString(R.string.connection_runing_task_left, event.text)
                NotificationHelper.updateNotification(
                    channelType = NotificationChannelType.CORE_TEST,
                    context = this,
                    content = progressText
                )
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, progressText)
            }

            is RealPingEvent.Result -> {
                MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_SUCCESS, event.guid)
            }

            is RealPingEvent.Finish -> {
                if (!isFinishSent) {
                    isFinishSent = true
                    MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_FINISH, event.status)
                }
                onWorkerDone()
                if (activeWorkers.isEmpty()) {
                    NotificationHelper.stopForeground(this)
                    stopSelf()
                }
            }
        }
    }

    private fun handleMeasureCancel(startId: Int) {
        LogUtil.i(AppConfig.TAG, "CoreTestService received cancel message, cancelling ${activeWorkers.size} active workers")
        
        // Stop all workers first
        val snapshot = ArrayList(activeWorkers)
        snapshot.forEach { it.cancel() }
        activeWorkers.clear()

        // Send finish message if not sent yet
        if (!isFinishSent) {
            isFinishSent = true
            MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_FINISH, "STOPPED")
        }

        // Clean up notification and stop service
        NotificationHelper.stopForeground(this)
        stopSelf(startId)
    }
}