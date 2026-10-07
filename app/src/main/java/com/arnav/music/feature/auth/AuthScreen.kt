package com.arnav.music.feature.auth

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.ArnavMark
import com.arnav.music.ui.components.PrimaryButton
import com.arnav.music.ui.components.SecondaryButton
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AuthScreen(vm: AuthViewModel = koinViewModel(), onDone: (() -> Unit)? = null) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    LaunchedEffect(ui.done) { if (ui.done) onDone?.invoke() ?: nav.back() }
    Box(Modifier.fillMaxSize()) {
        com.arnav.music.feature.moments.MomentCanvas(com.arnav.music.domain.model.Moments.byId("late_night")!!, Modifier.fillMaxSize(), animated = true, density = 0.5f)
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(Space.xs)) { if (onDone == null) ArnavIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", nav::back, tint = Color.White) }
            Spacer(Modifier.height(Space.xl))
            ArnavMark(Modifier.size(56.dp))
            Spacer(Modifier.height(Space.l))
            AuthCard(vm, ui, Modifier.padding(Space.gutter).widthIn(max = 480.dp))
        }
    }
}

@Composable
fun AuthCard(vm: AuthViewModel, ui: AuthUi, modifier: Modifier = Modifier) {
    val c = ArnavTheme.colors
    val motion = ArnavTheme.motion
    val activity = LocalContext.current as? Activity
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPw by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().glass(GlassMaterial.Elevated, RoundedCornerShape(Radius.xl)).padding(Space.xl)) {
        AnimatedContent(ui.mode, transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            (slideInHorizontally(motion.offsetSpring()) { (if (forward) it else -it) / 5 } + fadeIn(motion.fast())) togetherWith
                (slideOutHorizontally(motion.offsetSpring()) { (if (forward) -it else it) / 5 } + fadeOut(motion.fast())) using SizeTransform(clip = false)
        }, label = "authmode") { mode ->
            Column {
                Text(
                    when (mode) { AuthMode.SIGN_IN -> "Welcome back"; AuthMode.SIGN_UP -> "Create your account"; AuthMode.RESET -> "Reset password" },
                    style = ArnavTheme.type.headline, color = c.content,
                )
                Text(
                    when (mode) {
                        AuthMode.SIGN_IN -> "Sync your likes and playlists across devices."
                        AuthMode.SIGN_UP -> "Optional — Arnav Music works fully without one."
                        AuthMode.RESET -> "We'll email you a secure link."
                    },
                    style = ArnavTheme.type.bodySmall, color = c.contentMuted,
                )
                Spacer(Modifier.height(Space.xl))
                if (mode == AuthMode.SIGN_UP) {
                    Field(name, { name = it.take(60) }, "Name", KeyboardType.Text)
                    Spacer(Modifier.height(Space.m))
                }
                Field(email, { email = it.take(120) }, "Email", KeyboardType.Email)
                if (mode != AuthMode.RESET) {
                    Spacer(Modifier.height(Space.m))
                    OutlinedTextField(
                        password, { password = it.take(128) }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (mode == AuthMode.SIGN_IN) vm.signIn(email, password) else vm.signUp(name, email, password) }),
                        trailingIcon = { ArnavIconButton(if (showPw) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showPw) "Hide password" else "Show password", { showPw = !showPw }, tint = c.contentMuted, size = 20.dp) },
                        shape = RoundedCornerShape(Radius.m), colors = fieldColors(),
                    )
                }
                if (mode == AuthMode.SIGN_IN) {
                    Text("Forgot password?", style = ArnavTheme.type.label, color = c.accent,
                        modifier = Modifier.padding(top = Space.s).clip(RoundedCornerShape(Radius.s)).clickable(role = Role.Button) { vm.setMode(AuthMode.RESET) }.padding(Space.xs))
                }
            }
        }
        AnimatedVisibility(ui.error != null || ui.info != null, enter = expandVertically(motion.responsive()) + fadeIn(), exit = shrinkVertically(motion.fast()) + fadeOut()) {
            Text(ui.error ?: ui.info.orEmpty(), style = ArnavTheme.type.bodySmall, color = if (ui.error != null) c.danger else c.success, modifier = Modifier.padding(top = Space.m))
        }
        Spacer(Modifier.height(Space.xl))
        if (!vm.available) {
            Text("Cloud accounts aren't configured in this build. You can keep using Arnav Music locally.", style = ArnavTheme.type.bodySmall, color = c.contentMuted)
            return@Column
        }
        PrimaryButton(
            when (ui.mode) { AuthMode.SIGN_IN -> "Sign in"; AuthMode.SIGN_UP -> "Create account"; AuthMode.RESET -> "Send reset link" },
            { when (ui.mode) { AuthMode.SIGN_IN -> vm.signIn(email, password); AuthMode.SIGN_UP -> vm.signUp(name, email, password); AuthMode.RESET -> vm.reset(email) } },
            Modifier.fillMaxWidth(), loading = ui.loading,
        )
        if (ui.mode != AuthMode.RESET) {
            Spacer(Modifier.height(Space.m))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(c.divider))
                Text("or", style = ArnavTheme.type.caption, color = c.contentSubtle, modifier = Modifier.padding(horizontal = Space.m))
                Box(Modifier.weight(1f).height(1.dp).background(c.divider))
            }
            Spacer(Modifier.height(Space.m))
            SecondaryButton("Continue with Google", { activity?.let(vm::google) }, Modifier.fillMaxWidth(), icon = null)
        }
        Spacer(Modifier.height(Space.l))
        Text(
            when (ui.mode) { AuthMode.SIGN_IN -> "New here? Create an account"; AuthMode.SIGN_UP -> "Already have an account? Sign in"; AuthMode.RESET -> "Back to sign in" },
            style = ArnavTheme.type.label, color = c.accent, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.s)).clickable(role = Role.Button) {
                vm.setMode(if (ui.mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN)
            }.padding(Space.s),
        )
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, type: KeyboardType) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next), shape = RoundedCornerShape(Radius.m), colors = fieldColors(),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ArnavTheme.colors.accent, unfocusedBorderColor = ArnavTheme.colors.outline, cursorColor = ArnavTheme.colors.accent,
    focusedLabelColor = ArnavTheme.colors.accent,
)

@Suppress("unused") private val circle = CircleShape
@Suppress("unused") private val arr = Arrangement.Center
