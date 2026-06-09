package me.unknkriod.ang.handler

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import me.unknkriod.ang.dto.DiagnosticService
import java.io.InputStreamReader

object DiagnosticsManager {
    private const val ASSET_NAME = "diagnostics_services.json"
    private var services: List<DiagnosticService>? = null

    fun getServices(context: Context): List<DiagnosticService> {
        if (services == null) {
            services = try {
                val inputStream = context.assets.open(ASSET_NAME)
                val reader = InputStreamReader(inputStream)
                val type = object : TypeToken<List<DiagnosticService>>() {}.type
                Gson().fromJson<List<DiagnosticService>>(reader, type).sortedBy { it.priority }
            } catch (e: Exception) {
                emptyList()
            }
        }
        return services ?: emptyList()
    }
}
