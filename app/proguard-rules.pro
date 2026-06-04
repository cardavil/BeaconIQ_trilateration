# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- Project rules (inert while isMinifyEnabled = false; ready if enabled) ---

# AltBeacon uses reflection internally for parsers/simulators.
-keep class org.altbeacon.beacon.** { *; }
-dontwarn org.altbeacon.beacon.**

# org.json is part of the platform; keep to be safe for any reflective use.
-keep class org.json.** { *; }

# Keep the immutable model class (serialized to/from JSON by field name).
-keep class beaconiq.model.Beacon { *; }