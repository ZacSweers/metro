# Metro Runtime ProGuard/R8 Rules
# These rules remove compile-time-only constructs that are not needed at runtime.

# Remove methods annotated with @ComptimeOnly
# These are stub methods (like @Binds implementations) that throw and are never called
-assumenosideeffects class * {
    @dev.zacsweers.metro.internal.ComptimeOnly <methods>;
}

# Remove classes annotated with @ComptimeOnly
# These are compile-time-only classes (like BindsMirror) that are not needed at runtime
-assumenosideeffects @dev.zacsweers.metro.internal.ComptimeOnly class *
