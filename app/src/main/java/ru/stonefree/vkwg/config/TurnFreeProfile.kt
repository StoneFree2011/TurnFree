package ru.stonefree.vkwg.config

import com.wireguard.config.Config

data class TurnFreeProfile(
    val profileName: String = "",
    val peer: String = "",
    val turn: String = "",
    val listen: String = "127.0.0.1:9000",
    val turnHostOverride: String = "",
    val turnPortOverride: String = "",
    val streams: Int = 2,
    val udp: Boolean = true,
    val noDtls: Boolean = false,
    val manualCaptcha: Boolean = false,
    val hasAmneziaWg: Boolean = false,
    val wireGuardConfigText: String = "",
    val importLabel: String = "",
)

enum class TurnFreeImportFormat {
    Json,
    TurnbridgeLink,
    WireGuardConf,
}

data class TurnFreeImportResult(
    val profile: TurnFreeProfile,
    val format: TurnFreeImportFormat,
    val wireGuardConfig: Config?,
    val warning: String? = null,
)
