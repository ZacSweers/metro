# Not necessary for our use
-dontobfuscate

# Verify that @ComptimeOnly elements are actually removed
-checkdiscard class * {
    @dev.zacsweers.metro.internal.ComptimeOnly <methods>;
}
-checkdiscard @dev.zacsweers.metro.internal.ComptimeOnly class *