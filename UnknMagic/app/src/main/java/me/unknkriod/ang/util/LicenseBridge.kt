package me.unknkriod.ang.util

import android.content.Context
import android.content.Intent

interface LicenseBridge {
    val isExtensionAvailable: Boolean
    suspend fun isLicenseValid(context: Context): Boolean
    suspend fun getRemoteSubscriptions(context: Context): Result<List<RemoteSubscription>>
    fun getLicenseActivityIntent(context: Context): Intent?
}

data class RemoteSubscription(
    val remarks: String,
    val url: String
)
