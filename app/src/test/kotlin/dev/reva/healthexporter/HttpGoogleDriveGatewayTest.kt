package dev.reva.healthexporter

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HttpGoogleDriveGatewayTest {

    private class FakeHttpTransport : HttpTransport {
        val executedRequests = mutableListOf<HttpRequest>()
        var responseToReturn: HttpResponse = HttpResponse(statusCode = 200, body = "{}".toByteArray())
        val queuedResponses = ArrayDeque<HttpResponse>()
        var exceptionToThrow: Throwable? = null

        override suspend fun execute(request: HttpRequest): HttpResponse {
            executedRequests.add(request)
            exceptionToThrow?.let { throw it }
            return if (queuedResponses.isNotEmpty()) queuedResponses.removeFirst() else responseToReturn
        }
    }

    @Test
    fun `findFiles follows nextPageToken so history inventory is complete`() = runBlocking {
        val transport = FakeHttpTransport().apply {
            queuedResponses += HttpResponse(200, body = """{"nextPageToken":"page-2","files":[{"id":"one"}]}""".toByteArray())
            queuedResponses += HttpResponse(200, body = """{"files":[{"id":"two"}]}""".toByteArray())
        }
        val gateway = HttpGoogleDriveGateway(tokenProvider = { "token" }, transport = transport)

        val files = gateway.findFiles(appProperties = mapOf("installationId" to "synthetic"))

        assertEquals(listOf("one", "two"), files.map { it.id })
        assertEquals(2, transport.executedRequests.size)
        assertTrue(transport.executedRequests[1].url.contains("pageToken=page-2"))
    }

    @Test
    fun `verifyAccess sends GET with Bearer token`() = runBlocking {
        val transport = FakeHttpTransport()
        transport.responseToReturn = HttpResponse(statusCode = 200, body = "{\"files\":[]}".toByteArray())

        val gateway = HttpGoogleDriveGateway(
            accountId = "acc-01",
            tokenProvider = { "test-oauth-token-123" },
            transport = transport,
        )

        gateway.verifyAccess()

        assertEquals(1, transport.executedRequests.size)
        val request = transport.executedRequests.first()
        assertEquals("GET", request.method)
        assertEquals("Bearer test-oauth-token-123", request.headers["Authorization"])
        assertTrue(request.url.contains("/files?pageSize=1"))
    }

    @Test
    fun `verifyAccess throws AuthorizationException when token is null or blank`() = runBlocking {
        val transport = FakeHttpTransport()
        val gateway = HttpGoogleDriveGateway(
            accountId = null,
            tokenProvider = { null },
            transport = transport,
        )

        try {
            gateway.verifyAccess()
            fail("Should have thrown AuthorizationException")
        } catch (e: GoogleDriveException.AuthorizationException) {
            assertTrue(e.message.contains("Missing or invalid"))
        }
    }

    @Test
    fun `findFolders builds correct Drive v3 query and parses results`() = runBlocking {
        val transport = FakeHttpTransport()
        val jsonResponse = """
            {
              "files": [
                {
                  "id": "folder-123",
                  "name": "schema-v1",
                  "mimeType": "application/vnd.google-apps.folder",
                  "parents": ["parent-abc"],
                  "createdTime": "2026-08-30T10:00:00Z"
                }
              ]
            }
        """.trimIndent()
        transport.responseToReturn = HttpResponse(statusCode = 200, body = jsonResponse.toByteArray())

        val gateway = HttpGoogleDriveGateway(
            accountId = "acc-01",
            tokenProvider = { "valid-token" },
            transport = transport,
        )

        val folders = gateway.findFolders(name = "schema-v1", parentId = "parent-abc")

        assertEquals(1, folders.size)
        val folder = folders.first()
        assertEquals("folder-123", folder.id)
        assertEquals("schema-v1", folder.name)
        assertEquals("application/vnd.google-apps.folder", folder.mimeType)
        assertTrue(folder.parents.contains("parent-abc"))

        val executed = transport.executedRequests.first()
        assertTrue(executed.url.contains("q="))
        assertTrue(executed.url.contains("spaces=drive"))
    }

    @Test
    fun `createFolder sends POST with name and parent metadata`() = runBlocking {
        val transport = FakeHttpTransport()
        val jsonResponse = """
            {
              "id": "new-folder-789",
              "name": "2026",
              "mimeType": "application/vnd.google-apps.folder",
              "parents": ["parent-schema"]
            }
        """.trimIndent()
        transport.responseToReturn = HttpResponse(statusCode = 200, body = jsonResponse.toByteArray())

        val gateway = HttpGoogleDriveGateway(
            accountId = "acc-01",
            tokenProvider = { "valid-token" },
            transport = transport,
        )

        val folder = gateway.createFolder(name = "2026", parentId = "parent-schema")

        assertEquals("new-folder-789", folder.id)
        assertEquals("2026", folder.name)

        val executed = transport.executedRequests.first()
        assertEquals("POST", executed.method)
        val bodyString = String(executed.body!!, Charsets.UTF_8)
        assertTrue(bodyString.contains("\"name\":\"2026\""))
        assertTrue(bodyString.contains("\"parents\":[\"parent-schema\"]"))
    }

    @Test
    fun `uploadFile sends multipart related payload with appProperties`() = runBlocking {
        val transport = FakeHttpTransport()
        val jsonResponse = """
            {
              "id": "file-uploaded-456",
              "name": "test-batch.json",
              "mimeType": "application/json",
              "parents": ["month-folder-id"],
              "appProperties": {
                "batchId": "batch-111",
                "schemaVersion": "1"
              }
            }
        """.trimIndent()
        transport.responseToReturn = HttpResponse(statusCode = 200, body = jsonResponse.toByteArray())

        val gateway = HttpGoogleDriveGateway(
            accountId = "acc-01",
            tokenProvider = { "valid-token" },
            transport = transport,
        )

        val content = "{\"header\":{},\"records\":[]}".toByteArray(Charsets.UTF_8)
        val uploaded = gateway.uploadFile(
            name = "test-batch.json",
            mimeType = "application/json",
            parentFolderId = "month-folder-id",
            appProperties = mapOf("batchId" to "batch-111", "schemaVersion" to "1"),
            content = content,
        )

        assertEquals("file-uploaded-456", uploaded.id)
        assertEquals("test-batch.json", uploaded.name)

        val executed = transport.executedRequests.first()
        assertEquals("POST", executed.method)
        assertTrue(executed.headers["Content-Type"]?.startsWith("multipart/related") == true)
        val bodyString = String(executed.body!!, Charsets.UTF_8)
        assertTrue(bodyString.contains("test-batch.json"))
        assertTrue(bodyString.contains("application/json"))
        assertTrue(bodyString.contains("batch-111"))
    }

    @Test
    fun `downloadFile fetches media bytes via alt=media`() = runBlocking {
        val transport = FakeHttpTransport()
        val binaryData = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00)
        transport.responseToReturn = HttpResponse(statusCode = 200, body = binaryData)

        val gateway = HttpGoogleDriveGateway(
            accountId = "acc-01",
            tokenProvider = { "valid-token" },
            transport = transport,
        )

        val downloaded = gateway.downloadFile("file-123")

        assertEquals(4, downloaded.size)
        assertEquals(0x1f.toByte(), downloaded[0])
        assertEquals(0x8b.toByte(), downloaded[1])

        val executed = transport.executedRequests.first()
        assertEquals("GET", executed.method)
        assertTrue(executed.url.contains("/files/file-123?alt=media"))
    }

    @Test
    fun `error classification maps HTTP codes correctly`() = runBlocking {
        val transport = FakeHttpTransport()
        val gateway = HttpGoogleDriveGateway(
            tokenProvider = { "token" },
            transport = transport,
        )

        // 401 Unauthorized
        transport.responseToReturn = HttpResponse(statusCode = 401, body = "{\"error\":\"invalid_token\"}".toByteArray())
        try {
            gateway.verifyAccess()
            fail("Expected AuthorizationException")
        } catch (e: GoogleDriveException.AuthorizationException) {
            assertFalse(e.isRetryable)
        }

        // 403 Forbidden (general)
        transport.responseToReturn = HttpResponse(statusCode = 403, body = "{\"error\":{\"message\":\"Forbidden\"}}".toByteArray())
        try {
            gateway.verifyAccess()
            fail("Expected ForbiddenException")
        } catch (e: GoogleDriveException.ForbiddenException) {
            assertFalse(e.isRetryable)
        }

        // 403 Rate Limit / Quota Exceeded
        transport.responseToReturn = HttpResponse(
            statusCode = 403,
            body = "{\"error\":{\"errors\":[{\"reason\":\"rateLimitExceeded\"}]}}".toByteArray(),
        )
        try {
            gateway.verifyAccess()
            fail("Expected RateLimitException")
        } catch (e: GoogleDriveException.RateLimitException) {
            assertTrue(e.isRetryable)
        }

        // 429 Too Many Requests
        transport.responseToReturn = HttpResponse(statusCode = 429, body = "Too many requests".toByteArray())
        try {
            gateway.verifyAccess()
            fail("Expected RateLimitException")
        } catch (e: GoogleDriveException.RateLimitException) {
            assertTrue(e.isRetryable)
        }

        // 503 Service Unavailable
        transport.responseToReturn = HttpResponse(statusCode = 503, body = "Service Unavailable".toByteArray())
        try {
            gateway.verifyAccess()
            fail("Expected TransientServerException")
        } catch (e: GoogleDriveException.TransientServerException) {
            assertTrue(e.isRetryable)
        }
    }

    @Test
    fun `createFolder throws GeneralDriveException when response is missing id`() = runBlocking {
        val transport = FakeHttpTransport()
        transport.responseToReturn = HttpResponse(statusCode = 200, body = "{\"name\":\"test\"}".toByteArray())
        val gateway = HttpGoogleDriveGateway(tokenProvider = { "token" }, transport = transport)

        try {
            gateway.createFolder("test", null)
            fail("Expected GeneralDriveException when id is missing")
        } catch (e: GoogleDriveException.GeneralDriveException) {
            assertTrue(e.message.contains("missing file id"))
        }
    }
}
