package io.github.tuthan.steamoscompanion.core

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.ports.SocketFactory
import io.github.tuthan.steamoscompanion.core.ports.StateStore

/**
 * Platform capabilities assembled by the app's manual composition root.
 * Core services receive only the ports they need in their constructors.
 */
data class CorePorts(
    val clock: Clock,
    val stateStore: StateStore,
    val networkAccess: NetworkAccess,
    val socketFactory: SocketFactory,
)
