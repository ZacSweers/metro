# Debugging

Metro generates IR directly, so a debugger cannot step into generated source files.

Enable `debug` in the compiler options or Gradle extension to print verbose Kotlin pseudocode for all generated IR classes. You can filter the output to specific classes.

```kotlin
metro {
  debug.set(true)
}
```

We could explore adding call stack information in IR, much like coroutines do. If that would help you debug Metro, let me know!

## Reports

Set `reportsDestination` in the Gradle DSL to choose where Metro writes graph reports. This is still a work in progress. Feedback is welcome!

!!! warning
    Enable reports only when needed. They can produce large output and add compilation cost. The Kotlin Gradle plugin does not track `reportsDestination` as a task input. Use `--rerun` on the compilation task after changing it.

```kotlin
metro {
  reportsDestination.set(layout.buildDirectory.dir("metro/reports"))
}
```

### Unmatched Exclusions and Replacements

When you set `reportsDestination`, Metro lists exclusions and replacements that did not match a merged contribution.

Reports are written to files like:

- `merging-unmatched-exclusions-fir/<graph>.txt`
- `merging-unmatched-replacements-fir/<graph>.txt`
- `merging-unmatched-exclusions-ir/<scope>.txt`
- `merging-unmatched-replacements-ir/<scope>.txt`

For nested graphs or classes, `<graph>` and `<scope>` can contain path segments, such as `<graph1>/<graph2>/<graph3>.txt`.

## Graph Analysis & Visualization

The interactive graph viewer lets you browse bindings, follow dependencies from roots, and inspect compiler decisions. It shows graph extensions and included graphs in the same map. See [Graph Analysis](graph-analysis.md) for setup and viewer controls.

## Decompiled Bytecode

Open a generated JVM class file in IntelliJ. The IDE usually displays it as a Kotlin bytecode class. Run **Decompile to Java** to inspect the generated code.

For JVM projects, class files are under `build/classes`.

For Android projects, look under `build/intermediates/built_in_kotlinc` or, before AGP 9, `build/tmp/kotlin-classes`.
