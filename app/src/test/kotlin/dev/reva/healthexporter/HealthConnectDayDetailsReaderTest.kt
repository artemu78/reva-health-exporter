package dev.reva.healthexporter

import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.testing.FakeHealthConnectClient
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HealthConnectDayDetailsReaderTest {
    @Test
    fun readsEveryPageFromEveryOriginAndChecksPermissions() = runBlocking {
        val client = FakeHealthConnectClient()
        val window = localDayWindow(LocalDate.parse("2026-09-20"), ZoneOffset.UTC)
        listOf("com.xiaomi.wearable", "other.app").forEach { origin ->
            client.setPackageName(origin)
            client.insertRecords((0..2).map { index ->
                StepsRecord(window.startInclusive.plusSeconds(index * 60L), null,
                    window.startInclusive.plusSeconds(index * 60L + 30), null, 100,
                    Metadata.manualEntry(clientRecordId = "steps-$index"))
            })
        }
        val reader = HealthConnectDayDetailsReader(client, HealthConnectConfiguration.readPermissions, pageSize = 2)
        val records = reader.read(DetailType.STEPS, window)
        assertEquals(6, records.size)
        assertEquals(setOf("com.xiaomi.wearable", "other.app"), records.map { it.metadata.origin }.toSet())
        assertThrows(SecurityException::class.java) {
            runBlocking { HealthConnectDayDetailsReader(client, emptySet()).read(DetailType.STEPS, window) }
        }
        Unit
    }
    @Test
    fun repeatedTokenAndLateFailureNeverReturnPartialResultsAsComplete() = runBlocking {
        val client = FakeHealthConnectClient()
        val window = localDayWindow(LocalDate.parse("2026-09-20"), ZoneOffset.UTC)
        client.overrides.readRecords = androidx.health.connect.client.testing.stubs.Stub {
            androidx.health.connect.client.response.ReadRecordsResponse<androidx.health.connect.client.records.Record>(emptyList(), "same-token")
        }
        val reader = HealthConnectDayDetailsReader(client, HealthConnectConfiguration.readPermissions)
        assertThrows(IllegalStateException::class.java) { runBlocking { reader.read(DetailType.STEPS, window) } }
        var calls = 0
        client.overrides.readRecords = androidx.health.connect.client.testing.stubs.Stub {
            if (++calls == 2) throw java.io.IOException("page failure")
            androidx.health.connect.client.response.ReadRecordsResponse<androidx.health.connect.client.records.Record>(emptyList(), "next")
        }
        val day = DayDetailsLoader(reader, InMemoryEmaEventStore()).load(LocalDate.parse("2026-09-20"), ZoneOffset.UTC)
        assertEquals("Read failed", day.groups.single { it.type == DetailType.SLEEP }.summary)
        Unit
    }
}
