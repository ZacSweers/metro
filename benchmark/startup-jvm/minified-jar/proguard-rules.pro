# Keep the createAndInitialize function as the entry point for benchmarks
-keep class dev.zacsweers.metro.benchmark.app.component.AppComponentKt {
    public static *** createAndInitialize();
}

# Keep AppComponent and its public methods since it's the return type of createAndInitialize
-keep class dev.zacsweers.metro.benchmark.app.component.AppComponent {
    public *;
}

# Don't warn about missing classes
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

# Optimization settings
-allowaccessmodification
-dontobfuscate

# Verify that @ComptimeOnly elements are actually removed
-checkdiscard class * {
    @dev.zacsweers.metro.internal.ComptimeOnly <methods>;
}
-checkdiscard @dev.zacsweers.metro.internal.ComptimeOnly class *