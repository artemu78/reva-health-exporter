package dev.reva.healthexporter

import java.time.Instant
import java.util.UUID

class FakeDriveBackend {
    val accountFiles = mutableMapOf<String, MutableList<GoogleDriveFile>>()
    val accountFileContents = mutableMapOf<String, MutableMap<String, ByteArray>>()

    fun getFiles(accountId: String): MutableList<GoogleDriveFile> =
        accountFiles.getOrPut(accountId) { mutableListOf() }

    fun getContents(accountId: String): MutableMap<String, ByteArray> =
        accountFileContents.getOrPut(accountId) { mutableMapOf() }

    fun clear() {
        accountFiles.clear()
        accountFileContents.clear()
    }
}

class FakeGoogleDriveGateway(
    override val accountId: String? = "default-test-account",
    val backend: FakeDriveBackend = FakeDriveBackend(),
) : GoogleDriveGateway {

    val files: MutableList<GoogleDriveFile>
        get() = backend.getFiles(accountId ?: "anonymous")

    val fileContents: MutableMap<String, ByteArray>
        get() = backend.getContents(accountId ?: "anonymous")

    var failOnVerifyAccess: GoogleDriveException? = null
    var failOnFindFolders: GoogleDriveException? = null
    var failOnCreateFolder: GoogleDriveException? = null
    var failOnFindFiles: GoogleDriveException? = null
    var failOnUploadFile: GoogleDriveException? = null
    var failOnDownloadFile: GoogleDriveException? = null
    var simulateIndeterminateUploadSuccess: Boolean = false

    var uploadCallCount = 0
    var createFolderCallCount = 0
    var findFoldersCallCount = 0
    var findFilesCallCount = 0
    var downloadCallCount = 0

    override suspend fun verifyAccess() {
        failOnVerifyAccess?.let { throw it }
        if (accountId == null) {
            throw GoogleDriveException.AuthorizationException("Account is not connected")
        }
    }

    override suspend fun findFolders(name: String, parentId: String?): List<GoogleDriveFile> {
        findFoldersCallCount++
        failOnFindFolders?.let { throw it }

        val targetParent = parentId ?: "root"
        return files.filter { file ->
            file.mimeType == FOLDER_MIME_TYPE &&
                file.name == name &&
                file.parents.contains(targetParent)
        }
    }

    override suspend fun createFolder(name: String, parentId: String?): GoogleDriveFile {
        createFolderCallCount++
        failOnCreateFolder?.let { throw it }

        val targetParent = parentId ?: "root"
        val folder = GoogleDriveFile(
            id = "folder-${UUID.randomUUID()}",
            name = name,
            mimeType = FOLDER_MIME_TYPE,
            parents = listOf(targetParent),
            createdTime = Instant.now(),
        )
        files.add(folder)
        return folder
    }

    override suspend fun findFiles(
        parentFolderId: String?,
        name: String?,
        appProperties: Map<String, String>,
    ): List<GoogleDriveFile> {
        findFilesCallCount++
        failOnFindFiles?.let { throw it }

        return files.filter { file ->
            val matchParent = parentFolderId == null || file.parents.contains(parentFolderId)
            val matchName = name == null || file.name == name
            val matchProps = appProperties.all { (k, v) -> file.appProperties[k] == v }
            matchParent && matchName && matchProps
        }
    }

    override suspend fun uploadFile(
        name: String,
        mimeType: String,
        parentFolderId: String,
        appProperties: Map<String, String>,
        content: ByteArray,
    ): GoogleDriveFile {
        uploadCallCount++

        val file = GoogleDriveFile(
            id = "file-${UUID.randomUUID()}",
            name = name,
            mimeType = mimeType,
            parents = listOf(parentFolderId),
            appProperties = appProperties,
            createdTime = Instant.now(),
            sizeBytes = content.size.toLong(),
        )

        if (simulateIndeterminateUploadSuccess) {
            // Write file in memory so it exists on remote server, but throw timeout to caller
            files.add(file)
            fileContents[file.id] = content
            simulateIndeterminateUploadSuccess = false // Reset after first trigger
            throw GoogleDriveException.TimeoutException("Remote upload completed but network response was lost")
        }

        failOnUploadFile?.let { throw it }

        files.add(file)
        fileContents[file.id] = content
        return file
    }

    override suspend fun downloadFile(fileId: String): ByteArray {
        downloadCallCount++
        failOnDownloadFile?.let { throw it }

        return fileContents[fileId]
            ?: throw GoogleDriveException.GeneralDriveException("File with id '$fileId' not found", isRetryable = false)
    }

    fun addExistingFolder(
        id: String,
        name: String,
        parentId: String? = null,
        createdTime: Instant = Instant.now(),
    ): GoogleDriveFile {
        val folder = GoogleDriveFile(
            id = id,
            name = name,
            mimeType = FOLDER_MIME_TYPE,
            parents = listOf(parentId ?: "root"),
            createdTime = createdTime,
        )
        files.add(folder)
        return folder
    }

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
    }
}
