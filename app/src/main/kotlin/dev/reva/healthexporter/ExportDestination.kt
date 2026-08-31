package dev.reva.healthexporter

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

sealed interface DestinationStatus {
    object Ready : DestinationStatus
    data class InvalidConfiguration(val message: String, val cause: Throwable? = null) : DestinationStatus
    data class Unavailable(val message: String, val cause: Throwable? = null) : DestinationStatus
}

sealed interface UploadResult {
    data class Success(val batchId: String, val location: String? = null) : UploadResult
    data class Failure(val message: String, val isRetryable: Boolean, val cause: Throwable? = null) : UploadResult
}

interface ExportDestination {
    val destinationName: String
    suspend fun verifyConfiguration(): DestinationStatus
    suspend fun upload(batch: ExportBatch): UploadResult
}

class LocalFileDestination(
    val baseDirectory: File,
    val serializer: ExportBatchSerializer = ExportBatchSerializer(),
    val useHierarchy: Boolean = true,
) : ExportDestination {

    override val destinationName: String = "LocalFileDestination"

    override suspend fun verifyConfiguration(): DestinationStatus {
        return try {
            if (!baseDirectory.exists() && !baseDirectory.mkdirs()) {
                return DestinationStatus.InvalidConfiguration(
                    "Unable to create destination directory: ${baseDirectory.absolutePath}",
                )
            }
            if (!baseDirectory.isDirectory) {
                return DestinationStatus.InvalidConfiguration(
                    "Destination path is not a directory: ${baseDirectory.absolutePath}",
                )
            }
            if (!baseDirectory.canWrite()) {
                return DestinationStatus.InvalidConfiguration(
                    "Destination directory is not writable: ${baseDirectory.absolutePath}",
                )
            }
            DestinationStatus.Ready
        } catch (e: SecurityException) {
            DestinationStatus.InvalidConfiguration("Permission denied accessing ${baseDirectory.absolutePath}", e)
        } catch (e: Exception) {
            DestinationStatus.Unavailable("Error verifying destination directory: ${e.message}", e)
        }
    }

    override suspend fun upload(batch: ExportBatch): UploadResult {
        val targetDir = if (useHierarchy) {
            val startUtc = batch.header.timeWindow.startInclusive.atZone(ZoneOffset.UTC)
            val year = startUtc.year.toString()
            val month = String.format(Locale.ROOT, "%02d", startUtc.monthValue)
            File(baseDirectory, "$year/$month")
        } else {
            baseDirectory
        }

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return UploadResult.Failure(
                message = "Failed to create target directory: ${targetDir.absolutePath}",
                isRetryable = true,
            )
        }

        val filename = formatBatchFilename(batch.header)
        val targetFile = File(targetDir, filename)
        val tempFile = File(targetDir, "$filename.tmp.${UUID.randomUUID()}")

        return try {
            FileOutputStream(tempFile).use { outputStream ->
                outputStream.write(serializer.serializeToJson(batch).toByteArray(Charsets.UTF_8))
            }

            try {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }

            UploadResult.Success(
                batchId = batch.header.batchId,
                location = targetFile.absolutePath,
            )
        } catch (e: IOException) {
            Files.deleteIfExists(tempFile.toPath())
            UploadResult.Failure(
                message = "Failed writing batch to local file: ${e.message ?: "I/O error"}",
                isRetryable = true,
                cause = e,
            )
        } catch (e: Exception) {
            Files.deleteIfExists(tempFile.toPath())
            UploadResult.Failure(
                message = "Unexpected error writing batch to local file: ${e.message ?: "error"}",
                isRetryable = true,
                cause = e,
            )
        }
    }

    companion object {
        private val TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        fun formatBatchFilename(header: BatchHeader): String {
            val startStr = TIMESTAMP_FORMATTER.format(header.timeWindow.startInclusive)
            val endStr = TIMESTAMP_FORMATTER.format(header.timeWindow.endExclusive)
            return "$startStr--$endStr--${header.batchId}.json"
        }
    }
}
