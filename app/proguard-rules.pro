# WebView JS 接口相关保留
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.tvweb.player.** { *; }
