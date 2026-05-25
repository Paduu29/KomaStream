# Preserve benchmark/profile installer entry points used outside direct code references.
-keep class androidx.profileinstaller.** { *; }

# Keep WorkManager workers instantiated from manifest/background scheduling.
-keep class * extends androidx.work.ListenableWorker

# Retain WebView JavaScript bridge annotations if any provider resolvers add them later.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
