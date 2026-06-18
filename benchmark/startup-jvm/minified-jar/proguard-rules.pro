# Keep the generated top-level functions used by the JMH harness.
-keep class dev.zacsweers.metro.benchmark.app.component.AppComponentKt {
    public static *** createAndInitialize();
    public static *** traceNextCreateAndInitialize();
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
