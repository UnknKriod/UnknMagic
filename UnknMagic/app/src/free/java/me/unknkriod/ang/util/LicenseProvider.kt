package me.unknkriod.ang.util

import android.content.Context
import android.content.Intent

object LicenseProvider {
    fun get(): LicenseBridge = object : LicenseBridge {
        override val isExtensionAvailable: Boolean = false
        override suspend fun isLicenseValid(context: Context): Boolean = false
        override suspend fun getRemoteSubscriptions(context: Context): Result<List<RemoteSubscription>> = 
            Result.failure(Exception("Not available in this version"))
        override fun getLicenseActivityIntent(context: Context): Intent? = null
    }
}
