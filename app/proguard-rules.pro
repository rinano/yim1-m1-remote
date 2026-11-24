# YI M1 Remote — R8/ProGuard rules (release builds only).
#
# Milestone 1 ships a debug APK; these rules keep the release build honest once
# the camera protocol client lands.
#
# TODO(protocol): if the protocol client ends up using reflection over models
# (e.g. a JSON mapper), keep those model class names here.

# Keep annotation metadata that Compose relies on.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, SourceFile, LineNumberTable

# Kotlin metadata is needed by kotlinx.coroutines for some paths.
-keep class kotlin.Metadata { *; }
