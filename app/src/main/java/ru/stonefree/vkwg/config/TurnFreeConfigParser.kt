package ru.stonefree.vkwg.config

import android.net.Uri
import android.util.Base64
import com.wireguard.config.Config
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

object TurnFreeConfigParser {

    fun parse(rawInput: String): TurnFreeImportResult {
        val trimmed = rawInput.trim().trimStart('\uFEFF')

        if (trimmed.isBlank()) {
            throw IllegalArgumentException("Пустой конфиг")
        }

        return when {
            isTurnbridgeLink(trimmed) -> parseTurnbridgeLink(trimmed)
            looksLikeJson(trimmed) -> parseJson(trimmed, TurnFreeImportFormat.Json)
            looksLikeBase64Json(trimmed) -> parseBase64Json(trimmed)
            else -> parseWireGuardConf(trimmed)
        }
    }

    private fun parseTurnbridgeLink(rawInput: String): TurnFreeImportResult {
        val payload = rawInput.removePrefix("turnbridge://").trim().trimStart('/')
        val decoded = decodeBase64Flexible(normalizeBase64Payload(Uri.decode(payload)))
        return parseJson(decoded, TurnFreeImportFormat.TurnbridgeLink)
    }

    private fun parseBase64Json(rawInput: String): TurnFreeImportResult {
        val decoded = decodeBase64Flexible(normalizeBase64Payload(Uri.decode(rawInput)))
        return parseJson(decoded, TurnFreeImportFormat.Json)
    }

    private fun parseJson(jsonText: String, format: TurnFreeImportFormat): TurnFreeImportResult {
        val json = JSONObject(jsonText)
        val name = json.optString("name").trim()
        val turn = json.optString("turn").trim()
        val peer = json.optString("peer").trim()
        val listen = json.optString("listen", "127.0.0.1:9000").trim().ifBlank { "127.0.0.1:9000" }
        val turnHostOverride = optStringFromKeys(json, "turn_host", "turnHost", "turn_ip", "turnIp")
        val turnPortOverride = optStringFromKeys(json, "turn_port", "turnPort", "port")
        val streams = optIntFromKeys(json, "n", "streams")?.coerceIn(1, 12) ?: 2
        val udp = optBooleanFromKeys(json, "udp") ?: true
        val noDtls = optBooleanFromKeys(json, "no_dtls", "noDtls") ?: false
        val wireGuardConfigText = json.optString("wg").trim()
        val hasAmneziaWg = containsAmneziaWgDirectives(wireGuardConfigText)
        val splitTunnelSettings = extractSplitTunnelSettings(wireGuardConfigText)

        if (turn.isBlank() && peer.isBlank() && wireGuardConfigText.isBlank()) {
            throw IllegalArgumentException("В JSON нет полей turn, peer или wg")
        }

        val wireGuardConfig = parseWireGuardConfigOrNull(wireGuardConfigText)

        return TurnFreeImportResult(
            profile = TurnFreeProfile(
                profileName = name,
                peer = peer,
                turn = turn,
                listen = listen,
                turnHostOverride = turnHostOverride,
                turnPortOverride = turnPortOverride,
                streams = streams,
                udp = udp,
                noDtls = noDtls,
                hasAmneziaWg = hasAmneziaWg,
                wireGuardConfigText = wireGuardConfigText,
                splitTunnelMode = splitTunnelSettings.mode,
                splitTunnelPackages = splitTunnelSettings.packages,
            ),
            format = format,
            wireGuardConfig = wireGuardConfig,
            warning = combineWarnings(
                hasAmneziaWg.takeIf { it }?.let {
                    "Обнаружен Amnezia WG-конфиг (Jc/Jmin/Jmax/S/H/I). В текущей версии поддерживается только классический WireGuard."
                },
                wireGuardConfig?.let { null }
                    ?: wireGuardConfigText.takeIf { it.isNotBlank() }?.let {
                        "WireGuard-конфиг не удалось разобрать, но поля TURN сохранены"
                    },
            ),
        )
    }

    private fun parseWireGuardConf(confText: String): TurnFreeImportResult {
        val extracted = extractVkTurnBlockAndCleanWireGuardConf(confText)
        val wireGuardConfig = parseWireGuardConfigOrNull(extracted.cleanedWireGuardText)

        if (extracted.peer.isBlank() && extracted.turn.isBlank() && extracted.cleanedWireGuardText.isBlank()) {
            throw IllegalArgumentException("Не найдено ни одного понятного блока")
        }

        return TurnFreeImportResult(
            profile = TurnFreeProfile(
                profileName = extracted.name,
                peer = extracted.peer,
                turn = extracted.turn,
                listen = extracted.listen.ifBlank { "127.0.0.1:9000" },
                turnHostOverride = extracted.turnHostOverride,
                turnPortOverride = extracted.turnPortOverride,
                streams = extracted.streams,
                udp = extracted.udp,
                noDtls = extracted.noDtls,
                hasAmneziaWg = extracted.hasAmneziaWg,
                wireGuardConfigText = extracted.cleanedWireGuardText,
                splitTunnelMode = extracted.splitTunnelMode,
                splitTunnelPackages = extracted.splitTunnelPackages,
            ),
            format = TurnFreeImportFormat.WireGuardConf,
            wireGuardConfig = wireGuardConfig,
            warning = combineWarnings(
                extracted.hasAmneziaWg.takeIf { it }?.let {
                    "Обнаружен Amnezia WG-конфиг (Jc/Jmin/Jmax/S/H/I). В текущей версии поддерживается только классический WireGuard."
                },
                wireGuardConfig?.let { null }
                    ?: extracted.cleanedWireGuardText.takeIf { it.isNotBlank() }?.let { "WireGuard-конфиг не удалось разобрать" },
            ),
        )
    }

    private fun extractVkTurnBlockAndCleanWireGuardConf(confText: String): ExtractedConf {
        val cleanedLines = mutableListOf<String>()
        val collected = mutableMapOf<String, String>()
        var insideVkTurn = false

        confText.lineSequence().forEach { rawLine ->
            val trimmedLine = rawLine.trim()
            val sectionName = trimmedLine.takeIf { it.startsWith("[") && it.endsWith("]") }

            when {
                sectionName?.equals("[VkTurn]", ignoreCase = true) == true -> {
                    insideVkTurn = true
                }

                sectionName != null -> {
                    insideVkTurn = false
                    cleanedLines += rawLine
                }

                insideVkTurn -> {
                    parseKeyValueLine(rawLine)?.let { (key, value) ->
                        collected[key.lowercase(Locale.US)] = value
                    }
                }

                else -> {
                    cleanedLines += rawLine
                }
            }
        }

        val cleanedWireGuardText = cleanedLines.joinToString(separator = "\n").trim()
        val splitTunnelSettings = extractSplitTunnelSettings(cleanedWireGuardText)

        return ExtractedConf(
            name = collected["name"].orEmpty(),
            peer = collected["peer"].orEmpty(),
            turn = collected["turn"].orEmpty(),
            listen = collected["listen"].orEmpty(),
            turnHostOverride = collected["turn_host"].orEmpty().ifBlank {
                collected["turnhost"].orEmpty().ifBlank {
                    collected["turn-host"].orEmpty().ifBlank {
                        collected["turn_ip"].orEmpty().ifBlank { collected["turnip"].orEmpty() }
                    }
                }
            },
            turnPortOverride = collected["turn_port"].orEmpty().ifBlank {
                collected["turnport"].orEmpty().ifBlank {
                    collected["turn-port"].orEmpty().ifBlank { collected["port"].orEmpty() }
                }
            },
            streams = (
                collected["streams"]?.toIntOrNull()
                    ?: collected["n"]?.toIntOrNull()
                )?.coerceIn(1, 12) ?: 2,
            udp = collected["udp"]?.toBooleanLoose() ?: true,
            noDtls = collected["no_dtls"]?.toBooleanLoose()
                ?: collected["nodtls"]?.toBooleanLoose()
                ?: collected["no-dtls"]?.toBooleanLoose()
                ?: false,
            hasAmneziaWg = containsAmneziaWgDirectives(cleanedWireGuardText),
            cleanedWireGuardText = cleanedWireGuardText,
            splitTunnelMode = splitTunnelSettings.mode,
            splitTunnelPackages = splitTunnelSettings.packages,
        )
    }

    private fun optStringFromKeys(json: JSONObject, vararg keys: String): String {
        return keys.firstNotNullOfOrNull { key ->
            val value = json.opt(key)
            when (value) {
                null,
                JSONObject.NULL -> null
                else -> value.toString().trim().takeIf { it.isNotBlank() }
            }
        }.orEmpty()
    }

    private fun optIntFromKeys(json: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            val value = json.opt(key)
            when (value) {
                is Number -> return value.toInt()
                is String -> value.trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun optBooleanFromKeys(json: JSONObject, vararg keys: String): Boolean? {
        for (key in keys) {
            val value = json.opt(key)
            when (value) {
                is Boolean -> return value
                is Number -> return value.toInt() != 0
                is String -> when (value.trim().lowercase(Locale.US)) {
                    "1", "true", "yes", "on" -> return true
                    "0", "false", "no", "off" -> return false
                }
            }
        }
        return null
    }

    private fun parseWireGuardConfigOrNull(text: String): Config? {
        if (text.isBlank()) {
            return null
        }

        return runCatching {
            Config.parse(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)))
        }.getOrNull()
    }

    private fun isTurnbridgeLink(text: String): Boolean {
        return text.startsWith("turnbridge://", ignoreCase = true)
    }

    private fun looksLikeJson(text: String): Boolean {
        return text.startsWith("{") && text.endsWith("}")
    }

    private fun looksLikeBase64Json(text: String): Boolean {
        val normalized = normalizeBase64Payload(text)
        if (normalized.isBlank()) {
            return false
        }

        val base64ish = normalized.all { char ->
            char.isLetterOrDigit() || char == '+' || char == '/' || char == '=' || char == '-' || char == '_'
        }
        if (!base64ish) {
            return false
        }

        val decoded = runCatching { decodeBase64Flexible(Uri.decode(normalized)) }.getOrNull() ?: return false
        return looksLikeJson(decoded.trim())
    }

    private fun normalizeBase64Payload(text: String): String {
        return text.filterNot(Char::isWhitespace)
    }

    private fun decodeBase64Flexible(payload: String): String {
        val candidates = buildList {
            add(payload)
            add(payload.replace('-', '+').replace('_', '/'))
        }

        val flags = listOf(Base64.DEFAULT, Base64.NO_WRAP, Base64.URL_SAFE, Base64.URL_SAFE or Base64.NO_WRAP)

        for (candidate in candidates) {
            val normalized = candidate.padEnd(candidate.length + (4 - candidate.length % 4) % 4, '=')
            for (flag in flags) {
                val decoded = runCatching { Base64.decode(normalized, flag) }.getOrNull()
                if (decoded != null) {
                    return String(decoded, StandardCharsets.UTF_8)
                }
            }
        }

        throw IllegalArgumentException("Не удалось декодировать turnbridge:// ссылку")
    }

    private fun parseKeyValueLine(rawLine: String): Pair<String, String>? {
        val sanitized = rawLine
            .substringBefore('#')
            .substringBefore(';')
            .trim()

        if (sanitized.isBlank() || !sanitized.contains("=")) {
            return null
        }

        val parts = sanitized.split("=", limit = 2)
        if (parts.size != 2) {
            return null
        }

        return parts[0].trim() to parts[1].trim()
    }

    private fun String.toBooleanLoose(): Boolean {
        return when (lowercase(Locale.US)) {
            "1", "true", "yes", "on" -> true
            else -> false
        }
    }

    private fun containsAmneziaWgDirectives(configText: String): Boolean {
        if (configText.isBlank()) return false
        return configText.lineSequence()
            .map { it.substringBefore('#').substringBefore(';').trim() }
            .filter { it.isNotBlank() && it.contains("=") }
            .map { it.substringBefore('=').trim().lowercase(Locale.US) }
            .any { key ->
                key == "jc" ||
                    key == "jmin" ||
                    key == "jmax" ||
                    key == "s1" || key == "s2" || key == "s3" || key == "s4" ||
                    key == "h1" || key == "h2" || key == "h3" || key == "h4" ||
                    key == "i1" || key == "i2" || key == "i3" || key == "i4" || key == "i5"
            }
    }

    private fun combineWarnings(vararg warnings: String?): String? {
        val text = warnings.filter { !it.isNullOrBlank() }.joinToString(separator = " ")
        return text.ifBlank { null }
    }

    private fun extractSplitTunnelSettings(configText: String): SplitTunnelSettings {
        if (configText.isBlank()) {
            return SplitTunnelSettings()
        }

        var insideInterface = false
        var includedPackages: Set<String>? = null
        var excludedPackages: Set<String>? = null

        configText.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                insideInterface = trimmed.equals("[Interface]", ignoreCase = true)
                return@forEach
            }
            if (!insideInterface) {
                return@forEach
            }

            val (key, value) = parseKeyValueLine(rawLine) ?: return@forEach
            when (key.lowercase(Locale.US)) {
                "includedapplications" -> includedPackages = parsePackageList(value)
                "excludedapplications" -> excludedPackages = parsePackageList(value)
            }
        }

        if (!includedPackages.isNullOrEmpty()) {
            return SplitTunnelSettings(
                mode = TurnFreeSplitTunnelMode.OnlySelected,
                packages = includedPackages.orEmpty(),
            )
        }

        val selectedExcludedPackages = excludedPackages
            .orEmpty()
            .filterNot { it == TURNFREE_PACKAGE_NAME }
            .toSet()
        if (selectedExcludedPackages.isNotEmpty()) {
            return SplitTunnelSettings(
                mode = TurnFreeSplitTunnelMode.ExcludeSelected,
                packages = selectedExcludedPackages,
            )
        }

        return SplitTunnelSettings()
    }

    private fun parsePackageList(value: String): Set<String> {
        return value.split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
    }

    private data class ExtractedConf(
        val name: String,
        val peer: String,
        val turn: String,
        val listen: String,
        val turnHostOverride: String,
        val turnPortOverride: String,
        val streams: Int,
        val udp: Boolean,
        val noDtls: Boolean,
        val hasAmneziaWg: Boolean,
        val cleanedWireGuardText: String,
        val splitTunnelMode: TurnFreeSplitTunnelMode,
        val splitTunnelPackages: Set<String>,
    )

    private data class SplitTunnelSettings(
        val mode: TurnFreeSplitTunnelMode = TurnFreeSplitTunnelMode.Disabled,
        val packages: Set<String> = emptySet(),
    )

    private const val TURNFREE_PACKAGE_NAME = "ru.stonefree.vkwg"
}
