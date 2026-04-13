package ru.stonefree.vkwg.turn

enum class TurnFreeServiceState {
    Idle,
    Starting,
    Establishing,
    Established,
    WireGuardStarting,
    WireGuardRunning,
    Reconnecting,
    Stopping,
    Failed,
}
