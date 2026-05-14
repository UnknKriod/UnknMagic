package me.unknkriod.ang.dto

import android.content.Context
import me.unknkriod.ang.enums.CoreResolvedType

/**
 * Runtime context produced by the builder and consumed by CoreConfigManager.
 */
data class CoreConfigContext(
    val context: Context,
    val guid: String,
    val selectedProfile: ProfileItem,
    val resolvedProfiles: List<ProfileItem>,
    val resolvedType: CoreResolvedType,
    val customOutboundProfiles: Map<String, ProfileItem> = emptyMap(),
)