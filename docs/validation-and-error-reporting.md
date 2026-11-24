# Validation & Error Reporting

Metro reports declaration errors in FIR, so the IDE can display them when compiler plugin support is enabled.

Metro validates each dependency graph in IR. Each diagnostic has a stable ID, a dependency chain, a binding trace, and annotations for the fix (`help:`), context (`note:`), and reference (`docs:`). See the [Diagnostics Reference](diagnostics.md) for the generated index.

A missing binding looks like this:

```
AppGraph.kt:13:3: error: 
[Metro/MissingBinding] No binding found for Foo

  AppGraph.injectedThing -> InjectedThing -> Foo

  trace (in AppGraph):
      Foo is injected at InjectedThing(…, foo)
      InjectedThing is requested at AppGraph.injectedThing

  help: ensure Foo has an @Inject constructor or is provided by an @Provides or @Binds declaration
        visible to AppGraph
  docs: https://zacsweers.github.io/metro/latest/diagnostics/#missingbinding
```

If a similar binding exists, the `similar bindings:` section lists its location. Matches can differ by qualifier, nullability, subtype, supertype, or multibinding.

A dependency cycle appears as a closed loop. `~~>` marks `@Binds` alias edges, and the diagnostic suggests a fix:

```
ExampleGraph.kt:7:11: error: 
[Metro/DependencyCycle] Found a dependency cycle while processing test.ExampleGraph

  cycle:
      +-> Double -> String -> Int --+
      +-----------------------------+

  trace (in test.ExampleGraph):
      Double is injected at test.ExampleGraph.provideInt(…, double)
      String is injected at test.ExampleGraph.provideDouble(…, string)
      Int is injected at test.ExampleGraph.provideString(…, int)
      Double is injected at test.ExampleGraph.provideInt(…, double)
      ...

  help: break the cycle by injecting a deferred type at one edge, e.g. `() -> Double` or
        `Lazy<Double>`
  docs: https://zacsweers.github.io/metro/latest/diagnostics/#dependencycycle
```

Output wraps at 100 columns, except that identifiers are never truncated. You can search logs for complete type names.

## Render modes

The `diagnosticsRenderMode` Gradle option controls rendering:

```kotlin
metro {
  diagnosticsRenderMode.set(DiagnosticsRenderMode.RICH)
}
```

- `PLAIN` — the layout above: ASCII structure, no ANSI codes. Safe for any log consumer.
- `RICH` — the same layout with Unicode structure (`╭─▶ Double → String → Int ─╮`), ANSI styling, and real source snippets. Highlighted spans and `⌃`/`⌄` markers identify the injection site for a missing binding or each conflicting declaration for a duplicate binding.
- `AUTO` (default) — selects `PLAIN` at configuration time for a non-empty `NO_COLOR`, `--console=plain`, or an IDE-invoked build. Other builds, including CI, use `RICH`.

You can also set the `diagnosticsRenderMode` Gradle property or the `metro.diagnosticsRenderMode` system property. The compiler reads the system property directly, and it takes precedence. Render mode is excluded from compilation task inputs, so switching modes does not invalidate compilation or split build caches.

## Changing diagnostic severity

The `diagnostic-level` compiler option changes the severity of a Metro diagnostic by name. It works like kotlinc's [`-Xwarning-level`](https://kotlinlang.org/docs/compiler-reference.html#xwarning-level), but it can also lower errors to warnings. This is useful when another tool makes a Metro error a false positive in your build.

```kotlin
metro.compilerOptions {
  put("diagnostic-level", "MAP_KEY_WITHOUT_CONTRIBUTES_INTO_MAP:warning")
}
```

The available levels are `error`, `warning`, `disabled`, `ide-warning`, and `ide-error`. The IDE-only levels report in the IDE and are disabled in CLI builds. Separate multiple entries with commas, like `NAME_A:warning,NAME_B:disabled`.

Only diagnostics reported in FIR are supported for now. Metro reports an error if the name is unknown or the diagnostic is reported in IR.

The compiler plugin resolves graphs in IR, so its graph diagnostics do not appear through third-party FIR plugin support in the IDE. Maybe someday we can move that work to FIR, too.
