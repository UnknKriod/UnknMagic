package me.unknkriod.ang.handler

import android.os.Build
import me.unknkriod.ang.BuildConfig
import me.unknkriod.ang.AppConfig
import me.unknkriod.ang.dto.CheckUpdateResult
import me.unknkriod.ang.dto.GitHubRelease
import me.unknkriod.ang.util.HttpUtil
import me.unknkriod.ang.util.JsonUtil
import me.unknkriod.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateCheckerManager {
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        val url = AppConfig.APP_API_URL

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()

        var response = HttpUtil.getUrlContent(url, 5000)
        if (response.isNullOrEmpty()) {
            val httpPort = SettingsManager.getHttpPort()
            response = HttpUtil.getUrlContent(url, 5000, httpPort, proxyUsername, proxyPassword)
                ?: throw IllegalStateException("Failed to get response")
        }

        val allReleases = JsonUtil.fromJson(response, Array<GitHubRelease>::class.java)
            ?: throw IllegalStateException("Failed to parse releases")

        // Определяем тег для поиска в названии релиза
        val flavorTag = if (BuildConfig.FLAVOR == "free") "[FREE]" else "[PREMIUM]"

        // Находим последний подходящий релиз
        val latestRelease = allReleases.firstOrNull { release ->
            val matchesFlavor = release.tagName.contains(flavorTag, ignoreCase = true) || 
                               release.body.contains(flavorTag, ignoreCase = true)
            
            if (includePreRelease) {
                matchesFlavor
            } else {
                matchesFlavor && !release.prerelease
            }
        }

        if (latestRelease == null) {
            return@withContext CheckUpdateResult(hasUpdate = false)
        }

        val latestVersion = latestRelease.tagName.removePrefix("v").split(" ")[0]
        LogUtil.i(
            AppConfig.TAG,
            "Found version for $flavorTag: $latestVersion (current: ${BuildConfig.VERSION_NAME})"
        )

        if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
            val downloadUrl = getDownloadUrl(latestRelease, Build.SUPPORTED_ABIS[0])
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                releaseNotes = latestRelease.body,
                downloadUrl = downloadUrl,
                isPreRelease = latestRelease.prerelease
            )
        } else {
            CheckUpdateResult(hasUpdate = false)
        }
    }

    private fun compareVersions(version1: String, version2: String): Int {
        val v1 = version1.split(".")
        val v2 = version2.split(".")

        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = v1.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val num2 = v2.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }

    private fun getDownloadUrl(release: GitHubRelease, abi: String): String {
        val flavor = BuildConfig.FLAVOR // "free" или "premium"

        // Сначала фильтруем по архитектуре (ABI)
        val assetsByAbi = release.assets.filter {
            it.name.contains(abi, true)
        }

        // Затем ищем APK, который содержит название нашего флавора в имени файла
        val asset = assetsByAbi.firstOrNull { it.name.contains(flavor, ignoreCase = true) }
            ?: assetsByAbi.firstOrNull() // Если по флавору не нашли, берем первый подходящий по ABI

        return asset?.browserDownloadUrl
            ?: throw IllegalStateException("No compatible APK found for $flavor ($abi)")
    }
}
