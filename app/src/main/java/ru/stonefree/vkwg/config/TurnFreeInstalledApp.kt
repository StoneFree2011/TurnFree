package ru.stonefree.vkwg.config

import android.graphics.drawable.Drawable

data class TurnFreeInstalledApp(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
) {
    val displayName: String
        get() = if (label == packageName) packageName else "$label — $packageName"

    override fun toString(): String = displayName
}
