package com.arnav.music.core.firebase

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.arnav.music.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.FirebaseNetworkException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

data class UserAccount(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    val emailVerified: Boolean,
    val createdAt: Long?,
    val providers: List<String>,
)

sealed interface AuthResult {
    data object Success : AuthResult
    data class Failure(val message: String) : AuthResult
    data object Cancelled : AuthResult
}

/** Firebase Authentication. Passwords are never stored by the app. */
class AuthRepository(private val gate: FirebaseGate) {
    private val auth: FirebaseAuth? get() = if (gate.isAvailable) FirebaseAuth.getInstance() else null

    val isAvailable: Boolean get() = gate.isAvailable

    val currentUser: Flow<UserAccount?> get() {
        val a = auth ?: return flowOf(null)
        return callbackFlow {
            val l = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toAccount()) }
            a.addAuthStateListener(l)
            awaitClose { a.removeAuthStateListener(l) }
        }
    }

    fun current(): UserAccount? = auth?.currentUser?.toAccount()

    suspend fun signIn(email: String, password: String): AuthResult = guarded {
        it.signInWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun signUp(name: String, email: String, password: String): AuthResult = guarded {
        val result = it.createUserWithEmailAndPassword(email.trim(), password).await()
        result.user?.let { u ->
            u.updateProfile(com.google.firebase.auth.UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()).await()
            runCatching { u.sendEmailVerification().await() }
        }
    }

    suspend fun resetPassword(email: String): AuthResult = guarded { it.sendPasswordResetEmail(email.trim()).await() }

    suspend fun resendVerification(): AuthResult = guarded { it.currentUser?.sendEmailVerification()?.await() }

    /** Google sign-in via Credential Manager → Firebase credential. */
    suspend fun signInWithGoogle(activity: Activity): AuthResult {
        val a = auth ?: return AuthResult.Failure(NOT_CONFIGURED)
        val clientId = webClientId(activity) ?: return AuthResult.Failure("Google sign-in isn't configured for this build yet.")
        return try {
            val option = GetSignInWithGoogleOption.Builder(clientId).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val response = CredentialManager.create(activity).getCredential(activity, request)
            val cred = response.credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                a.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
                AuthResult.Success
            } else AuthResult.Failure("That account type isn't supported.")
        } catch (e: GetCredentialCancellationException) {
            AuthResult.Cancelled
        } catch (e: NoCredentialException) {
            AuthResult.Failure("No Google account is available on this device.")
        } catch (e: Exception) {
            AuthResult.Failure(friendly(e))
        }
    }

    suspend fun signOut(activity: Activity?) {
        auth?.signOut()
        if (activity != null) runCatching { CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest()) }
    }

    /** Deletes the Firebase account. Callers delete cloud data first (rules require auth). */
    suspend fun deleteAccount(): AuthResult = guarded { it.currentUser?.delete()?.await() }

    private suspend fun guarded(block: suspend (FirebaseAuth) -> Unit): AuthResult {
        val a = auth ?: return AuthResult.Failure(NOT_CONFIGURED)
        return try { block(a); AuthResult.Success } catch (e: Exception) { AuthResult.Failure(friendly(e)) }
    }

    private fun webClientId(activity: Activity): String? {
        BuildConfig.GOOGLE_WEB_CLIENT_ID.takeIf { it.isNotBlank() }?.let { return it }
        // Generated by the google-services plugin when google-services.json is present.
        val id = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
        return if (id != 0) activity.getString(id) else null
    }

    private fun friendly(e: Exception): String = when (e) {
        is FirebaseAuthWeakPasswordException -> "Choose a stronger password — at least 8 characters."
        is FirebaseAuthInvalidUserException -> "We couldn't find an account with that email."
        is FirebaseAuthInvalidCredentialsException -> "That email or password doesn't look right."
        is FirebaseAuthUserCollisionException -> "An account already exists with that email."
        is FirebaseAuthRecentLoginRequiredException -> "For your security, sign in again before doing that."
        is FirebaseNetworkException -> "You're offline. Check your connection and try again."
        is FirebaseAuthException -> "Sign-in failed. Please try again."
        else -> "Something went wrong. Please try again."
    }

    private fun com.google.firebase.auth.FirebaseUser.toAccount() = UserAccount(
        uid = uid, email = email, displayName = displayName, photoUrl = photoUrl?.toString(),
        emailVerified = isEmailVerified, createdAt = metadata?.creationTimestamp,
        providers = providerData.map { it.providerId },
    )

    companion object {
        const val NOT_CONFIGURED = "Cloud accounts aren't set up in this build. You can keep using Arnav Music locally."
    }
}
