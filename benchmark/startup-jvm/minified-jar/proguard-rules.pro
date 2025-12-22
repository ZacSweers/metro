# Keep the createAndInitialize function as the entry point for benchmarks
-keep class dev.zacsweers.metro.benchmark.app.component.AppComponentKt {
    public static *** createAndInitialize();
}

# Don't warn about missing classes
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

# Optimization settings
-allowaccessmodification
-dontobfuscate