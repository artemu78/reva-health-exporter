package dev.reva.healthexporter

import java.util.concurrent.CancellationException

sealed interface HistoryRefreshResult {
    data class Success(val entries: List<ExportHistoryEntry>) : HistoryRefreshResult
    data class Unknown(val reason: String) : HistoryRefreshResult
}

class DriveHistoryInventoryRefresher(
    private val gateway: GoogleDriveGateway,
    private val historyStore: ExportHistoryStore,
    private val installationId: String,
    private val destinationKey: String,
) {
    suspend fun refresh(): HistoryRefreshResult {
        return try {
            gateway.verifyAccess()
            val files = gateway.findFiles(appProperties = mapOf("installationId" to installationId))
            val parsed = files.map { parseDriveHistoryEntry(it.appProperties, destinationKey) }
            if (parsed.any { it == null }) {
                HistoryRefreshResult.Unknown("App-created Drive metadata is incomplete.")
            } else {
                val entries = parsed.filterNotNull()
                historyStore.replaceConfirmed(destinationKey, entries)
                HistoryRefreshResult.Success(entries)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: GoogleDriveException.AuthorizationException) {
            HistoryRefreshResult.Unknown("Google Drive authorization is required.")
        } catch (_: Exception) {
            HistoryRefreshResult.Unknown("Google Drive inventory could not be refreshed.")
        }
    }
}
