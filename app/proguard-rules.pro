# Project-specific ProGuard / R8 rules. Applied to the release build only.

# --- kotlinx-serialization --------------------------------------------------
# Keep the generated $serializer companions + serializer(...) lookup so JSON decode
# (AppPrefs, global_actions_defaults.json, AppPrefsSerializer) works after shrinking.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class org.tfv.deskflow.**$$serializer { *; }
-keepclassmembers class org.tfv.deskflow.** {
    *** Companion;
}
-keepclasseswithmembers class org.tfv.deskflow.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Protocol Buffers (full java runtime + generated messages) --------------
# Generated message classes rely on reflection, so keep the runtime intact.
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
# Keep our generated message classes (AppPrefs et al.).
-keep class org.tfv.deskflow.data.** { *; }
-keep class ** extends com.google.protobuf.GeneratedMessageV3 { *; }
-keep class ** extends com.google.protobuf.GeneratedMessageLite { *; }

# --- kotlin.logging / SLF4J -------------------------------------------------
-keep class mu.** { *; }
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.impl.**

# --- App logging (reflective construction) ----------------------------------
# KLoggingManager.forwardingLogger() reflectively constructs AndroidForwardingLogger(String).
# Without this, R8 strips/renames that constructor and every Service fails at <clinit>
# with NoSuchMethodException -- the app won't start.
-keep class org.tfv.deskflow.logging.** { *; }

# --- Input events (Serializable across the in-process AIDL Bundle) ----------
# KeyboardEvent / MouseEvent / ScreenEvent / ClipboardData are passed between the
# ConnectionService and the input services via Bundle.putSerializable. R8 must not
# strip/rename their fields or deserialization yields broken objects and input
# (mouse/keyboard/clipboard) never arrives -- the connection still shows "connected"
# (status flows separately) but nothing moves.
-keep class org.tfv.deskflow.client.events.** { *; }
-keep class org.tfv.deskflow.client.models.** { *; }
