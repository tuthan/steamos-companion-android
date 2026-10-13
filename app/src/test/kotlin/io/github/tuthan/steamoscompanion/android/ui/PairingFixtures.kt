package io.github.tuthan.steamoscompanion.android.ui

import io.github.tuthan.steamoscompanion.core.client.CandidatePhase
import io.github.tuthan.steamoscompanion.core.client.PairingCandidate
import io.github.tuthan.steamoscompanion.core.client.PairingVariant

/** Pairing candidates for UI tests; they are never validated or stored. */
internal object PairingFixtures {
    const val CODE = "75332470"

    fun candidate(
        expiresAtMillis: Long?,
        variant: PairingVariant = PairingVariant.VERIFICATION,
        code: String? = if (variant == PairingVariant.VERIFICATION) CODE else null,
        phase: CandidatePhase = CandidatePhase.PENDING,
    ) = PairingCandidate(
        generation = 1,
        variant = variant,
        clientId = "client-android",
        clientName = "Pixel",
        requestedScopes = listOf("status.read", "power.control", "display.control"),
        endpoint = "192.0.2.5:18443",
        certificatePin = "sha256:" + "a".repeat(64),
        hostId = "host-fixture0001",
        createdAtMillis = 0,
        expiresAtMillis = expiresAtMillis,
        phase = phase,
        verificationCode = code,
    )
}
