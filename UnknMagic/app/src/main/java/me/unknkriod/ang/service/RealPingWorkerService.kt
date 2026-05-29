package me.unknkriod.ang.service

import android.content.Context
import me.unknkriod.ang.core.CoreConfigManager
import me.unknkriod.ang.core.CoreNativeManager
import me.unknkriod.ang.dto.RealPingEvent
import me.unknkriod.ang.handler.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val concurrency = SettingsManager.getRealPingConcurrency()
    private val dispatcher = Executors.newFixedThreadPool(concurrency).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val runningCount = AtomicInteger(0)
    private val completedCount = AtomicInteger(0)
    private val totalCount = guids.size

    fun start() {
        if (totalCount == 0) {
            onEvent(RealPingEvent.Finish("0"))
            return
        }

        val jobs = guids.map { guid ->
            scope.launch {
                runningCount.incrementAndGet()
                try {
                    val result = startRealPing(guid)
                    if (job.isActive) {
                        onEvent(RealPingEvent.Result(guid, result))
                    }
                } catch (_: Throwable) {
                    // ignore
                } finally {
                    if (job.isActive) {
                        runningCount.decrementAndGet()
                        val completed = completedCount.incrementAndGet()
                        val percentage = (completed * 100) / totalCount
                        onEvent(RealPingEvent.Progress("$percentage%"))
                    }
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                onEvent(RealPingEvent.Finish("0"))
            } catch (_: CancellationException) {
                onEvent(RealPingEvent.Finish("-1"))
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun startRealPing(guid: String): Long {
        val retFailure = -1L
        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return retFailure
        }
        return CoreNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl())
    }
}
