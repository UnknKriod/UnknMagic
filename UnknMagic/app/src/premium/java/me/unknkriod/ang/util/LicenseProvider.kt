package me.unknkriod.ang.util

import android.content.Context
import android.content.Intent
import me.unknkriod.licensechecker.manager.LicenseManager
import me.unknkriod.licensechecker.extension.subscription.SubscriptionManager
import me.unknkriod.licensechecker.data.SubscriptionResponse

object LicenseProvider {
    fun get(): LicenseBridge = object : LicenseBridge {
        override val isExtensionAvailable: Boolean = true

        override suspend fun isLicenseValid(context: Context): Boolean {
            return try {
                LicenseManager.getInstance(context).isLicenseValidLocally()
            } catch (e: Exception) {
                false
            }
        }

        override suspend fun getRemoteSubscriptions(context: Context): Result<List<RemoteSubscription>> {
            return try {
                val manager = SubscriptionManager(context)
                val result = manager.getRemoteSubscriptions()
                result.map { subs ->
                    subs.map { RemoteSubscription(it.remarks, it.url) }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        override fun getLicenseActivityIntent(context: Context): Intent? {
            return try {
                Intent(context, me.unknkriod.licensechecker.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
