package ru.stonefree.vkwg.config

import android.content.Context
import android.content.Intent

class TurnFreeAppInventory(context: Context) {

    private val appContext = context.applicationContext

    @Suppress("DEPRECATION")
    fun loadLaunchableApps(): List<TurnFreeInstalledApp> {
        val packageManager = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        return packageManager.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == appContext.packageName) {
                    return@mapNotNull null
                }
                val label = resolveInfo.loadLabel(packageManager)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                    .ifBlank { packageName }
                val icon = runCatching { resolveInfo.loadIcon(packageManager) }.getOrNull()
                TurnFreeInstalledApp(
                    label = label,
                    packageName = packageName,
                    icon = icon,
                )
            }
            .groupBy { it.packageName }
            .values
            .map { candidates ->
                candidates.minWithOrNull(
                    compareBy<TurnFreeInstalledApp>({ it.label.length }, { it.label.lowercase() }),
                ) ?: candidates.first()
            }
            .sortedWith(
                compareBy<TurnFreeInstalledApp>({ it.label.lowercase() }, { it.packageName.lowercase() }),
            )
            .toList()
    }
}
