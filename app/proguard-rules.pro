# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK.

# Keep Photon parser classes
-keep class com.grradar.parser.** { *; }
-keep class com.grradar.model.** { *; }

# Keep data classes for Gson
-keep class com.grradar.data.** { *; }
