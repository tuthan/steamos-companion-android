package io.github.tuthan.steamoscompanion.core.client

/**
 * Which way out the owner is offered when the encrypted state cannot be opened (Phase 04). Network work stops in every
 * case, and nothing here erases anything: a reset is always an explicit, confirmed tap.
 */
enum class SavedPairingProblemKind {
    /** Damaged file or a missing or permanently invalidated key: the saved pairing cannot be read again. */
    RESET_REQUIRED,

    /** The key is temporarily unavailable, or the read failed for an input/output or crypto reason: try again. */
    RETRY,

    /** A schema this build does not know. Never overwritten; the owner updates the app or reinstalls it. */
    APP_UPDATE_REQUIRED,
}

data class SavedPairingProblem(
    val kind: SavedPairingProblemKind,
    /** Offered only for a state that can never be opened again; it clears the file and the key. */
    val canReset: Boolean,
    val canRetry: Boolean,
) {
    companion object {
        /** The problem a load result represents, or null when the state is usable or simply empty (nothing paired yet). */
        fun from(load: StateLoadResult): SavedPairingProblem? = when (load) {
            StateLoadResult.Empty, is StateLoadResult.Ready -> null
            is StateLoadResult.Failed -> RETRY_ONLY
            is StateLoadResult.Unavailable -> when (load.reason) {
                StateUnavailableReason.DAMAGED,
                StateUnavailableReason.KEY_MISSING,
                StateUnavailableReason.KEY_INVALIDATED,
                -> RESET
                StateUnavailableReason.TEMPORARY_KEY_UNAVAILABLE -> RETRY_ONLY
                StateUnavailableReason.APP_UPDATE_REQUIRED -> UPDATE
            }
        }

        private val RESET = SavedPairingProblem(SavedPairingProblemKind.RESET_REQUIRED, canReset = true, canRetry = false)
        private val RETRY_ONLY = SavedPairingProblem(SavedPairingProblemKind.RETRY, canReset = false, canRetry = true)
        private val UPDATE = SavedPairingProblem(SavedPairingProblemKind.APP_UPDATE_REQUIRED, canReset = false, canRetry = true)
    }
}
