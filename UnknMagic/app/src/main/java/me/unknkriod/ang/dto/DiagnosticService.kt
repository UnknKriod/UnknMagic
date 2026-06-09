package me.unknkriod.ang.dto

import com.google.gson.annotations.SerializedName

data class DiagnosticService(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("urls") val urls: List<String>,
    @SerializedName("priority") val priority: Int
)
