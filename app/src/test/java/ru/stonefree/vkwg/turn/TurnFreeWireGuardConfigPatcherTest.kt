package ru.stonefree.vkwg.turn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stonefree.vkwg.config.TurnFreeProfile
import ru.stonefree.vkwg.config.TurnFreeSplitTunnelMode

class TurnFreeWireGuardConfigPatcherTest {

    private val baseConfig = """
        [Interface]
        PrivateKey = abc
        Address = 10.0.0.2/32

        [Peer]
        PublicKey = def
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    @Test
    fun `disabled mode keeps self excluded`() {
        val patched = TurnFreeWireGuardConfigPatcher.apply(
            profile = TurnFreeProfile(wireGuardConfigText = baseConfig),
            ownPackageName = "ru.stonefree.vkwg",
        )

        assertTrue(patched.contains("ExcludedApplications = ru.stonefree.vkwg"))
        assertFalse(patched.contains("IncludedApplications"))
    }

    @Test
    fun `exclude mode merges selected packages with app package`() {
        val patched = TurnFreeWireGuardConfigPatcher.apply(
            profile = TurnFreeProfile(
                wireGuardConfigText = baseConfig,
                splitTunnelMode = TurnFreeSplitTunnelMode.ExcludeSelected,
                splitTunnelPackages = setOf("org.telegram.messenger", "com.android.chrome"),
            ),
            ownPackageName = "ru.stonefree.vkwg",
        )

        assertTrue(patched.contains("ExcludedApplications = com.android.chrome, org.telegram.messenger, ru.stonefree.vkwg"))
    }

    @Test
    fun `include-only mode writes included applications without self exclusion`() {
        val patched = TurnFreeWireGuardConfigPatcher.apply(
            profile = TurnFreeProfile(
                wireGuardConfigText = baseConfig,
                splitTunnelMode = TurnFreeSplitTunnelMode.OnlySelected,
                splitTunnelPackages = setOf("org.telegram.messenger", "com.android.chrome", "ru.stonefree.vkwg"),
            ),
            ownPackageName = "ru.stonefree.vkwg",
        )

        assertTrue(patched.contains("IncludedApplications = com.android.chrome, org.telegram.messenger"))
        assertFalse(patched.contains("ExcludedApplications = ru.stonefree.vkwg"))
    }
}
