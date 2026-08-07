# Project-specific ProGuard / R8 rules. Applied to the release build only.
#
# Several parts of the app rely on reflection / java.io.Serializable across the
# in-process AIDL (the reflective logger constructor, and the input/clipboard
# events) plus kotlinx-serialization and protobuf generated code. Chasing each
# site one-by-one kept biting us (logger ctor -> launch crash; Serializable
# events -> input never arrived). So keep all of org.tfv.deskflow.** intact;
# R8 still shrinks and optimizes the (much larger) third-party dependency tree.

-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Keep every class in the app + client modules: fields, methods, constructors.
-keep class org.tfv.deskflow.** { *; }

# --- Protocol Buffers (full java runtime + generated messages) --------------
# Generated message classes rely on reflection, so keep the runtime intact.
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-keep class ** extends com.google.protobuf.GeneratedMessageV3 { *; }
-keep class ** extends com.google.protobuf.GeneratedMessageLite { *; }

# --- kotlin.logging / SLF4J -------------------------------------------------
-keep class mu.** { *; }
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.impl.**
