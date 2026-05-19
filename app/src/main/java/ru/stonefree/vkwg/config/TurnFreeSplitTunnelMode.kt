package ru.stonefree.vkwg.config

enum class TurnFreeSplitTunnelMode {
    Disabled,
    ExcludeSelected,
    OnlySelected,
    ;

    companion object {
        fun fromPersisted(value: String?): TurnFreeSplitTunnelMode {
            return entries.firstOrNull { it.name == value } ?: Disabled
        }
    }
}
