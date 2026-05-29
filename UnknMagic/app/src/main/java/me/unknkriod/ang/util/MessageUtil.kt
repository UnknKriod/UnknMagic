package me.unknkriod.ang.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import me.unknkriod.ang.AppConfig
import me.unknkriod.ang.dto.TestServiceMessage
import me.unknkriod.ang.service.CoreTestService
import java.io.Serializable

object MessageUtil {


    /**
     * Sends a message to the service.
     *
     * @param ctx The context.
     * @param what The message identifier.
     * @param content The message content.
     */
    fun sendMsg2Service(ctx: Context, what: Int, content: Serializable) {
        sendMsg(ctx, AppConfig.BROADCAST_ACTION_SERVICE, what, content)
    }

    /**
     * Sends a message to the UI.
     *
     * @param ctx The context.
     * @param what The message identifier.
     * @param content The message content.
     */
    fun sendMsg2UI(ctx: Context, what: Int, content: Serializable) {
        sendMsg(ctx, AppConfig.BROADCAST_ACTION_ACTIVITY, what, content)
    }

    /**
     * Sends a message to the test service.
     *
     * @param ctx The context.
     * @param message The test service message containing key, subscriptionId, and serverGuids.
     */
    fun sendMsg2TestService(ctx: Context, message: TestServiceMessage) {
        try {
            val intent = Intent()
            intent.component = ComponentName(ctx, CoreTestService::class.java)
            intent.putExtra("content", message)
            
            // On Android 12+, we must be very careful with foreground services.
            // Using startForegroundService only for START.
            if (message.key == AppConfig.MSG_MEASURE_CONFIG_START) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(ctx, intent)
                } else {
                    ctx.startService(intent)
                }
            } else {
                // For CANCEL or other messages, use normal startService.
                // If the service is already in foreground, it will stay there.
                // If it's not running, it will start in background and likely stop immediately.
                ctx.startService(intent)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to send message to test service", e)
        }
    }

    /**
     * Sends a message with the specified action.
     *
     * @param ctx The context.
     * @param action The action string.
     * @param what The message identifier.
     * @param content The message content.
     */
    private fun sendMsg(ctx: Context, action: String, what: Int, content: Serializable) {
        try {
            val intent = Intent()
            intent.action = action
            intent.setPackage(ctx.packageName)
            intent.putExtra("key", what)
            intent.putExtra("content", content)
            ctx.sendBroadcast(intent)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to send message with action: $action", e)
        }
    }
}
