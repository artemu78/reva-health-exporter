package dev.reva.healthexporter

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GoogleDriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val parents: List<String> = emptyList(),
    val appProperties: Map<String, String> = emptyMap(),
    val createdTime: Instant = Instant.now(),
    val sizeBytes: Long? = null,
)

sealed class GoogleDriveException(
    override val message: String,
    override val cause: Throwable? = null,
    val isRetryable: Boolean,
) : Exception(message, cause) {
    class AuthorizationException(message: String, cause: Throwable? = null) :
        GoogleDriveException(message, cause, isRetryable = false)

    class ForbiddenException(message: String, cause: Throwable? = null) :
        GoogleDriveException(message, cause, isRetryable = false)

    class RateLimitException(message: String, cause: Throwable? = null) :
        GoogleDriveException(message, cause, isRetryable = true)

    class TransientServerException(message: String, cause: Throwable? = null) :
        GoogleDriveException(message, cause, isRetryable = true)

    class TimeoutException(message: String, cause: Throwable? = null) :
        GoogleDriveException(message, cause, isRetryable = true)

    class GeneralDriveException(message: String, isRetryable: Boolean = true, cause: Throwable? = null) :
        GoogleDriveException(message, cause, isRetryable)
}

interface GoogleDriveGateway {
    val accountId: String?

    suspend fun verifyAccess()

    suspend fun findFolders(name: String, parentId: String? = null): List<GoogleDriveFile>

    suspend fun createFolder(name: String, parentId: String? = null): GoogleDriveFile

    suspend fun findFiles(
        parentFolderId: String? = null,
        name: String? = null,
        appProperties: Map<String, String> = emptyMap(),
    ): List<GoogleDriveFile>

    suspend fun uploadFile(
        name: String,
        mimeType: String,
        parentFolderId: String,
        appProperties: Map<String, String>,
        content: ByteArray,
    ): GoogleDriveFile

    suspend fun updateFile(
        fileId: String,
        name: String,
        mimeType: String,
        appProperties: Map<String, String>,
        content: ByteArray,
    ): GoogleDriveFile

    suspend fun downloadFile(fileId: String): ByteArray
}

suspend fun GoogleDriveGateway.ensureFolderHierarchy(path: List<String>): String {
    require(path.isNotEmpty()) { "Folder hierarchy path must not be empty" }
    var currentParentId: String? = null
    for (folderName in path) {
        val existing = findFolders(name = folderName, parentId = currentParentId)
        val folder = if (existing.isNotEmpty()) {
            existing.minWithOrNull(
                compareBy<GoogleDriveFile>({ it.createdTime }, { it.id }),
            ) ?: existing.first()
        } else {
            createFolder(name = folderName, parentId = currentParentId)
        }
        currentParentId = folder.id
    }
    return checkNotNull(currentParentId)
}

suspend fun GoogleDriveGateway.findExistingBatch(
    parentFolderId: String,
    batchId: String,
    filename: String,
): GoogleDriveFile? {
    val byAppProp = findFiles(
        parentFolderId = parentFolderId,
        appProperties = mapOf("batchId" to batchId),
    )
    if (byAppProp.isNotEmpty()) {
        return byAppProp.first()
    }
    val byName = findFiles(
        parentFolderId = parentFolderId,
        name = filename,
    )
    return byName.firstOrNull()
}

class GoogleDriveDestination(
    val driveGateway: GoogleDriveGateway,
    val serializer: ExportBatchSerializer = ExportBatchSerializer(),
    val rootFolderName: String = DEFAULT_ROOT_FOLDER_NAME,
    val schemaFolderName: String = DEFAULT_SCHEMA_FOLDER_NAME,
) : ExportDestination {

    override val destinationName: String = "GoogleDriveDestination"

    override suspend fun verifyConfiguration(): DestinationStatus {
        return try {
            driveGateway.verifyAccess()
            DestinationStatus.Ready
        } catch (e: GoogleDriveException.AuthorizationException) {
            DestinationStatus.InvalidConfiguration("Google Drive authorization required: ${e.message}", e)
        } catch (e: GoogleDriveException.ForbiddenException) {
            DestinationStatus.InvalidConfiguration("Google Drive access forbidden: ${e.message}", e)
        } catch (e: GoogleDriveException) {
            DestinationStatus.Unavailable("Google Drive unavailable: ${e.message}", e)
        } catch (e: Exception) {
            DestinationStatus.Unavailable("Error verifying Google Drive: ${e.message}", e)
        }
    }

    override suspend fun upload(batch: ExportBatch): UploadResult {
        return try {
            val startUtc = batch.header.timeWindow.startInclusive.atZone(ZoneOffset.UTC)
            val year = startUtc.year.toString()
            val month = String.format(Locale.ROOT, "%02d", startUtc.monthValue)

            val targetFolderId = driveGateway.ensureFolderHierarchy(
                listOf(rootFolderName, schemaFolderName, year, month),
            )

            val filename = LocalFileDestination.formatBatchFilename(batch.header)

            val existingFile = driveGateway.findExistingBatch(
                parentFolderId = targetFolderId,
                batchId = batch.header.batchId,
                filename = filename,
            )

            val jsonBytes = serializer.serializeToJson(batch).toByteArray(Charsets.UTF_8)
            val appProperties = mapOf(
                "batchId" to batch.header.batchId,
                "installationId" to batch.header.installationId,
                "schemaVersion" to batch.header.schemaVersion.toString(),
                "windowStart" to batch.header.timeWindow.startInclusive.toString(),
                "windowEnd" to batch.header.timeWindow.endExclusive.toString(),
                "historyStatus" to HistoryBatchStatus.CONFIRMED.name,
                "historyUpdatedAt" to batch.header.createdAt.toString(),
            )

            if (existingFile != null) {
                val updatedFile = driveGateway.updateFile(
                    fileId = existingFile.id,
                    name = filename,
                    mimeType = "application/json",
                    appProperties = appProperties,
                    content = jsonBytes,
                )
                return UploadResult.Success(
                    batchId = batch.header.batchId,
                    location = updatedFile.id,
                )
            }

            val uploadedFile = driveGateway.uploadFile(
                name = filename,
                mimeType = "application/json",
                parentFolderId = targetFolderId,
                appProperties = appProperties,
                content = jsonBytes,
            )

            UploadResult.Success(
                batchId = batch.header.batchId,
                location = uploadedFile.id,
            )
        } catch (e: GoogleDriveException) {
            UploadResult.Failure(
                message = "Google Drive upload failed: ${e.message}",
                isRetryable = e.isRetryable,
                cause = e,
            )
        } catch (e: Exception) {
            UploadResult.Failure(
                message = "Unexpected error during Google Drive upload: ${e.message}",
                isRetryable = true,
                cause = e,
            )
        }
    }

    companion object {
        const val DEFAULT_ROOT_FOLDER_NAME = "Reva Health Exporter"
        const val DEFAULT_SCHEMA_FOLDER_NAME = "schema-v1"
    }
}

fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    val bodyAsString: String get() = String(body, Charsets.UTF_8)
}

class DefaultHttpTransport(
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val url = URI(request.url).toURL()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doInput = true
            request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (request.body != null) {
                doOutput = true
            }
        }

        try {
            if (request.body != null) {
                connection.outputStream.use { it.write(request.body) }
            }
            val statusCode = connection.responseCode
            val responseStream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
            }
            val responseBody = responseStream.use { it.readBytes() }
            val headerFields = connection.headerFields ?: emptyMap()
            HttpResponse(statusCode = statusCode, headers = headerFields, body = responseBody)
        } catch (e: SocketTimeoutException) {
            throw GoogleDriveException.TimeoutException("Connection timed out: ${e.message}", e)
        } catch (e: IOException) {
            throw GoogleDriveException.TimeoutException("I/O error communicating with Google Drive: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }
}

class HttpGoogleDriveGateway(
    override val accountId: String? = null,
    private val tokenProvider: suspend () -> String?,
    private val transport: HttpTransport = DefaultHttpTransport(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val uploadBaseUrl: String = DEFAULT_UPLOAD_BASE_URL,
) : GoogleDriveGateway {

    private val gson = Gson()

    override suspend fun verifyAccess() {
        val token = getValidToken()
        val request = HttpRequest(
            method = "GET",
            url = "$baseUrl/files?pageSize=1&spaces=drive&fields=files(id)",
            headers = mapOf("Authorization" to "Bearer $token"),
        )
        val response = transport.execute(request)
        handlePotentialError(response)
    }

    override suspend fun findFolders(name: String, parentId: String?): List<GoogleDriveFile> {
        val parentClause = if (parentId != null) "'$parentId' in parents" else "'root' in parents"
        val escapedName = name.replace("'", "\\'")
        val query = "mimeType = 'application/vnd.google-apps.folder' and trashed = false and name = '$escapedName' and $parentClause"
        return queryFiles(query)
    }

    override suspend fun createFolder(name: String, parentId: String?): GoogleDriveFile {
        val token = getValidToken()
        val metadata = JsonObject().apply {
            addProperty("name", name)
            addProperty("mimeType", "application/vnd.google-apps.folder")
            val parentsArray = com.google.gson.JsonArray().apply {
                add(parentId ?: "root")
            }
            add("parents", parentsArray)
        }

        val request = HttpRequest(
            method = "POST",
            url = "$baseUrl/files?fields=id,name,mimeType,parents,appProperties,createdTime",
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "application/json; charset=UTF-8",
            ),
            body = gson.toJson(metadata).toByteArray(Charsets.UTF_8),
        )

        val response = transport.execute(request)
        handlePotentialError(response)
        return parseDriveFile(response.bodyAsString)
    }

    override suspend fun findFiles(
        parentFolderId: String?,
        name: String?,
        appProperties: Map<String, String>,
    ): List<GoogleDriveFile> {
        val clauses = mutableListOf("trashed = false")
        if (parentFolderId != null) {
            clauses.add("'$parentFolderId' in parents")
        }
        if (name != null) {
            val escapedName = name.replace("'", "\\'")
            clauses.add("name = '$escapedName'")
        }
        for ((key, value) in appProperties) {
            val escapedKey = key.replace("'", "\\'")
            val escapedValue = value.replace("'", "\\'")
            clauses.add("appProperties has { key='$escapedKey' and value='$escapedValue' }")
        }
        val query = clauses.joinToString(separator = " and ")
        return queryFiles(query)
    }

    override suspend fun uploadFile(
        name: String,
        mimeType: String,
        parentFolderId: String,
        appProperties: Map<String, String>,
        content: ByteArray,
    ): GoogleDriveFile {
        val token = getValidToken()
        val boundary = "===============RevaDriveUpload${UUID.randomUUID()}="

        val metadataObj = JsonObject().apply {
            addProperty("name", name)
            val parentsArray = com.google.gson.JsonArray().apply {
                add(parentFolderId)
            }
            add("parents", parentsArray)
            if (appProperties.isNotEmpty()) {
                val propsObj = JsonObject().apply {
                    appProperties.forEach { (k, v) -> addProperty(k, v) }
                }
                add("appProperties", propsObj)
            }
        }

        val metadataJson = gson.toJson(metadataObj)
        val headerPart = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadataJson)
            append("\r\n--$boundary\r\n")
            append("Content-Type: $mimeType\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)

        val footerPart = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        val body = ByteArray(headerPart.size + content.size + footerPart.size)
        System.arraycopy(headerPart, 0, body, 0, headerPart.size)
        System.arraycopy(content, 0, body, headerPart.size, content.size)
        System.arraycopy(footerPart, 0, body, headerPart.size + content.size, footerPart.size)

        val request = HttpRequest(
            method = "POST",
            url = "$uploadBaseUrl/files?uploadType=multipart&fields=id,name,mimeType,parents,appProperties,createdTime",
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "multipart/related; boundary=$boundary",
            ),
            body = body,
        )

        val response = transport.execute(request)
        handlePotentialError(response)
        return parseDriveFile(response.bodyAsString)
    }

    override suspend fun updateFile(
        fileId: String,
        name: String,
        mimeType: String,
        appProperties: Map<String, String>,
        content: ByteArray,
    ): GoogleDriveFile {
        val token = getValidToken()
        val boundary = "===============RevaDriveUpdate${UUID.randomUUID()}="
        val metadataObj = JsonObject().apply {
            addProperty("name", name)
            if (appProperties.isNotEmpty()) {
                add("appProperties", JsonObject().apply {
                    appProperties.forEach { (key, value) -> addProperty(key, value) }
                })
            }
        }
        val headerPart = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(gson.toJson(metadataObj))
            append("\r\n--$boundary\r\n")
            append("Content-Type: $mimeType\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val footerPart = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val body = ByteArray(headerPart.size + content.size + footerPart.size).also {
            System.arraycopy(headerPart, 0, it, 0, headerPart.size)
            System.arraycopy(content, 0, it, headerPart.size, content.size)
            System.arraycopy(footerPart, 0, it, headerPart.size + content.size, footerPart.size)
        }
        val response = transport.execute(
            HttpRequest(
                method = "PATCH",
                url = "$uploadBaseUrl/files/$fileId?uploadType=multipart&fields=id,name,mimeType,parents,appProperties,createdTime",
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Content-Type" to "multipart/related; boundary=$boundary",
                ),
                body = body,
            ),
        )
        handlePotentialError(response)
        return parseDriveFile(response.bodyAsString)
    }

    override suspend fun downloadFile(fileId: String): ByteArray {
        val token = getValidToken()
        val request = HttpRequest(
            method = "GET",
            url = "$baseUrl/files/$fileId?alt=media",
            headers = mapOf("Authorization" to "Bearer $token"),
        )
        val response = transport.execute(request)
        handlePotentialError(response)
        return response.body
    }

    private suspend fun queryFiles(query: String): List<GoogleDriveFile> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val files = mutableListOf<GoogleDriveFile>()
        val seenTokens = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val token = getValidToken()
            val pageSuffix = pageToken?.let { "&pageToken=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
            val request = HttpRequest(
                method = "GET",
                url = "$baseUrl/files?q=$encodedQuery&spaces=drive&fields=nextPageToken,files(id,name,mimeType,parents,appProperties,createdTime)&pageSize=100$pageSuffix",
                headers = mapOf("Authorization" to "Bearer $token"),
            )
            val response = transport.execute(request)
            handlePotentialError(response)
            val json = JsonParser.parseString(response.bodyAsString).asJsonObject
            json.getAsJsonArray("files")?.forEach { files += parseDriveFile(it.asJsonObject) }
            pageToken = json.get("nextPageToken")?.asString?.takeIf { it.isNotBlank() }
            if (pageToken != null && !seenTokens.add(pageToken)) pageToken = null
        } while (pageToken != null)
        return files
    }

    private suspend fun getValidToken(): String {
        val token = tokenProvider()
        if (token.isNullOrBlank()) {
            throw GoogleDriveException.AuthorizationException("Missing or invalid Google Drive authorization token")
        }
        return token
    }

    private fun handlePotentialError(response: HttpResponse) {
        if (response.statusCode in 200..299) return

        val body = response.bodyAsString
        when (response.statusCode) {
            401 -> throw GoogleDriveException.AuthorizationException("Drive authorization failed (HTTP 401): $body")
            403 -> {
                if (body.contains("rateLimitExceeded", ignoreCase = true) ||
                    body.contains("userRateLimitExceeded", ignoreCase = true) ||
                    body.contains("quotaExceeded", ignoreCase = true)
                ) {
                    throw GoogleDriveException.RateLimitException("Drive rate limit or quota exceeded (HTTP 403): $body")
                } else {
                    throw GoogleDriveException.ForbiddenException("Drive access forbidden (HTTP 403): $body")
                }
            }
            429 -> throw GoogleDriveException.RateLimitException("Drive rate limit exceeded (HTTP 429): $body")
            500, 502, 503, 504 ->
                throw GoogleDriveException.TransientServerException("Drive server error (HTTP ${response.statusCode}): $body")
            else ->
                throw GoogleDriveException.GeneralDriveException("Drive API error (HTTP ${response.statusCode}): $body")
        }
    }

    private fun parseDriveFile(jsonString: String): GoogleDriveFile {
        val obj = JsonParser.parseString(jsonString).asJsonObject
        return parseDriveFile(obj)
    }

    private fun parseDriveFile(obj: JsonObject): GoogleDriveFile {
        val id = obj.get("id")?.asString?.takeIf(String::isNotBlank)
            ?: throw GoogleDriveException.GeneralDriveException("Google Drive response missing file id: $obj", isRetryable = true)
        val name = obj.get("name")?.asString ?: "untitled"
        val mimeType = obj.get("mimeType")?.asString ?: "application/octet-stream"
        val parents = obj.getAsJsonArray("parents")?.map { it.asString } ?: emptyList()
        val appProperties = obj.getAsJsonObject("appProperties")?.entrySet()?.associate {
            it.key to it.value.asString
        } ?: emptyMap()
        val createdTime = obj.get("createdTime")?.asString?.let {
            try { Instant.parse(it) } catch (_: Exception) { null }
        } ?: Instant.now()

        return GoogleDriveFile(
            id = id,
            name = name,
            mimeType = mimeType,
            parents = parents,
            appProperties = appProperties,
            createdTime = createdTime,
        )
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.googleapis.com/drive/v3"
        const val DEFAULT_UPLOAD_BASE_URL = "https://www.googleapis.com/upload/drive/v3"
    }
}
