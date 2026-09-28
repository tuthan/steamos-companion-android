package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.tuthan.steamoscompanion.R

/** Fixture-only gallery. These are the same components used by the live screens. */
enum class GallerySection { FACTS, CARDS, ACTIONS, BUTTONS, RECOVERY }

@Composable
fun CompanionComponentGallery(section: GallerySection, darkTheme: Boolean) {
    CompanionTheme(darkTheme = darkTheme) {
        val palette = LocalCompanionPalette.current
        Column(Modifier.fillMaxSize().background(Color(palette.bg.toInt()))
            .verticalScroll(rememberScrollState())
            .padding(CompanionSpacing.SCREEN_GUTTER_DP.dp),
            verticalArrangement = Arrangement.spacedBy(CompanionSpacing.ROW_GROUP_DP.dp)) {
            Kicker("Component gallery · ${section.name.lowercase()}")
            when (section) {
                GallerySection.FACTS -> GalleryFacts()
                GallerySection.CARDS -> GalleryCards()
                GallerySection.ACTIONS -> GalleryActions()
                GallerySection.BUTTONS -> GalleryButtons()
                GallerySection.RECOVERY -> GalleryRecovery()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GalleryFacts() {
    val palette = LocalCompanionPalette.current
    Text("SteamOS Companion", style = MaterialTheme.typography.headlineSmall,
        color = Color(palette.heading.toInt()))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("LAN only")
        Pill("Pinned TLS")
        Pill("Code never sent", StatusTone.GOOD)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Badge("Read status", StatusTone.GOOD, icon = R.drawable.ic_check)
        Badge("Current", StatusTone.PENDING)
        Badge("Previewing", StatusTone.WARNING, icon = R.drawable.ic_status_clock)
        Badge("Planned", StatusTone.UNKNOWN)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusLine("Connected", StatusTone.GOOD, glyph = StatusGlyph.DOT, detail = "Checked 2 s ago")
        StatusLine("Status is out of date", StatusTone.UNKNOWN, glyph = StatusGlyph.CLOCK, detail = "Last checked 40 s ago")
        StatusLine("Can't reach Living-room Deck", StatusTone.WARNING, glyph = StatusGlyph.BLOCKED)
        StatusLine("Pairing is no longer valid", StatusTone.BLOCKED, glyph = StatusGlyph.KEY)
        StatusLine("Waiting for approval on Living-room Deck…", StatusTone.PENDING)
        StatusLine("Result not confirmed", StatusTone.WARNING, glyph = StatusGlyph.QUESTION)
        StatusLine("Some network addresses weren't searched. You can enter the address directly.",
            StatusTone.WARNING)
    }
    MonoValue("deck-1.invalid:18443")
    MonoValue("sha256:9f3a27c1b8e0f4d2a6b1c9e8d7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8")
}

@Composable
private fun GalleryCards() {
    var choice by remember { mutableIntStateOf(0) }
    InfoCard("Pinned identity", "The device certificate is checked before sending credentials.",
        icon = R.drawable.ic_lock)
    FactTable {
        FactRow("Last connected", "2 s ago")
        FactRow("Steam bridge", "Ready")
        FactRow("Wake target", "e4:5f:01:aa:bb:cc")
        FactRow("Host protocol", "v1 · Decky 0.5.16", divider = false)
    }
    WireLog(listOf(WireLogEntry(WireDirection.REQUEST, "POST /v1/power 7f3a…c2 → 202"),
        WireLogEntry(WireDirection.RESPONSE, "200 · status received"),
        WireLogEntry(WireDirection.LOCAL, "Result not confirmed")))
    ChoiceCard("Remove access on the host", "Ask the host to revoke this phone", selected = choice == 0,
        onClick = { choice = 0 })
    ChoiceCard("Forget on this phone only", "Host removal will not be confirmed", selected = choice == 1,
        onClick = { choice = 1 })
}

@Composable
private fun GalleryActions() {
    DeviceHeader("Living-room Deck", "deck-1.invalid:18443", "Connected", StatusTone.GOOD,
        kicker = "Remote device", glyph = StatusGlyph.DOT, detail = "Checked 2 s ago")
    Kicker("Recovery")
    ActionRow("Restore saved display", secondary = "Saved: 1920 × 1080 · 60 Hz", icon = R.drawable.ic_replay,
        onClick = {})
    ActionRow("Wake device", secondary = "Wake target saved · sends one magic packet", icon = R.drawable.ic_radar,
        onClick = {})
    ActionRow("Display settings", secondary = "Current: 3840 × 2160 · 60 Hz", icon = R.drawable.ic_list,
        navigates = true, onClick = {})
    ActionRow("1920 × 1080 · 60 Hz", secondary = "Saved recovery mode", selected = true,
        badge = "Current", badgeTone = StatusTone.PENDING, onClick = {})
    ActionRow("Power options", secondary = "Suspend, restart, shut down", icon = R.drawable.ic_play,
        enabled = false, reason = "Waiting for this preview to be kept or reverted", navigates = true, onClick = {})
    ActionRow("Remove this pairing", secondary = "Revoke on device or forget here", icon = R.drawable.ic_key,
        danger = true, navigates = true, onClick = {})
}

@Composable
private fun GalleryButtons() {
    var tab by remember { mutableIntStateOf(0) }
    PrimaryAction("Find devices", icon = R.drawable.ic_radar, onClick = {})
    PrimaryAction("Keep and save", green = true, icon = R.drawable.ic_check, onClick = {})
    GhostAction("Cancel scan", icon = R.drawable.ic_close, onClick = {})
    DangerAction("Remove this pairing", onClick = {})
    PrimaryAction("Preview selected mode", enabled = false, onClick = {})
    SegmentTabs(listOf("Resolution", "Display order"), tab) { tab = it }
    StepRail(listOf("Find device", "Compare code", "Waiting for approval"), activeIndex = 1)
    ProgressStrip(ProgressState.Indeterminate, reduceMotion = true)
    ProgressStrip(ProgressState.Determinate(.65f))
}

@Composable
private fun GalleryRecovery() {
    CodeDisplay("75332470")
    PreviewCard("HDMI display · 1920 × 1080 · 60 Hz", 12, "Reverts in 12 seconds",
        canRevert = true, canKeep = true, onRevert = {}, onKeep = {},
        deviceName = "Living-room Deck", question = "Can you see the picture on the remote display?",
        totalSeconds = 15)
    ActionRow("Check result", secondary = "Result not confirmed", icon = R.drawable.ic_status_question, onClick = {})
}

@Preview(name = "Facts dark", widthDp = 360, heightDp = 640)
@Composable private fun FactsDarkPreview() = CompanionComponentGallery(GallerySection.FACTS, true)

@Preview(name = "Facts light", widthDp = 360, heightDp = 640)
@Composable private fun FactsLightPreview() = CompanionComponentGallery(GallerySection.FACTS, false)

@Preview(name = "Cards dark", widthDp = 360, heightDp = 640)
@Composable private fun CardsDarkPreview() = CompanionComponentGallery(GallerySection.CARDS, true)

@Preview(name = "Actions dark", widthDp = 360, heightDp = 640)
@Composable private fun ActionsDarkPreview() = CompanionComponentGallery(GallerySection.ACTIONS, true)

@Preview(name = "Actions light", widthDp = 360, heightDp = 640)
@Composable private fun ActionsLightPreview() = CompanionComponentGallery(GallerySection.ACTIONS, false)

@Preview(name = "Buttons dark", widthDp = 360, heightDp = 640)
@Composable private fun ButtonsDarkPreview() = CompanionComponentGallery(GallerySection.BUTTONS, true)

@Preview(name = "Recovery dark", widthDp = 360, heightDp = 640)
@Composable private fun RecoveryDarkPreview() = CompanionComponentGallery(GallerySection.RECOVERY, true)

@Preview(name = "Recovery light", widthDp = 360, heightDp = 640)
@Composable private fun RecoveryLightPreview() = CompanionComponentGallery(GallerySection.RECOVERY, false)
