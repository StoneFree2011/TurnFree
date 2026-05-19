package ru.stonefree.vkwg.turn

import ru.stonefree.vkwg.config.TurnFreeProfile
import ru.stonefree.vkwg.config.TurnFreeSplitTunnelMode

object TurnFreeWireGuardConfigPatcher {

    fun apply(profile: TurnFreeProfile, ownPackageName: String): String {
        val normalized = profile.wireGuardConfigText.replace("\r\n", "\n").trim()
        if (normalized.isBlank()) {
            error("WireGuard-конфиг пустой")
        }

        val directives = buildApplicationDirectives(profile, ownPackageName)
        val lines = normalized.lineSequence().toList()
        if (lines.none { it.trim().equals("[Interface]", ignoreCase = true) }) {
            error("WireGuard-конфиг не содержит секцию [Interface]")
        }

        val result = mutableListOf<String>()
        var insideInterface = false
        var insertedDirectives = false

        fun appendDirectivesIfNeeded() {
            if (!insertedDirectives) {
                result += directives
                insertedDirectives = true
            }
        }

        lines.forEach { rawLine ->
            val trimmed = rawLine.trim()
            val isSectionHeader = trimmed.startsWith("[") && trimmed.endsWith("]")

            if (isSectionHeader) {
                if (insideInterface) {
                    appendDirectivesIfNeeded()
                }
                insideInterface = trimmed.equals("[Interface]", ignoreCase = true)
                result += rawLine
                return@forEach
            }

            if (insideInterface && isApplicationDirective(trimmed)) {
                return@forEach
            }

            result += rawLine
        }

        if (insideInterface) {
            appendDirectivesIfNeeded()
        }

        return result.joinToString(separator = "\n").trim()
    }

    private fun buildApplicationDirectives(profile: TurnFreeProfile, ownPackageName: String): List<String> {
        val selectedPackages = profile.splitTunnelPackages
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toMutableSet()

        return when (profile.splitTunnelMode) {
            TurnFreeSplitTunnelMode.Disabled -> listOf(
                "ExcludedApplications = $ownPackageName",
            )

            TurnFreeSplitTunnelMode.ExcludeSelected -> {
                selectedPackages += ownPackageName
                listOf("ExcludedApplications = ${selectedPackages.sorted().joinToString(separator = ", ")}")
            }

            TurnFreeSplitTunnelMode.OnlySelected -> {
                selectedPackages -= ownPackageName
                if (selectedPackages.isEmpty()) {
                    error("Для режима «Только выбранные» нужно выбрать хотя бы одно приложение")
                }
                listOf("IncludedApplications = ${selectedPackages.sorted().joinToString(separator = ", ")}")
            }
        }
    }

    private fun isApplicationDirective(line: String): Boolean {
        return line.startsWith("ExcludedApplications", ignoreCase = true) ||
            line.startsWith("IncludedApplications", ignoreCase = true)
    }
}
