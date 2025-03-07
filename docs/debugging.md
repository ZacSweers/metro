# Debugging

One major downside to generating IR directly is that developers cannot step into generated source code with the debugger. This is an accepted trade-off with Metro (or any other compiler plugin).

Metro does offer a `debug` option in its plugin options/Gradle extension that will print verbose Kotlin pseudocode for all generated IR classes. This can be further tuned to print just certain classes.

```kotlin
metro {
  debug.set(true)
}
```

In the future, we could possibly explore including information in IR to synthesize call stack information similar to coroutines, but will save that for if/when it’s asked for.


## Reports

Similar to Compose, Metro supports a `reportsDestination` property in its Gradle DSL and can output various graph reports to this destination if specified. This is very much a WIP, feedback is welcome!

```kotlin
metro {
  reportsDestination.set(layout.buildDirectory.dir("metro/reports"))
}
```