# Keep the process root and its public graph until the screens begin calling it directly.
-keep class io.github.tuthan.steamoscompanion.android.CompanionApplication { *; }
-keep class io.github.tuthan.steamoscompanion.android.CompanionRuntime { *; }
-keep class io.github.tuthan.steamoscompanion.android.data.** { *; }
-keep class io.github.tuthan.steamoscompanion.core.** { *; }
