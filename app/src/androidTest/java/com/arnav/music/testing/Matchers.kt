package com.arnav.music.testing

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText

/**
 * Semantics matchers for Arnav Music screens. Everything is found by visible text, content
 * descriptions or accessibility click labels, so the app needs no test tags.
 */
object M {
    fun role(role: Role): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    fun noRole(): SemanticsMatcher = SemanticsMatcher.keyNotDefined(SemanticsProperties.Role)

    /** Node whose click action carries this accessibility label (`clickable(onClickLabel = …)`). */
    fun clickLabel(label: String): SemanticsMatcher =
        SemanticsMatcher("has click label '$label'") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    /** Exact node, by semantics id (only valid while that node is alive). */
    fun id(id: Int): SemanticsMatcher = SemanticsMatcher("semantics id == $id") { it.id == id }

    /** Bottom navigation tab (or the navigation rail on wide windows). */
    fun tab(label: String): SemanticsMatcher = role(Role.Tab) and hasText(label) and hasClickAction()

    /** Clickable node showing [text] (buttons, rows, pills) — never a text field holding that text. */
    fun clickableText(text: String, substring: Boolean = false): SemanticsMatcher =
        hasText(text, substring = substring) and hasClickAction() and !hasSetTextAction()

    /** Icon button (or any clickable) with this content description. */
    fun icon(desc: String): SemanticsMatcher = hasContentDescription(desc) and hasClickAction()

    /** Plain (non-clickable, role-less) text such as a screen title. */
    fun title(text: String): SemanticsMatcher = hasText(text) and noRole() and !hasClickAction() and !hasSetTextAction()

    // ---- Screen markers (something only that screen shows) ----
    val ONBOARDING_SKIP: SemanticsMatcher = clickLabel("Skip onboarding")
    val HOME_ROOT: SemanticsMatcher = clickLabel("Profile")
    val EXPLORE_ROOT: SemanticsMatcher = title("Explore")
    val LIBRARY_ROOT: SemanticsMatcher = hasText("Filter your library")
    val AI_ROOT: SemanticsMatcher = hasText("Describe a feeling", substring = true)
    val SETTINGS_ROOT: SemanticsMatcher = hasText("Sign in, sync, profile")
    val PROFILE_ROOT: SemanticsMatcher = hasText("PREFERENCES")
    val HOME_SEARCH_BAR: SemanticsMatcher = clickableText("Songs, artists, playlists")
    val TEXT_FIELD: SemanticsMatcher = hasSetTextAction()

    // ---- Player ----
    /** The collapsed MorphBar (its whole surface opens Now Playing). */
    val MORPH_BAR: SemanticsMatcher = clickLabel("Open player")
    val PAUSE: SemanticsMatcher = icon("Pause") and role(Role.Button)
    val PLAY: SemanticsMatcher = icon("Play") and role(Role.Button)
    val COLLAPSE_PLAYER: SemanticsMatcher = icon("Collapse player")

    val TABS = listOf("Home", "Explore", "Library", "Arnav AI")

    fun tabRoot(label: String): SemanticsMatcher = when (label) {
        "Home" -> HOME_ROOT
        "Explore" -> EXPLORE_ROOT
        "Library" -> LIBRARY_ROOT
        "Arnav AI" -> AI_ROOT
        else -> error("Unknown tab $label")
    }
}

/** Everything a person could read or hear for this node: texts, descriptions, click label, field text. */
fun SemanticsNode.spokenText(): String {
    val parts = ArrayList<String>()
    config.getOrNull(SemanticsProperties.Text)?.forEach { parts += it.text }
    config.getOrNull(SemanticsProperties.ContentDescription)?.let { parts += it }
    config.getOrNull(SemanticsProperties.EditableText)?.let { parts += it.text }
    config.getOrNull(SemanticsActions.OnClick)?.label?.let { parts += it }
    return parts.filter { it.isNotBlank() }.joinToString(" / ")
}
