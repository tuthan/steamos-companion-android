package io.github.tuthan.steamoscompanion.android.ui

/**
 * Text pairs measured below 4.5:1 on screenshots (R10), keyed `screen|theme|text` with the measured ratio. See the ratchet
 * in [CompanionVisualRulesTest]. Every entry is text on a profile tint fill (badge, ghost button, selected row, step
 * circle); fixing one means changing a tint alpha or a text token, which is a design decision and re-records goldens.
 */
internal object KnownContrastFindings {
    val pairs: Map<String, Double> = mapOf(
        "gallery actions|dark|Saved recovery mode" to 4.13,
        "gallery actions|light|CURRENT" to 3.70,
        "gallery buttons|dark|3" to 4.11,
        "gallery buttons|light|2" to 4.19,
        "gallery buttons|light|3" to 4.36,
        "gallery buttons|light|Cancel scan" to 4.26,
        "gallery cards|dark|Ask the host to revoke this phone" to 4.13,
        "gallery cards|light|! LOCAL" to 4.22,
        "gallery cards|light|← RECEIVED" to 3.67,
        "gallery cards|light|→ SENT" to 3.95,
        "gallery facts|dark|PLANNED" to 4.11,
        "gallery facts|light|CURRENT" to 4.19,
        "gallery facts|light|PLANNED" to 4.36,
        "gallery facts|light|PREVIEWING" to 4.48,
        "gallery facts|light|READ STATUS" to 3.90,
        "pairing counting|dark|4" to 4.11,
        "pairing counting|light|3" to 4.19,
        "pairing counting|light|4" to 4.36,
        "pairing counting|light|CHANGE DISPLAY SETTINGS" to 4.19,
        "pairing counting|light|CONTROL POWER" to 4.19,
        "pairing counting|light|Cancel request" to 4.26,
        "pairing counting|light|READ STATUS" to 4.19,
        "pairing expired|dark|4" to 4.11,
        "pairing expired|light|3" to 4.19,
        "pairing expired|light|4" to 4.36,
        "pairing expired|light|CHANGE DISPLAY SETTINGS" to 4.19,
        "pairing expired|light|CONTROL POWER" to 4.19,
        "pairing expired|light|Cancel request" to 4.26,
        "pairing expired|light|READ STATUS" to 4.19,
        "pairing near expiry|dark|4" to 4.11,
        "pairing near expiry|light|3" to 4.19,
        "pairing near expiry|light|4" to 4.36,
        "pairing near expiry|light|CHANGE DISPLAY SETTINGS" to 4.19,
        "pairing near expiry|light|CONTROL POWER" to 4.19,
        "pairing near expiry|light|Cancel request" to 4.26,
        "pairing near expiry|light|READ STATUS" to 4.19,
    )
}
