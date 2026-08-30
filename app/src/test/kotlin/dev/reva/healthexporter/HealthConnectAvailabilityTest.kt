package dev.reva.healthexporter

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class HealthConnectAvailabilityTest(
    private val sdkStatus: Int,
    private val expected: ProviderAvailability,
) {
    @Test
    fun `classifies provider availability`() {
        assertEquals(expected, classifyProviderAvailability(sdkStatus))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "SDK status {0} is {1}")
        fun states() = listOf(
            arrayOf(ProviderSdkStatus.AVAILABLE, ProviderAvailability.AVAILABLE),
            arrayOf(ProviderSdkStatus.UNAVAILABLE, ProviderAvailability.UNAVAILABLE),
            arrayOf(ProviderSdkStatus.UPDATE_REQUIRED, ProviderAvailability.UPDATE_REQUIRED),
        )
    }
}
