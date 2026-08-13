# Issue draft — scratch file, not intended to be committed

Title: `Adding a member to a @GraphExtension doesn't invalidate the parent @DependencyGraph under IC`

---

### Description

Changing the member set of a `@GraphExtension` interface doesn't dirty the file that declares the
parent `@DependencyGraph`, so the generated extension impl is left implementing the previous member
set. The build succeeds and the failure only shows up at runtime as an `AbstractMethodError`.

I hit this after adding an injector to an existing `@GraphExtension`:

```kotlin
@GraphExtension(scope = ActivityScope::class, bindingContainers = [BaseActivityBindingModule::class])
interface BaseActivityGraph {
  fun inject(a: LicensesActivity) // was: fun inject(a: BaseActivity)

  @GraphExtension.Factory
  interface Factory {
    fun create(): BaseActivityGraph
  }
}
```

```text
FATAL EXCEPTION: main
java.lang.AbstractMethodError: abstract method
  "void com.chickfila.cfaflagship.activities.BaseActivityGraph.inject(com.chickfila.cfaflagship.features.account.newui.LicensesActivity)"
  on receiver java.lang.Class<com.chickfila.cfaflagship.AppGraph$Impl$BaseActivityGraphImpl>
    at com.chickfila.cfaflagship.features.account.newui.LicensesActivity.onCreate(LicensesActivity.kt:55)
```

The parent graph is in a different file from the extension and never names it directly — it only
inherits `val baseActivityGraphFactory: BaseActivityGraph.Factory` from a supertype declared in a
third file. So as far as Kotlin's IC is concerned nothing in `AppGraph.kt` looked up the members
that changed, and it stays out of the dirty set.

This feels like the same family as #2531, but that fix doesn't cover extension member changes.

### Self-contained Reproducer

I added a failing test to `ICTests.kt` in a fork: `adding a member to a graph extension invalidates
the parent graph`. `AppGraph` is wired to the extension purely via `@ContributesTo` on the
extension factory, the extension gains a second `inject(...)` overload on the second build, and
`invokeMain` then throws `AbstractMethodError`.

I also confirmed it directly in my own project (single Gradle module, `--no-build-cache`, so the
build cache isn't involved). Starting from a clean build, I edited only the extension interface to
add a second injector and recompiled:

```console
$ javap -p '.../classes/com/chickfila/cfaflagship/activities/BaseActivityGraph.class'
public interface com.chickfila.cfaflagship.activities.BaseActivityGraph {
  public abstract void inject(com.chickfila.cfaflagship.activities.BaseActivity);
  public abstract void inject(com.chickfila.cfaflagship.features.account.newui.LicensesActivity);
}

$ javap -p '.../classes/com/chickfila/cfaflagship/AppGraph$Impl$BaseActivityGraphImpl.class'
final class com.chickfila.cfaflagship.AppGraph$Impl$BaseActivityGraphImpl implements ...BaseActivityGraph {
  public final void inject(com.chickfila.cfaflagship.features.account.newui.LicensesActivity);
}
```

Output timestamps confirm the parent graph was never recompiled — `BaseActivityGraph.class` was
rewritten by that build at 17:48:03, while `AppGraph$Impl.class` and
`AppGraph$Impl$BaseActivityGraphImpl.class` were untouched since the previous clean build at
17:44:46.

Forcing `AppGraph.kt` to recompile (any content change to it) produces a correct impl, and deleting
`build/kotlin/compile<Variant>Kotlin` also clears it.

Worth noting for anyone else hitting this: with `org.gradle.caching=true`, the inconsistent output
gets stored under the current input hashes, so later builds restore the broken classes even after
the sources are back to a state that previously compiled correctly.

### Metro environment

<details>
<summary>Metro environment</summary>

```text
Metro environment report

Project
  path: :app
  target:
  compilation: stagingDebug
  platform: androidJvm
  compile task: compileStagingDebugKotlin

Versions
  Metro Gradle plugin: 1.4.1
  Metro compiler artifact: dev.zacsweers.metro:compiler:1.4.1
  Kotlin Gradle plugin: 2.4.10
  Kotlin compiler: 2.4.10
  Gradle: 9.7.0
  Java: 24
  OS: Mac OS X 26.5.2 (aarch64)

Kotlin compiler options
  languageVersion: <default>
  apiVersion: <default>
  freeCompilerArgs:
    -opt-in=kotlin.RequiresOptIn
    -Xjspecify-annotations=strict
    -Xrender-internal-diagnostic-names
    -Xreturn-value-checker=check
    -Xwarning-level=DEPRECATION:disabled
    -Xwarning-level=NOTHING_TO_INLINE:disabled
    -Xwarning-level=POTENTIALLY_NON_REPORTED_ANNOTATION:disabled
    -Xwarning-level=PLATFORM_CLASS_MAPPED_TO_KOTLIN:disabled
    -Xwarning-level=OVERRIDE_DEPRECATION:disabled
    -Xwarning-level=UNCHECKED_CAST:disabled
    -Xwarning-level=OPT_IN_USAGE:disabled
    -Xwarning-level=ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD:disabled
    -Xwarning-level=PARAMETER_NAME_CHANGED_ON_OVERRIDE:disabled
    -Xwarning-level=NULLABILITY_MISMATCH_BASED_ON_EXPLICIT_TYPE_ARGUMENTS_FOR_JAVA:disabled
    -Xwarning-level=ASSIGNED_VALUE_IS_NEVER_READ:disabled
    -Xwarning-level=CAN_BE_VAL_DELAYED_INITIALIZATION:disabled
    -Xwarning-level=UNUSED_GRAPH_INPUT_WARNING:disabled
    -Xwarning-level=SUGGEST_CLASS_INJECTION:disabled
    -Xcompiler-plugin-order=dev.zacsweers.metro.compiler>androidx.compose.compiler.plugins.kotlin
    -Xcompiler-plugin-order=dev.zacsweers.metro.compiler>org.jetbrains.kotlinx.serialization
    -Xuse-inline-scopes-numbers
    -Xallow-unstable-dependencies

Metro compiler plugin options
  options:
    enabled = true
    max-ir-errors-count = 20
    debug = false
    generate-assisted-factories = false
    generate-contribution-hints = true
    generate-contribution-hints-in-fir = true
    generate-classes-in-ir = false
    enable-private-provider-properties = false
    statements-per-init-fun = 25
    enable-graph-sharding = true
    keys-per-graph-shard = 2000
    enable-switching-providers = false
    optional-binding-behavior = DEFAULT
    diagnostics-render-mode = PLAIN
    public-scoped-provider-severity = NONE
    non-public-contribution-severity = NONE
    warn-on-inject-annotation-placement = true
    interop-annotations-named-arg-severity = NONE
    unused-graph-inputs-severity = WARN
    enable-top-level-function-injection = true
    contributes-as-inject = true
    enable-klib-params-check = false
    patch-klib-params = true
    force-enable-fir-in-ide = false
    compiler-version =
    compiler-version-aliases =
    enable-function-providers = true
    enable-suspend-providers = false
    desugared-provider-severity = WARN
    generate-contribution-providers = false
    enable-circuit-codegen = false
    enable-runtime-tracing = false
    plugin-order-set = true
    enable-dagger-runtime-interop = false
    enable-kclass-to-class-interop = false
    interop-include-javax-annotations = false
    interop-include-jakarta-annotations = false
    interop-include-dagger-annotations = false
    interop-include-kotlin-inject-annotations = false
    interop-include-anvil-annotations = false
    interop-include-kotlin-inject-anvil-annotations = false
    enable-dagger-anvil-interop = false
    interop-include-guice-annotations = false
    enable-guice-runtime-interop = false
    interop-include-hilt-annotations = false
```

</details>

### Previous working version

Unknown — this is the first time I've changed this extension's member set, so I can't say whether
an earlier version handled it. Reproduced on 1.4.1.

### IDE version

N/A (reproduced from the command line).

### Platform

Android (JVM). Nothing looks Android-specific about it.

### Context

Runtime failure from a successful build, not a compiler error. I used an AI assistant to help
collect the bytecode evidence and draft the reproducer test.
