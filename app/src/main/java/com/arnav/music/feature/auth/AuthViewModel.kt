package com.arnav.music.feature.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.firebase.AuthRepository
import com.arnav.music.core.firebase.AuthResult
import com.arnav.music.core.firebase.CloudSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AuthMode { SIGN_IN, SIGN_UP, RESET }

data class AuthUi(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val loading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val done: Boolean = false,
)

class AuthViewModel(private val auth: AuthRepository, private val sync: CloudSync) : ViewModel() {
    private val _ui = MutableStateFlow(AuthUi())
    val ui: StateFlow<AuthUi> = _ui.asStateFlow()
    val available: Boolean get() = auth.isAvailable

    fun setMode(m: AuthMode) { _ui.value = AuthUi(mode = m) }

    private fun run(block: suspend () -> AuthResult, success: String? = null) {
        if (_ui.value.loading) return
        _ui.value = _ui.value.copy(loading = true, error = null, info = null)
        viewModelScope.launch {
            when (val r = block()) {
                AuthResult.Success -> {
                    if (success != null) _ui.value = _ui.value.copy(loading = false, info = success)
                    else { _ui.value = _ui.value.copy(loading = false, done = true); sync.requestSync(0) }
                }
                is AuthResult.Failure -> _ui.value = _ui.value.copy(loading = false, error = r.message)
                AuthResult.Cancelled -> _ui.value = _ui.value.copy(loading = false)
            }
        }
    }

    fun signIn(email: String, password: String) {
        if (!valid(email, password)) return
        run({ auth.signIn(email, password) })
    }

    fun signUp(name: String, email: String, password: String) {
        if (name.isBlank()) { _ui.value = _ui.value.copy(error = "Tell us what to call you."); return }
        if (!valid(email, password)) return
        run({ auth.signUp(name, email, password) })
    }

    fun reset(email: String) {
        if (!EMAIL.matches(email.trim())) { _ui.value = _ui.value.copy(error = "Enter the email you signed up with."); return }
        run({ auth.resetPassword(email) }, success = "Check your inbox for a reset link.")
    }

    fun google(activity: Activity) = run({ auth.signInWithGoogle(activity) })

    private fun valid(email: String, password: String): Boolean {
        val err = when {
            !EMAIL.matches(email.trim()) -> "That email doesn't look right."
            password.length < 8 -> "Passwords need at least 8 characters."
            else -> null
        }
        _ui.value = _ui.value.copy(error = err)
        return err == null
    }

    companion object { private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$") }
}
