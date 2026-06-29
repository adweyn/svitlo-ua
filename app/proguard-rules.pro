# Keep WebView JavaScript interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Keep Activity
-keep class ua.svitlo.app.** { *; }

# WebView
-keepclassmembers class android.webkit.** { *; }
