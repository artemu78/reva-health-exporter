package dev.reva.healthexporter

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class GoogleDriveAuthorizationGateway(
    private val activity: ComponentActivity,
    private val resolutionLauncher: ActivityResultLauncher<IntentSenderRequest>,
    private val onComplete: (DriveAuthorizationResult) -> Unit,
) : DriveAuthorizationGateway {
    private val client = Identity.getAuthorizationClient(activity)
    private val scopes = DriveAuthorizationScopes.requested.map(::Scope)
    var currentAccessToken: String? = null
        private set

    override fun launchAuthorization() {
        check(DriveAuthorizationScopes.isNarrow(scopes.map { it.scopeUri }.toSet()))
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .setOptOutIncludingGrantedScopes(true)
            .build()
        client.authorize(request)
            .addOnSuccessListener(::handleResult)
            .addOnFailureListener { onComplete(classifyFailure(it)) }
    }

    fun completeResolution(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            onComplete(DriveAuthorizationResult.Cancelled)
            return
        }
        try {
            handleResult(client.getAuthorizationResultFromIntent(data))
        } catch (error: ApiException) {
            onComplete(classifyFailure(error))
        }
    }

    override fun disconnect(onComplete: (DriveDisconnectionResult) -> Unit) {
        currentAccessToken = null
        try {
            val request = RevokeAccessRequest.builder().setScopes(scopes).build()
            client.revokeAccess(request)
                .addOnSuccessListener { onComplete(DriveDisconnectionResult.Disconnected) }
                .addOnFailureListener { onComplete(DriveDisconnectionResult.Failed) }
        } catch (_: Exception) {
            onComplete(DriveDisconnectionResult.Failed)
        }
    }

    suspend fun getAccessToken(): String? {
        currentAccessToken?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(scopes)
                .build()
            client.authorize(request)
                .addOnSuccessListener { result ->
                    val granted = result.grantedScopes.toSet()
                    if (DriveAuthorizationScopes.isNarrow(granted) && !result.hasResolution()) {
                        currentAccessToken = result.accessToken
                        continuation.resume(result.accessToken)
                    } else {
                        continuation.resume(null)
                    }
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        }
    }

    private fun handleResult(result: AuthorizationResult) {
        val granted = result.grantedScopes.toSet()
        if (!DriveAuthorizationScopes.isNarrow(granted)) {
            currentAccessToken = null
            onComplete(DriveAuthorizationResult.Denied)
            return
        }
        if (result.hasResolution()) {
            val pendingIntent = result.pendingIntent ?: run {
                currentAccessToken = null
                onComplete(DriveAuthorizationResult.Denied)
                return
            }
            resolutionLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            return
        }
        currentAccessToken = result.accessToken
        val account = result.toGoogleSignInAccount()
        val accountIdentity = account?.id ?: account?.account?.name
        onComplete(DriveAuthorizationResult.Authorized(accountIdentity?.let(::hashAccountIdentity)))
    }

    private fun classifyFailure(error: Exception): DriveAuthorizationResult {
        currentAccessToken = null
        return if (error is ApiException && error.statusCode == com.google.android.gms.common.api.CommonStatusCodes.CANCELED) {
            DriveAuthorizationResult.Cancelled
        } else {
            DriveAuthorizationResult.Denied
        }
    }

    private fun hashAccountIdentity(identity: String): String = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
