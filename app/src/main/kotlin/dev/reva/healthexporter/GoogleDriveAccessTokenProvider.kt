package dev.reva.healthexporter

import android.content.Context
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class GoogleDriveAccessTokenProvider(context: Context) {
    private val client = Identity.getAuthorizationClient(context.applicationContext)
    private val scopes = DriveAuthorizationScopes.requested.map(::Scope)
    private var currentAccessToken: String? = null

    suspend fun getAccessToken(): String? {
        currentAccessToken?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            val request = buildDriveAuthorizationRequest()
            client.authorize(request)
                .addOnSuccessListener { result ->
                    val granted = result.grantedScopes.toSet()
                    val token = result.accessToken
                        ?.takeIf {
                            DriveAuthorizationScopes.containsRequired(granted) &&
                                !result.hasResolution() &&
                                it.isNotBlank()
                        }
                    currentAccessToken = token
                    continuation.resume(token)
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        }
    }
}
