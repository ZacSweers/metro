# Validation & Error Reporting

Common programmer/usage errors are implemented in FIR. This should allow errors to appear directly in the IDE, offering the best and fastest feedback loop for developers writing their code.

Dependency graph validation is performed at the per-graph level in the compiler IR backend. Each graph diagnostic is a structured report: a headline tagged with a stable diagnostic ID, a compact dependency chain, a binding trace, and trailing `help:` (actionable fix), `note:` (context), and `docs:` (reference link) annotations. See the [Diagnostics Reference](diagnostics.md) for a generated index of the diagnostic IDs.

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

When a near-miss exists (same type with a different qualifier, nullability, sub/supertype, or a multibinding of the type), a `similar bindings:` section lists it with its location.

Dependency cycles are drawn as a closed loop, with `~~>` marking `@Binds` alias edges, and suggest the fix:

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

Output wraps to a fixed 100-column budget. Identifiers are never truncated, so type names stay grep-able.

## Render modes

The `diagnosticsRenderMode` Gradle option controls rendering:

```kotlin
metro {
  diagnosticsRenderMode.set(DiagnosticsRenderMode.RICH)
}
```

- `PLAIN` — the layout above: ASCII structure, no ANSI codes. Safe for any log consumer.
- `RICH` — the same layout with Unicode glyphs (`╭─▶ Double → String → Int ─╮`), ANSI color/styling, and source snippets: real source lines with the relevant spans highlighted and marked with `⌃`/`⌄` pointers, e.g. the injection site of a missing binding or each conflicting declaration of a duplicate binding.
- `AUTO` (default) — resolved at configuration time. Non-empty `NO_COLOR`, `--console=plain`, and IDE-invoked builds (whose build output windows don't render ANSI) get `PLAIN`; other builds, including CI builds, get `RICH`.

The mode can also be set via the `diagnosticsRenderMode` Gradle property or the `metro.diagnosticsRenderMode` system property (which the compiler reads directly and wins over everything). Because rendering is presentation-only, the resolved mode is excluded from compilation task inputs — switching between IDE and CLI never invalidates compilation or splits build caches.

Note that binding graph resolution currently only happens in the compiler IR backend, but maybe someday we can move this to FIR to get errors in the IDE.
