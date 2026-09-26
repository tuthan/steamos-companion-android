# AndroidX's instrumentation runner and Kotlin tests resolve their runtime from the target APK.
-keep class kotlin.** { *; }
# The Android fake host lives in another APK and shares this server library with the client.
-keep class org.bouncycastle.** { *; }
# The full BC artifact includes optional desktop LDAP lookup code; Android does not ship JNDI.
-dontwarn javax.naming.**
