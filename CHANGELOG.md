Changelog
=========

**Unreleased**
--------------

- **New**: New `dev.zacsweers.metro:metrox-android` artifact for Android `AppComponentFactory` support. See the [docs](https://zacsweers.github.io/metro/latest/metrox-android) for more information.
- **New**: New `dev.zacsweers.metro:metrox-viewmodel` and `metrox-viewmodel-compose` artifacts for AndroidX `ViewModel` support. See the [docs](https://zacsweers.github.io/metro/latest/metrox-viewmodel) ([compose docs](https://zacsweers.github.io/metro/latest/metrox-viewmodel-compose) ) for more information.
    - > Should I use this?

      - Well, that's up to you! This artifact is mostly for projects coming from heavy use of more vanilla Android architecture components or `hiltViewModel()` use. Modern Android apps should use higher level architectures like Circuit* (Disclosure: I am one of the authors of Circuit, and I'm a big fan of it!), Voyager, etc. that abstract away `ViewModel` management.

- **New**: Add `generateMetroGraphMetadata` Gradle task that writes a merged JSON dump of all binding graphs in the project. This can be chained from the `GenerateGraphMetadataTask` for further processing.
- **New**: Add `analyzeMetroGraph` Gradle task that performs comprehensive graph analysis including fan-in/fan-out, betweenness centrality, dominator analysis, and longest path detection.
- **New**: Add `generateMetroGraphHtml` Gradle task that generates interactive HTML visualizations of dependency graphs using ECharts. Features include force/circular layouts, filtering by package/scope/synthetic bindings, metrics heatmaps, and longest path highlighting. See the [graph analysis docs](https://zacsweers.github.io/metro/latest/graph-analysis) for more information.
- **New**: Add `MetroArtifacts` Gradle API for accessing Metro reports and graph metadata directories.
- **New**: Add `supportedHintContributionPlatforms` Gradle DSL property to manually override supported hint contribution generation on different platforms. By default this computes default supported platforms based on the platform and Kotlin tooling version.
- **API Change**: Rename `generateJvmContributionHintsInFir` Gradle DSL property to `generateContributionHintsInFir`. Note this property/mode still doesn't really work and is highly experimental at the moment.
- **Behavior Change**: Add `@DelicateMetroGradleApi` annotations to relevant Gradle extension APIs.
- **Behavior Change**: The `generateContributionHints` property now computes defaults per platform and per Kotlin compilation as a `convention` and can be overridden.
- **Behavior Change**: All non-final classes with member injections or that extend classes with member injections must now be annotated with `@HasMemberInjections`. This is enforced with a new diagnostic check.
- **Enhancement**: Lazily validate multibindings. Previously, multibindings were validated eagerly even if they were unused in a graph.
- **Enhancement**: Report all duplicate bindings errors during graph construction rather than failing at first.
- **Enhancement**: Support `FirFunctionCall` evaluation in annotation arguments during FIR.
- **Enhancement**: Order before the Compose compiler on Kotlin 2.3.0+.
- **Enhancement**: Only print Metro Gradle version configuration mismatch warnings once rather than per-compilation.
- **Enhancement**: Report Metro Gradle configuration issues to Gradle's `Problems` API.
  - While this is nice for the IDE integration/Develocity integration, it's wholly hidden away from CLI users. So, Metro will still print these warnings to console too.
- **Fix**: Support member injection of classes that have no member injections but do extend superclasses with member injections.
- **Fix**: Catch more `IrErrorType` error types cases and report context/advice where possible.
- **Fix**: Dedupe binding containers contributed to both parent and child graphs.
- **Fix**: Fix support for Anvil's `exclude` argument in dependency graph annotations when Anvil interop is enabled.
- **Fix**: Fix colliding reports destinations in KMP projects by disambiguating with the target path as another subdir indirection.
- **Fix**: Fix qualifiers not being propagated to generated `MembersInjector` classes for multibindings.
- **Fix**: Remove misleading "doesn't appear to be visible to this compilation" hint, as this hint was misreporting.
- **Fix**: Fix not reserving properties multibinding contributors that are behind aliases.
- **Fix**: Stub hint function bodies if FIR-generated.
- **Fix**: Hide FIR-generated hint functions.
- **Fix**: Full support injected Java fields from Dagger-processed Java supertypes.
- **Removed**: Removed deprecated `enableScopedInjectClassHints` Gradle DSL property.
- **Removed**: Removed deprecated `enableStrictValidation` Gradle DSL property.
- **Removed**: Removed deprecated `contributesGraphExtension` Gradle DSL property.
- **Removed**: Removed deprecated `contributesGraphExtensionFactory` Gradle DSL property.
- **Removed**: Removed deprecated `optionalDependencyBehavior` Gradle DSL property.
- **Removed**: Removed deprecated `OptionalDependencyBehavior` Gradle plugin class.
- **Removed**: Removed deprecated `includeAnvil` Gradle DSL function.
- **Removed**: Removed deprecated `ContributesGraphExtension` annotation.
- **Removed**: Removed deprecated `OptionalDependency` annotation.
- [gradle] Mark `metro.reportsDestination` as delicate/opt-in.

0.7.7
-----

_2025-11-19_

- Add `MetroCompilerPluginRegistrar.pluginId` for forward compatibility with Kotlin `2.3.0`'s new API.

0.7.6
-----

_2025-11-17_

- **Fix**: Record IC lookups for merged supertypes to graph extensions.
- **Fix**: Optimize `checkScope()` diagnostics in errors by checking if roots are empty first.
- **Fix**: Fix diagnostic when an `@Multibinds`-annotated Map uses an enum as a key.
- **Fix**: Fix Dagger interop error messages when validating `@Module`s with constructor-injected fields to match errors for `@BindingContainer`s.
- **Fix**: Catch `IrErrorType` error types earlier when generating missing binding hints.
- **Fix**: Fix IC edge case when restoring a deleted contributed binding container.
- Update shaded Okio to `3.16.3`.
- Build against Gradle `9.2.1`.

Special thanks to [@jonamireh](https://github.com/jonamireh), [@neworld](https://github.com/neworld), and [@JoelWilcox](https://github.com/JoelWilcox) for contributing to this release!

0.7.5
-----

_2025-11-07_

- **New**: Add Guice interop. This is largely focused on Guice's `Provider` type, annotations, and existing interop with jakarta.inject annotations.
    ```kotlin
    metro {
      includeGuice()
    }
    ```
- **Enhancement**: Improve IR caching layer with supertype caching.
- **Enhancement**: Add diagnostic to check for `Array` properties in map key annotations that unwrap values.
- **Enhancement**: Add diagnostic to check multibinds map keys are valid (primitives, strings, `KClass`, annotation classes, not arrays).
- **Enhancement**: Improve multibinding star project checks.
- **Enhancement**: Nudge `@Binds` functions toward `private` visibility like `@Provides` functions now that it's fully supported.
- **Fix**: Don't treat `l` as an illegal char in name allocating. This was supposed to be `;`.
- **Fix**: Ensure all reserved properties by child graphs get properties in parents. Previously, there were some cases for bindings that were unused in the parent and otherwise did not meet the criteria for having a backing property would get missed in binding property collection.
- **Fix**: Fix compiler crash when injecting a target type with star generics.
- **Fix**: Fix `ClassCastException` when accessing a `Provider` contributed from a `dagger.Module`.
- Split `javax` and `jakarta` interop APIs into separate artifacts from dagger for reuse.

Special thanks to [@jonamireh](https://github.com/jonamireh) for contributing to this release!

0.7.4
-----

_2025-11-04_

- **Fix**: Support more than 32 parameters to Graph factories.
- **Fix**: Support more than 32 accessors in Graphs.
- **Fix**: Transform `INSTANCE` access types for `GraphDependency` bindings.
- **Fix**: Fix ordering of setter member injection parameters when reading injectors across modules.
- [change] When generating reports, create directory structures matching packages rather than generating all to one top-level dir.

Special thanks to [@jonamireh](https://github.com/jonamireh), [@kevinguitar](https://github.com/kevinguitar), and [@JoelWilcox](https://github.com/JoelWilcox) for contributing to this release!

0.7.3
-----

_2025-11-02_

- **New**: Support interop with Dagger/Anvil-generated member injector classes.
- **Enhancement**: Skip reading members when loading externally compiled member injector classes. Parameters are now computed from their static `inject*` functions.
- **Enhancement**: Improve logic for avoiding reserved keywords or illegal character for names in more platforms.
- **Enhancement**: Inline empty multibinding expressions in code gen.
- **Enhancement**: Better detect static-ish functions in generated Kotlin factories from Dagger/Anvil interop.
- **Enhancement**: Cache members injector binding lookups.
- **Enhancement**: Don't double-lookup members injectors already computed from roots.
- **Enhancement**: Support Kotlin `2.3.0-Beta2`.
- **Enhancement**: Test against Kotlin `2.2.21`.
- **Enhancement**: Improve generated graph impl declaration checks.
- **Fix**: Work around "LookupSymbols are not yet converted to ProgramSymbols" issue ([KT-80412](https://youtrack.jetbrains.com/issue/KT-80412)) in incremental compilation by avoiding using `$$` prefixes in generated class names.
- **Fix**: Fix interop support for two layers of `Provider` interop in map multibindings (i.e. `Provider<Map<Key, Provider<Value>>`).
- Deprecate `includeAnvil()` Gradle DSL function in favor of more specific `includeAnvilForDagger()` and `includeAnvilForKotlinInject()` functions.
- Move interop annotations controls to compiler. For Gradle users, there's mostly no change (other than the above). For users of any other build system, this makes it a bit easier to reuse the interop annotations logic.
- [docs] Add compatibility docs: https://zacsweers.github.io/metro/latest/compatibility/. Metro supports a moving range of Kotlin versions, this page captures the tested versions for each release.
- [docs] Add stability docs: https://zacsweers.github.io/metro/latest/stability/

Special thanks to [@jonamireh](https://github.com/jonamireh), [@hossain-khan](https://github.com/hossain-khan), and [@l2hyunwoo](https://github.com/l2hyunwoo) for contributing to this release!

0.7.2
-----

_2025-10-22_

- **Fix**: Fix eager initialization of some bindings going into multibindings.
- **Fix**: Fix injection of `Lazy`-wrapped multibindings.

0.7.1
-----

_2025-10-21_

**🚨 This release has a severe bug in multibinding code gen, please use 0.7.2 instead!**

- **New**: Add missing dependency hints for missing bindings errors
    ```
    [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: FooImpl

        FooImpl is injected at
            [AppGraph] Bindings.bind: FooImpl
        Base is requested at
            [AppGraph] AppGraph.base

    (Hint)
    'FooImpl' doesn't appear to be visible to this compilation. This can happen when a binding references a type from an 'implementation' dependency that isn't exposed to the consuming graph's module.
    Possible fixes:
    - Mark the module containing 'FooImpl' as an 'api' dependency in the module that defines 'Bindings' (which is requesting it).
    - Add the module containing 'FooImpl' as an explicit dependency to the module that defines 'AppGraph'.
    ```

- **Enhancement**: Improve code generation around multibinding collection builders and contributors, using more lazy getters in graph code gen.
- **Enhancement**: Short-circuit empty map providers to `emptyMap()`.
- **Enhancement**: Support default values for assisted parameter arguments in top-level function injection.
- **Enhancement**: Allow using `@Contributes*` annotations on assisted factories with `contributesAsInject` enabled.
- **Enhancement**: Allow `@OptionalBinding` annotation to be customizable/replaceable.
- **Change**: Deprecate `@OptionalDependency` in favor of `@OptionalBinding`. Same behavior, just a slightly more consistent name.
- **Fix**: Compute `Optional` instance lazily when requested as a `Provider<Optional<T>>` and the underlying optional is not empty. Only applies to `@BindsOptionalOf` interop.
- **Fix**: Don't generate duplicate `init()` functions when chunking initializers if graphs already have an explicit `init()` function.
- **Fix**: Fix support for assisted inject with no assisted params.
- **Fix**: Detect platform types in just the `kotlin` package. Previously it missed any that didn't have multiple package segments.
- **Fix**: Align unused context parameter special names on Kotlin 2.3.x.
- Remove `2.3.0-dev-7984` compat (superseded by `2.3.0-Beta1`).

Special thanks to [@Lavmee](https://github.com/Lavmee), [@kevinguitar](https://github.com/kevinguitar), and [@jackwilsdon](https://github.com/jackwilsdon) for contributing to this release!

0.7.0
-----

_2025-10-17_

### Dynamic Graphs

Dynamic graphs are a powerful new feature of the Metro compiler that allows for dynamically replacing bindings in a given graph. To use them, you can pass in a vararg set of _binding containers_ to the `createDynamicGraph()` and `createDynamicGraphFactory()` intrinsics.

```kotlin
@DependencyGraph
interface AppGraph {
  val message: String

  @Provides fun provideMessage(): String = "real"
}

class AppTest {
  val testGraph = createDynamicGraph<AppGraph>(FakeBindings)

  @Test
  fun test() {
    assertEquals("fake", testGraph.message)
  }

  @BindingContainer
  object FakeBindings {
    @Provides fun provideMessage(): String = "fake"
  }
}
```

This is particularly useful for tests. See their docs for more information: [Dynamic Graphs](https://zacsweers.github.io/metro/latest/dependency-graphs/#dynamic-graphs).

This API is experimental and may change in the future, please report any issues you encounter!

### Implicit `@Inject` behavior on (most) `@Contributes*`-annotated types

Up to this point, Metro has always required you to use `@Inject` on most `@Contributes*` annotated types. However, this can feel a bit repetitive and tedious. In this release, there is a new `contributesAsInject` option that can be enabled that will treat all `@Contributes*` annotated types as `@Inject` by default. You can still use `@Inject` on classes to be explicit, and if you have multiple constructors you must still use `@Inject` on the constructor you want to be used.

_The only exception to this is `@ContributesTo`, which isn't applicable to injected types._

This is disabled by default to start but will likely become the default in a future release.

```kotlin
@ContributesBinding(AppScope::class)
// @Inject // <-- now implicit!
class TacoImpl(...) : Taco
```

### Other Changes

- **Behavior change**: Remove `assistedInjectMigrationSeverity` DSL. You must now move fully to using `@AssistedInject` annotations for assisted types.
- **New**: Allow exposing assisted-injected classes on a graph with qualifier annotations via `@Provides` declarations. This means you could, for example, write a provider like so:
    ```kotlin
    @Provides @Named("qualified")
    fun provideTaco(factory: Taco.Factory): Taco = factory.create("spicy")
    ```
- **New**: Add diagnostic disallowing qualifier annotations directly on `@AssistedInject`-annotated classes.
- **New**: Add `wasmWasi` targets to Metro's runtime.
- **New**: Add diagnostic to report positional arguments use in custom interop annotations. See the [interop docs](https://zacsweers.github.io/metro/latest/interop#diagnostics) for more information. This is disabled by default but can be configured via the `interopAnnotationsNamedArgSeverity` option.
- **New**: Support context parameters on top-level injected functions. See the [docs](https://zacsweers.github.io/metro/latest/injection-types/#context-parameters) for more information.
- **New**: Improve diagnostic checks around binding container arguments to annotations and graph creators.
- **New**: Add a diagnostic to warn on suspicious injection of unqualified object classes.
- **Enhancement**: Add diagnostic for providing a constructor-injected class with a different scope than the class (if the class has a scope).
- **Enhancement**: Allow replacing/excluding binding containers by `@Origin` annotations.
- **Fix**: Don't use interoped annotation arguments at matching indices if their name does not match the requested name.
- **Fix**: Trace all member injection dependencies from supertypes in graph reachability computation.
- **Fix**: Use compat `getContainingClassSymbol()` (fixes Kotlin 2.3.0-x compatibility).
- **Fix**: Better escape field names to be valid in JVM.
- **Fix**: Don't double-invoke `Optional` binding fields.
- **Fix**: Don't report duplicate bindings if injectors for both a parent and child class are present on a graph.
- **Fix**: Look up correct target class ID for computed member injectors in `BindingLookup`.
- **Fix**: Don't allow binding containers to be `inner` classes.
- **Fix**: Don't allow binding containers to be local classes.
- **Fix**: Don't allow binding containers to be anonymous objects.
- **Fix**: Fix wrong parent graph name in `IncompatiblyScopedBindings` hint.
- **Fix**: Fix replacements for regular contributed types not getting processed in graph extensions.
- **Fix**: Don't re-process contribution merging for generated graph extension impls during graph node creation.
- **Fix**: Don't reserve provider fields for custom wrapper types like interoped `Optional` types, avoiding accidental eager initialization in cycles.
- Change the warning key for redundant provides to more specific `REDUNDANT_PROVIDES`.

Special thanks to [@erawhctim](https://github.com/erawhctim) and [@CharlieTap](https://github.com/CharlieTap) for contributing to this release!

0.6.10
------

_2025-10-11_

### Optional Dependency Behaviors

Graph accessors can now expose optional dependencies, just use `@OptionalDependency` on the accessor. Note that the accessor _must_ declare a default body that Metro will use if the dependency is absent.

```kotlin
@DependencyGraph
interface AppGraph {
  @OptionalDependency
  val message: String
    get() = "Absent!"
}
```

There are a couple of optional configuration for Metro's optional dependency support that can be configured via the `optionalDependencyBehavior` Gradle DSL:

- `DISABLED` - Disallows optional dependencies entirely.
- `REQUIRE_OPTIONAL_DEPENDENCY` - Requires optional dependency _parameters_ to also be annotated with `@OptionalDependency`. This may be preferable for consistency with accessors and/or explicitness.
- `DEFAULT` - The default behavior as described above — accessors must be annotated with `@OptionalDependency` with default bodies and parameters just use default value expressions.

### Other changes

- **New**: Add interop for Dagger `@BindsOptionalOf`. Note this is currently only limited to `java.util.Optional`.
- **Enhancement**: Improve error messages for unexpected `IrErrorType` encounters.
- **Enhancement**: Add configurable `statementsPerInitFun` to option to control the number of statements per init function. Only for advanced/debugging use.
- **Fix**: Allow `@Includes` types themselves (i.e., not their accessors) to be dependencies in generated graphs.
- **Fix**: Allow multiple graph extension factory accessors of the same factory type on parent graphs.
- **Fix**: Report all missing `@Provides` body diagnostics rather than returning early.
- **Fix**: Allow `open` members from abstract graph class superclasses to be accessors.
- **Fix**: When detecting default function/property getter bodies in graph accessors, check for `open` modality as well.
- **Fix**: Don't duplicate includes accessor keys across multiple parent context levels.
- **Fix**: Fix not respecting ref counting when allocating provider fields for constructor-injected class providers. This should reduce generated graph code size quite a bit.

Special thanks to [@ChristianKatzmann](https://github.com/ChristianKatzmann) for contributing to this release!

0.6.9
-----

_2025-10-07_

This release introduces new experimental support for multiple compiler and IDE versions. The primary goal of this is to better support running Metro's FIR extensions across different IntelliJ Kotlin Plugin versions and make IDE support more robust, and general compiler compatibility falls out of that more or less for free. This is experimental and only going to target _forward_ compatibility.

- **New**: Report more IR errors up to a maximum. The default is `20`, but is configurable via the `maxIrErrors` Gradle DSL option. If you want to restore the previous "fail-fast" behavior, you can set this value to `1`.
- **New**: Generate specific containing names in Kotlin 2.3.0+ when generating top-level functions for hint gen.
- **Behavior change**: Assisted-inject types can only be directly exposed on a graph if qualified.
- **Behavior change**: Update the Gradle plugin to target Kotlin 2.0, which requires Gradle `8.11` or later.
- **Enhancement**: Improve compatibility across 2.2.20 and 2.3.0+ releases. This release _should_ be compatible with both!
- **Enhancement**: Add diagnostic for directly injecting unqualified assisted-injected classes rather than using their factories.
- **Enhancement**: Add diagnostic mixing `Provider` and `Lazy` types for `Provider<Lazy<T>>` injections.
- **Enhancement**: Add diagnostics for custom map keys.
- **Enhancement**: Fully allow exposing `Provider<Lazy<T>>` accessor types.
- **Enhancement**: Significantly improve duplicate binding error message rendering.
- **Enhancement**: Inline internal `trace` functions to reduce overhead.
- **Enhancement**: Don't always generate fields for `MembersInjector` bindings.
- **Enhancement**: Improve formatting of long cycles in `DependencyCycle` error messages.
- **Enhancement**: Improve formatting of aliases in `DependencyCycle` error messages. Aliases are now indicated with `~~>` arrows instead of `-->`.
- **Enhancement**: Improve formatting of member declarations in error messages for better IDE linking (if in the IDE terminal/console output) by using `.` separators instead of `#`.
- **Fix**: Avoid obscure `UnsupportedOperationException` failures when reporting missing bindings.
- **Fix**: Only generate assisted factories if `@AssistedInject` annotations are used on the target class.
- **Fix**: Remove `PsiElement` shading workaround when reporting diagnostics.
- **Fix**: Treat `MembersInjector` types as implicitly deferrable in binding graph validation.
- **Fix**: Report cycles in form of `binding --> dependency` rather than the reverse for better readability.

Special thanks to [@kevinguitar](https://github.com/kevinguitar), [@hossain-khan](https://github.com/hossain-khan), and [@vRallev](https://github.com/vRallev) for contributing to this release!

0.6.8
-----

_2025-09-26_

- **Fix**: Preserve original nullability when canonicalizing generic types.

0.6.7
-----

_2025-09-25_

### New `@AssistedInject` annotation

Assisted-injected classes must now use `@AssistedInject` instead of `@Inject`.

This is for multiple reasons:
  - It's more explicit at the source declaration site that this uses assisted injection and cannot be requested directly on the DI graph. This is particularly useful for scenarios where there no assisted parameters but you still want to use assisted injection.
  - This will allow adding more granular checks at compile-time to validate use-sites.
  - This will allow providing assisted types directly on the graph.
  - Will simplify some of Metro's internal logic.

Note that not all internal changes are implemented yet to allow for a migration period. In this release, use of `@Inject` with `@Assisted` parameters is a compiler _warning_ and will become an error in the future. This diagnostic is configurable via the `assistedInjectMigrationSeverity` Gradle DSL option.

### Other changes

- **New**: Support for interop with externally generated Dagger modules.
- **Enhancement**: Always check for available assisted factories when reporting `InvalidBinding` errors about misused assisted injects.
- **Enhancement**: Always specifically report mismatched assisted parameter mismatches.
- **Enhancement**: Validate `Lazy` assisted factory injections in more places.
- **Enhancement**: Allow private `@Binds` properties.
- **Enhancement**: Better canonicalize flexible mutability from Dagger interop in collections and flexible nullability.
- **Enhancement**: Better canonicalize flexible nullability from Dagger interop in generic type arguments.
- **Enhancement**: Simplify assisted factory impl class generation by moving it entirely to IR.
- **Enhancement**: Allow qualifier narrowing but not widening on graph accessor types. Essentially, you can have a base interface with an unqualified accessor and then override that to add a qualifier in a subtype, but not the other way around.
- **Fix**: Register `MetroDiagnostics` in FIR.
- **Fix**: Use correct severity when reporting warnings to `MessageCollector` from newer IR diagnostics factories.
- **Fix**: When transforming FIR override statuses, check all supertypes and not just immediate supertype.
- **Fix**: Carry qualifiers over from Dagger inject constructors when interoping with dagger factories.
- If Dagger runtime interop is enabled, do not run status transformation on `@Provides` declarations in dagger modules.

Special thanks to [@kevinguitar](https://github.com/kevinguitar), [@ChristianKatzmann](https://github.com/ChristianKatzmann), and [@hossain-khan](https://github.com/hossain-khan) for contributing to this release!

0.6.6
-----

_2025-09-11_

- **Enhancement:** Optimize annotation lookups in some places in IR.
- **Fix:** If a graph declares an overridable declaration that matches one of a contributed supertype, transform it to add the requisite `override` modifier.
    - All that is to say, this code now works
      ```kotlin
      @ContributesTo(AppScope::class)
      interface StringRequester {
        val string: String
      }

      @DependencyGraph(AppScope::class)
      interface AppGraph {
        val string: String // <-- previously failed to compile due to missing override
      }
      ```
- Update to Kotlin `2.2.20`. This release requires `2.2.20` or later. See the compatibility [docs](https://zacsweers.github.io/metro/latest/compatibility).

Special thanks to [@kevinguitar](https://github.com/kevinguitar) for contributing to this release!

0.6.5
-----

_2025-09-11_

* **New**: Add `@Origin` annotation for custom code generators to link origin classes. See the [docs](https://zacsweers.github.io/metro/latest/generating-metro-code.html#origin-annotations).
* **Fix**: Fix wrong `IrType` for default value expressions wrapped in `Provider`.

Special thanks to [@ChristianKatzmann](https://github.com/ChristianKatzmann), [@KevinGuitar](https://github.com/KevinGuitar), and [@hossain-khan](https://github.com/hossain-khan) for contributing to this release!

0.6.4
-----

_2025-09-01_

* **Enhancement**: Transform and collect contribution data in a single pass during IR.
* **Fix**: Ensure contributed binding containers' included containers are available in root dependency graphs.
* **Fix**: Make `@Includes` parameter keys available to extensions.
* **Fix**: Fix an edge case where an included binding container that's transitively included by another container is seen to have zero bindings.
* **Fix**: Report diagnostic errors to check that binding containers don't extend other binding containers.
* **Fix**: Report diagnostic errors if accessors or injectors have conflicting qualifiers in overridden functions.
* **Fix**: Report diagnostic errors if an injector function does not return `Unit`.

Special thanks to [@joelwilcox](https://github.com/joelwilcox), [@vRallev](https://github.com/vRallev), [@kevinguitar](https://github.com/kevinguitar), and [@erawhctim](https://github.com/erawhctim) for contributing to this release!

0.6.3
-----

_2025-08-23_

* **Enhancement**: Allow `@Includes` parameters that are binding containers to transitively include other binding containers.
* **Fix**: Ensure provider fields for graph instances when needed by extensions.

Special thanks to [@hossain-khan](https://github.com/hossain-khan), [@vRallev](https://github.com/vRallev), and [@erawhctim](https://github.com/erawhctim) for contributing to this release!

0.6.2
-----

_2025-08-22_

* **Enhancement**: Add diagnostic for contributed binding containers with no (visible) no-arg constructor.
* **Enhancement**: Add hint for missing bindings if the binding appears to be contributed by an `internal` type in another, non-friend module.
* **Fix**: Don't allocate new fields for deferred bindings reserved by extension graphs.
* **Fix**: Allow graph extensions to expose their own factories if inherited from parents.

Special thanks to [@KevinGuitar](https://github.com/KevinGuitar), [@hossain-khan](https://github.com/@hossain-khan) and [@ChrisBanes](https://github.com/ChrisBanes) for contributing to this release!

0.6.1
-----

_2025-08-20_

* **New**: Add a diagnostic to report parent keys used by graph extensions `parent-keys-used-*.txt`.
* **Enhancement**: Graph extensions are now generated as `inner` classes, reducing much of the necessary generated code in parent graphs and allowing them to access parent binding fields directly.
* **Enhancement**: Allow graph extensions to depend on other graph extensions within the context of their parent graph.
* **Enhancement**: Add a diagnostic for graph factories with vararg parameters.
* **Enhancement**: Allow graph extension factories to participate in the binding graph, which then allows injecting or binding them like any other dependency.
* **Enhancement**: Improve error message location accuracy for missing bindings when reporting from a `@Binds` declaration.
* **Fix**: Don't override graph extension factories' default functions.
* **Fix**: Fix Kotlin internal error overriding Metro error when there's a missing factory for a Java `@Inject` class.
* [Docs] The project website is now versioned. This means you can read the documentation at different versions:
  * Latest release: https://zacsweers.github.io/metro/latest/
  * Snapshots (example): https://zacsweers.github.io/metro/0.7.0-SNAPSHOT/
  * Past release (example): https://zacsweers.github.io/metro/0.6.0/
* Deprecate the `enableStrictValidation` Gradle property in favor of `enableFullBindingGraphValidation`, which aligns with [Dagger's (better) name for the same functionality](https://dagger.dev/dev-guide/compiler-options#full-binding-graph-validation).
* Update Wire to `5.3.11`.

Special thanks to [@hossain-khan](https://github.com/hossain-khan) and [@JoelWilcox](https://github.com/JoelWilcox) for contributing to this release!

0.6.0
-----

_2025-08-15_

### Graph extensions are no longer detached.

**TL;DR: Metro graph extensions are now wired similar to Dagger subcomponents and use a new `@GraphExtension` annotation. `@Extends` and `isExtendable` are now deleted, `@ContributesGraphExtension` and `enableScopedInjectClassHints()` are deprecated.**

Up to this point, Metro's graph extensions have been _detached_. This meant that extensions could simply depend on a parent graph via `@Extends` and parent graphs had to mark themselves as extendable via `isExtendable = true`. This approach mirrored kotlin-inject's approach and was convenient in its flexibility. However, it's proven too problematic in practice for a few reasons:

1. Parent graphs have to generate a bunch of extra code for extensions. Namely, scoped providers and any instances of containers/parents they are holding on to need accessors. It also generates extra metadata (metro serializes its own metadata to its types) for separate graphs to read.
2. Because of the above, parent graphs had to opt-in to extension via `isExtendable = true`.
3. Up to this point, parent graphs always held scoped providers for all `@Provides` bindings in it or containers, _even if they do not use them_.
4. Similar to #3, we've had to add support for automatic discovery of scoped constructor-injected classes (via `enableScopedInjectClassHints()`) to ensure they are also held at the appropriate scope.
5. This has ended up causing a lot of headaches because eager validation complicates these in scenarios where you have multiple graphs that may not actually use that class anywhere (and thus not provide some of its dependencies)
6. Because every graph must expose every available binding to unknown extensions, every graph in a chain is often bloated with bindings it doesn't use.

Metro _could_ optimize the `@ContributesGraphExtension` cases where Metro's compiler has a view of the entire graph chain, but that would frankly leave Metro with a lot of edge cases to deal with and users with needing to know about two different ways to extend graphs. We opted against that, and instead are now going to process graph extensions in a similar way to Dagger's **subcomponents**.

This will allow Metro to

1. Fully optimize the whole graph chain.
2. Automatically scope bindings in parents (no need to expose accessors for scoped bindings unused in parents).
3. Only generate _exactly_ the bindings that are used in each graph with lazy validation of bindings.

#### `@GraphExtension`

`@GraphExtension` is a new annotation to denote a graph that is an extension. This is analogous to Dagger's `@Subcomponent` and dagger interop treats it as such.

To connect an extension to a parent graph, you can do one of multiple ways:

- Declare an accessor on the parent graph directly.

```kotlin
@GraphExtension
interface LoggedInGraph

@DependencyGraph
interface AppGraph {
  val loggedInGraph: LoggedInGraph
}
```

- (If the extension has a creator) declare the creator on the parent graph directly.

```kotlin
@GraphExtension
interface LoggedInGraph {
  @GraphExtension.Factory
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph
interface AppGraph {
  val loggedInGraphFactory: LoggedInGraph.Factory
}
```

- (If the extension has a creator) make the parent graph implement the creator.

```kotlin
@GraphExtension
interface LoggedInGraph {
  @GraphExtension.Factory
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph
interface AppGraph : LoggedInGraph.Factory
```

- Contribute the factory to the parent graph via `@ContributesTo`.

```kotlin
@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph
```

#### Migration

The following APIs have been removed or deprecated:

- `@Extends`. Migrate to `@GraphExtension`, remove this parameter, and expose the factory in the parent graph API as documented above.
- `isExtendable` is removed from `@DependencyGraph` and `@ContributesGraphExtension`.
- `@ContributesGraphExtension` is now deprecated and treated like `@GraphExtension`.
- `@ContributesGraphExtension.Factory` is deprecated with **error** severity and requires migration to the new `@ContributesTo` pattern.
- `enableScopedInjectClassHints()` is now deprecated and does nothing. It will be removed in the future.
- Graph extensions may no longer have multiple direct parents.

To create graph extensions, you now _must_ do so via a parent graph (using one of the above connecting mechanisms).

### Other changes

- **Breaking change**: Rename `custom-graph` compiler option to `custom-dependency-graph`.
- **Breaking change**: Rename `custom-dependency-graph-factory` compiler option to `custom-dependency-graph-factory`.
- **Breaking change**: Rename `MetroPluginExtension.graph` gradle extension property to `MetroPluginExtension.dependencyGraph`.
- **Breaking change**: Rename `MetroPluginExtension.graphFactory` gradle extension property to `MetroPluginExtension.dependencyGraphFactory`.
- **Behavior change**: `@Provides` and `@Binds` bindings are now only validated if they are used by the _owning_ graph. Previously, they were always validated.
    - If you want to keep the previous behavior, you can enable the `enableStrictValidation()` option.
- **Behavior change**: `chunkFieldInits()` is now enabled by default.
- **Behavior change**: When adding bindings from extended parent graphs, ignore any that are provided directly in the child graph. Previously, Metro only ignored the binding if the binding was itself a graph type.
- **New**: Add diagnostic reports for (valid) cycles. This means if you have a cycle in your graph and enable a `reportsDestination`, Metro will generate files with a list of all the keys in that cycle.
- **Enhancement**: In tracing logs, include the graph name in the "Transform dependency graph" sections.
- **Enhancement**: Allow contributing annotations on assisted-injected classes.
- **Enhancement**: Improve dagger interop with `dagger.Lazy` types by allowing `Provider` subtypes to be wrapped too.
- **Enhancement**: Support `rank` interop on Anvil annotations in contributed graph extensions.
- **Enhancement**: Support `ignoreQualifier` interop on Anvil annotations in contributed graph extensions.
- **Enhancement**: Only process contributions to the consuming graph's scopes when processing `rank` replacements in FIR.
- **Enhancement**: Improve error message for invalid assisted inject bindings to injected target.
- **Enhancement**: Report similar bindings in missing binding errors where the similar binding doesn't have a qualifier but the requested binding does. Previously we only reported if the similar binding had a qualifier and the requested binding didn't.
- **Fix**: Don't link expect/actual declarations if they're in the same file.
- **Fix**: Don't copy map keys over into generated `@Binds` contributions unless it's an `@IntoMap` binding.
- **Fix**: Fall back to annotation sources if needed when reporting errors with bound types in FIR.
- **Fix**: Use `MapProviderFactory.builder().build()` for Dagger interop on `Map<Key, Provider<Value>>` types as there is no `MapProviderFactory.empty()`.
- **Fix**: Don't assume `@ContributesGraphExtension` to have aggregation scopes during graph generation.
- **Fix**: When extending graphs, ignore bindings of the same type as the inheriting graph.
- **Fix**: Propagate parent graph empty `@Multibinds` declarations to extensions.
- **Fix**: Propagate managed binding containers to extension graphs.
- **Fix**: Propagate transitively included binding containers contributed to contributed graphs (sorry, word soup).
- **Fix**: Make generated multibinding element IDs stable across compilations.
- **Fix**: Handle location-less declarations when reporting invalid assisted inject bindings.
- **Fix**: Don't chunk parent graph validation statements as these must be in the original constructor body.
- **Fix**: Fix wrong receiver context for chunked field initializers.
- **Fix**: Fix support for generic private injected constructors.
- [internal change] Simplify metadata and just use accessor annotations.
- [internal change] Graph extension impls are now generated as nested classes within the generated metro graph that they are contributed to.
- Update to Kotlin `2.2.10`.

Special thanks to [@hossain-khan](https://github.com/hossain-khan), [@Egorand](https://github.com/Egorand), [@kevinguitar](https://github.com/kevinguitar), [@jonapoul](https://github.com/jonapoul), and [@martinbonnin](https://github.com/martinbonnin) for contributing to this release!

0.5.5
-----

_2025-08-02_

- **Fix**: Fix Wire shading in native targets.

0.5.4
-----

_2025-08-01_

- **Enhancement**: Support `excludes`, `bindingContainers`, and `additionalScopes` in `@ContributesGraphExtension`.
- **Enhancement**: Allow binding containers and regular contributed classes to replace each other in contribution merging.
- **Enhancement**: Allow `@ElementsIntoSet` on properties.
- **Enhancement**: Don't run FIR extensions on Java sources.
- **Fix**: Report incompatible scopes in nested contributed graphs to `MessageCollector` until Kotlin 2.2.20.
- **Fix**: Report binding issues from externally contributed graphs to `MessageCollector` until Kotlin 2.2.20.
- **Fix**: Preserve nullability when remapping type parameters.
- **Fix**: Don't double-add `@ContributesTo` contributions while merging contributed graphs.
- **Fix**: Check `rawStatus` for overrides when merging contributed supertypes.
- **Fix**: Correctly extract the element type when creating implicit `Set` multibindings from `@ElementsIntoSet` contributors.
- **Fix**: Check `additionalScopes` when merging binding containers too.
- **Fix**: Don't fail if multiple contributing annotations on binding containers match the target scope when aggregating them.
- **Fix**: Dedupe binding containers during graph node generation.
- **Fix**: Add a checker for `@Provides` constructor parameters in binding containers.
- **Fix**: Fix reading repeated external contributed annotations.
- **Fix**: Filter by matching scopes when merging contributed types with repeated annotations.

Special thanks to [@hossain-khan](https://github.com/hossain-khan), [@gabrielittner](https://github.com/gabrielittner), [@kevinguitar](https://github.com/kevinguitar), [@JoelWilcox](https://github.com/JoelWilcox), and [@martinbonnin](https://github.com/martinbonnin) for contributing to this release!

0.5.3
-----

_2025-07-28_

- **Behavior change:** The `enableScopedInjectClassHints` option is no longer enabled by default. This option is tricky to get right and will be iterated on further in [#764](https://github.com/ZacSweers/metro/issues/764).
- **Enhancement:** Generate synthetic `$$BindsMirror` classes to...
    - support full IC compatibility with changing annotations and return types on `@Binds` and `@Multibinds` declarations
    - allow these declarations to be `private`
- **Enhancement:** Allow `@Binds` and `@Multibinds` functions to be private.
- **Enhancement:** Allow "static graphs" via companions implementing the graph interface itself.
- **Enhancement:** Allow graphs to aggregate `internal` contributions from other compilations IFF those compilations are marked as friend paths. This mainly allows for test graphs to consume contributions from their corresponding main source sets.
- **Enhancement:** Allow `internal` graphs to extend `internal` contributed interfaces from other compilations IFF those compilations are marked as friend paths.
- **Fix:** Sort soft edges before hard edges within (valid) cycles. Previously we would just apply a standard topological sort here, but in this scenario we want to add extra weight to ready-up nodes that depend directly on the deferred type being used to break the cycle first.
- **Fix:** When recording IC lookups of overridable declarations, only record the original declaration and not fake overrides.
- **Fix:** Record IC lookups to `@Multibinds` declarations.
- **Fix:** Write `@Multibinds` information to metro metadata.
- **Fix:** Always write metro metadata to `@BindingContainer` classes, even if empty.
- **Fix:** When `@Includes`-ing other graphs, link against the original interface accessor rather than the generated `$$MetroGraph` accessor.
- **Fix:** Disambiguate contributed nullable bindings from non-nullable bindings.
- **Fix:** When computing `@Includes` graph dependencies from accessors, only consider directly included graphs and not transitively included graphs.
- **Fix:** Expose `@Includes` graph dependencies as synthetic `_metroAccessor` types for extended graphs rather than exposing the included graph directly.
- **Fix:** Prohibit calling `.asContribution()` on `@ContributesGraphExtension`-annotated types. `@ContributesGraphExtension`-annotated types cannot be validated at compile-time with this function as their generated class is definitionally contextual and the compiler cannot infer that from callsites of this function alone.
- **Fix:** Only process `@DependencyGraph` types in FIR supertype generation. Contributed graph extension supertypes are merged only in IR.
- **Fix:** Generate `$$MetroContribution` binds functions before aggregating contributions.
- **Fix:** Don't short-circuit class visiting in contribution visiting in IR.
- **Fix:** Propagate property annotations for `@Provides`-properties, previously only the accessor function annotations were being included.
- **Fix:** Propagate class annotations for `@Inject`-annotated constructors to factory class mirror functions, previously only the constructor's annotations were being included.
- **Fix:** Fix dispatch receiver for `DelegateFactory` fields when `chunkFieldInits` is enabled.
- **Fix:** Fix compilation error for members-injected classes with no direct, but only inherited `@Inject` attributes.
- **Fix:** Always look up member injectors of ancestor classes of classes member-injected by graphs (sorry, word soup I know).
- **Fix:** Ensure `$$MetroContribution` interfaces are not generated for binding containers by ensuring binding container annotations are readable during their generation.
- Change to `UnsupportedOperationException` for compiler intrinsic stubs, matching what the stdlib does.
- Add a `ViewModel` assisted injection example to `compose-navigation-app` sample.
- Small improvements to the doc site (404 page, favicon, etc.)

Special thanks to [@hossain-khan](https://github.com/hossain-khan), [@bnorm](https://github.com/bnorm), [@yschimke](https://github.com/yschimke), [@kevinguitar](https://github.com/kevinguitar), and [@JoelWilcox](https://github.com/JoelWilcox) for contributing to this release!

0.5.2
-----

_2025-07-21_

- **Enhancement**: De-dupe contributions before processing in contributed graphs.
- **Fix**: Don't extend contributed binding container classes in generated contributed graphs.
- Small doc fixes.

Special thanks to [@bnorm](https://github.com/bnorm) and [@alexvanyo](https://github.com/alexvanyo) for contributing to this release!

0.5.1
-----

_2025-07-18_

- **Breaking change:** Rename the `generateHintProperties` Gradle DSL property to `generateContributionHints`.
- **Enhancement:** Chunk field initializers and constructor statements across multiple init functions to avoid `MethodTooLargeException` in large graphs. This is currently experimental and gated behind the `chunkFieldInits()` Gradle DSL.
- **Enhancement:** Mark generated factories and member injectors' constructors as `private`, matching the same [change in Dagger 2.57](https://github.com/google/dagger/releases/tag/dagger-2.57).
- **Enhancement:** Add a new Metro option `warnOnInjectAnnotationPlacement` to disable suggestion to lift @Inject to class when there is only one constructor, the warning applies to constructors with params too.
- **Fix:** Fix `@Contributes*.replaces` not working if the contributed type is in the same compilation but a different file.
- **Fix:** Fix generated `MembersInjector.create()` return types' generic argument to use the target class.
- **Fix:** Don't generated nested MetroContribution classes for binding containers.
- **Fix:** Fix contributing binding containers across compilations.

Special thanks to [@kevinguitar](https://github.com/kevinguitar) and [@ChristianKatzmann](https://github.com/ChristianKatzmann) for contributing to this release!

0.5.0
-----

_2025-07-14_

- **New:** Experimental support for "binding containers" via `@BindingContainer`. See [their docs](https://zacsweers.github.io/metro/dependency-graphs#binding-containers) for more details.
- **New:** Add `keys-scopedProviderFields-*.txt` and `keys-providerFields-*.txt` reports to see generated field reports for graphs.
- **Enhancement:** Remove `Any` constraint from `binding<T>()`, allowing bindings to satisfy nullable variants.
- **Enhancement:** Add diagnostic to check for scoped `@Binds` declarations. These are simple pipes and should not have scope annotations.
- **Enhancement:** Move graph dependency cycle checks to earlier in validation.
- **Enhancement:** When using Dagger interop, default `allowEmpty` to true when using Dagger's `@Multibinds` annotation.
- **Enhancement:** Make Dagger interop providers/lazy instances a `dagger.internal.Provider` internally for better compatibility with Dagger internals. Some dagger-generated code assumes this type at runtime.
- **Enhancement:** Support javax/jakarta `Provider` types as multibinding Map value types when Dagger interop is enabled.
- **Enhancement:** Completely skip processing local and enum classes as they're irrelevant to Metro's compiler.
- **Enhancement:** When reporting `@Binds` declarations in binding stacks, report the original declaration rather than inherited fake overrides.
- **Enhancement:** Add interop support for kotlin-inject's `@AssistedFactory` annotations.
- **Enhancement:** Add diagnostic to check for graph classes directly extending other graph classes. You should use `@Extends`.
- **Enhancement:** Add diagnostic to check for `@Assisted` parameters in provides functions.
- **Enhancement:** Add diagnostic to check duplicate `@Provides` declaration names in the same class.
- **Fix:** Within (valid) cycles, topographically sort bindings within the cycle. Previously these would fall back to a deterministic-but-wrong alphabetical sort.
- **Fix:** Handle enum entry arguments to qualifier, scope, and map key annotations.
- **Fix:** Report the original location of declarations in fake overrides in error reporting.
- **Fix:** Handle default values on provides parameters with absent bindings during graph population.
- **Fix:** Don't try to read private accessors of `@Includes` parameters.
- **Fix:** Don't quietly stub accessors for missing `Binding.Provided` bindings.
- **Fix:** Check constructor-annotated injections when discovering scoped classes in parent graphs.
- **Fix:** Fix `BaseDoubleCheck.isInitialized()`.
- **Fix:** Gracefully fall back to `MessageCollector` for graph seal and contributed graph errors on sourceless declarations.
- **Fix:** Fix supporting overloads of binds functions from parent graphs or external supertypes.
- **Fix:** Fix generating binding functions with names that contain dashes.
- **Fix:** Treat interop'd Dagger/Anvil/KI components as implicitly extendable.
- **Fix:** Record lookups of `@Binds` declarations for IC.
- **Fix:** Record lookups of generated class factories and their constructor signatures for IC.

Special thanks to [@kevinguitar](https://github.com/kevinguitar), [@gabrielittner](https://github.com/gabrielittner), [@chrisbanes](https://github.com/chrisbanes), [@yschimke](https://github.com/yschimke), and [@ajarl](https://github.com/ajarl) for contributing to this release!

0.4.0
-----

_2025-06-23_

- **New:** Injected constructors may now be private. This can be useful for scenarios where you want `@Inject`-annotated constructors to only be invokable by Metro's generated code.
- **New:** If reporting is enabled, write unused bindings diagnostics to `keys-unused-*.txt`.
- **New:** Support for generic assisted injection.
- **New:** Support for generic member injection.
- **New:** Add diagnostic to prohibit type parameters on injected member functions.
- **Enhancement:** Enable child graphs to depend on parent-scoped dependencies that are unused and not referenced in the parent scope. This involves generating hints for scoped `@Inject` classes and is gated on a new Metro option `enableScopedInjectClassHints`, which is enabled by default.
- **Enhancement:** Check for context parameters in top-level function injection checker.
- **Enhancement:** Store member injection info in metro metadata to slightly optimize member injection code gen.
- **Enhancement:** Avoid writing providers fields in graphs for unused bindings.
- **Enhancement:** Improve missing binding trace originating from root member injectors.
- **Fix:** Fix support for generic injected constructor parameters.
- **Fix:** Fix support for repeated contributes annotations by moving contribution binding function generation to IR.
- **Fix:** Ensure scope/qualifier annotation changes on constructor-injected classes dirty consuming graphs in incremental compilation.
- **Fix:** Report member injection dependencies when looking up constructor-injected classes during graph population.
- **Fix:** Disable IR hint generation on JS targets too, as these now have the same limitation as native/WASM targets in Kotlin 2.2. Pending upstream support for generating top-level FIR declarations in [KT-75865](https://youtrack.jetbrains.com/issue/KT-75865).
- **Fix:** Ensure private provider function annotations are propagated across compilation boundaries.
- **Fix:** Substitute copied FIR type parameter symbols with symbols from their target functions.
- **Fix:** Improved support for generic member injection.
- **Fix:** Propagate qualifiers on graph member injector functions.
- **Fix:** Fix support for requesting `MembersInjector` types.
- [internal] Report IR errors through `IrDiagnosticReporter`.
- [internal] Significantly refactor + simplify IR parameter handling.
- Fix publishing Apple native targets in snapshots.
- Update to Kotlin `2.2.0`.
- Update Gradle plugin to target Kotlin language version to `1.9` (requires Gradle 8.3+).

Special thanks to [@kevinguitar](https://github.com/kevinguitar), [@gabrielittner](https://github.com/gabrielittner), and [@JoelWilcox](https://github.com/JoelWilcox) for contributing to this release!

0.3.8
-----

_2025-06-16_

- **Enhancement:** Disambiguate `MetroContribution` class names based on scope to better support IC when changing scopes.
- **Enhancement:** Minimize deferred types when breaking cycles.
- **Fix:** Disallow injection of `Lazy<T>` where `T` is an `@AssistedFactory`-annotated class.
- **Fix:** Don't short-circuit assisted injection validation if only an accessor exists.
- **Fix:** Allow cycles of assisted factories to their target classes.
- Update shaded okio to `3.13.0`.
- Update atomicfu to `0.28.0`.

Special thanks to [@kevinguitar](https://github.com/kevinguitar), [@bnorm](https://github.com/bnorm), and [@JoelWilcox](https://github.com/JoelWilcox) for contributing to this release!

0.3.7
-----

_2025-06-08_

- **Fix:** Record lookups of generated static member inject functions for IC.
- **Fix:** Dedupe merged overrides of `@Includes` accessors.

Special thanks to [@kevinguitar](https://github.com/kevinguitar) for contributing to this release!

0.3.6
-----

_2025-06-06_

- **New:** Add new `Provider.map`, `Provider.flatMap`, `Provider.zip`, and `Provider.memoize` utility APIs.
- **Enhancement:** Improve graph validation performance by avoiding unnecessary intermediate sorts (again).
- **Enhancement:** Fail eagerly with a clear error message if `languageVersion` is too old.
- **Enhancement:** Validate improperly depending on assisted-injected classes directly at compile-time.
- **Fix:** Support constructing nested function return types for provider functions.
- **Fix:** Propagate `@Include` bindings from parent graphs to extension graphs.
- **Fix:** Reparent copied lambda default values in IR.
- [internal] Make internal renderings of `IrType` more deterministic.

Special thanks to [@kevinguitar](https://github.com/kevinguitar) for contributing to this release!

0.3.5
-----

_2025-05-31_

- **New:** Implement top-level function injection checkers.
- **Change:** Disallow top-level function injections to be scoped.
- **Fix:** Support type parameters with `where` bounds.
- **Fix:** Support injected class type parameters with any bounds.
- **Fix:** Support generic graph factory interfaces.
- **Fix:** In the presence of multiple contributing annotations to the same scope, ensure only hint function/file is generated.
- **Fix:** Improve shading to avoid packaging in stdlib and other dependency classes.
- **Fix:** Revert [#483](https://github.com/ZacSweers/metro/pull/483) as it broke some cases we haven't been able to debug yet.

Special thanks to [@gabrielittner](https://github.com/gabrielittner) and [@kevinguitar](https://github.com/kevinguitar) for contributing to this release!

0.3.4
-----

_2025-05-27_

- **Enhancement:** Use a simple numbered (but deterministic) naming for contributed graph classes to avoid long class names.
- **Enhancement:** Improve graph validation performance by avoiding unnecessary intermediate sorts.
- **Enhancement:** Move binding validation into graph validation step.
- **Enhancement:** Avoid unnecessary BFS graph walk in provider field collection.
- **Fix:** Fix provider field populating missing types that previously seen types dependent on.

Special thanks to [@ChristianKatzmann](https://github.com/ChristianKatzmann) and [@madisp](https://github.com/madisp) for contributing to this release!

0.3.3
-----

_2025-05-26_

- **Enhancement:** Don't unnecessarily wrap `Provider` graph accessors.
- **Enhancement:** Allow multiple contributed graphs to the same parent graph.
- **Fix:** Don't unnecessarily recompute bindings for roots when populating graphs.
- **Fix:** Better handle generic assisted factory interfaces.
- **Fix:** Use fully qualified names when generating hint files to avoid collisions.
- **Fix:** Support provides functions with capitalized names.
- **Fix:** Prohibit consuming `Provider<Lazy<...>>` graph accessors.
- [internal] Migrate to new IR `parameters`/`arguments`/`typeArguments` compiler APIs.

Special thanks to [@gabrielittner](https://github.com/gabrielittner) for contributing to this release!

0.3.2
-----

_2025-05-15_

- **Enhancement**: Optimize supertype lookups in IR.
- **Fix**: Fix generic members inherited from generic supertypes of contributed graphs.
- **Fix**: Fix `@ContributedGraphExtension` that extends the same interface as the parent causes a duplicate binding error.
- **Fix**: Fix contributed binding replacements not being respected in contributed graphs.
- **Fix**: Fix contributed providers not being visible to N+2+ descendant graphs.
- **Fix**: Collect bindings from member injectors as well as exposed accessors when determining scoped provider fields.
- **Fix**: Fix a few `-Xverify-ir` and `-Xverify-ir-visibility` issues + run all tests with these enabled now.

Special thanks to [@bnorm](https://github.com/bnorm), [@gabrielittner](https://github.com/gabrielittner), [@kevinguitar](https://github.com/kevinguitar), and [@JoelWilcox](https://github.com/JoelWilcox) for contributing to this release!

0.3.1
-----

_2025-05-13_

- **Enhancement**: Rewrite graph resolution using topological sorting to vastly improve performance and simplify generation.
- **Enhancement**: Return early once an externally-compiled dependency graph is found.
- **Enhancement**: Simplify multibinding contributor handling in graph resolution by generating synthetic qualifiers for each of them. This allows them to participate in standard graph resolution.
- **Enhancement**: When there are multiple empty `@Multibinds` errors, report them all at once.
- **Enhancement**: Avoid unnecessary `StringBuilder` allocations.
- **Fix**: Don't transform `@Provides` function's to be private if its visibility is already explicitly defined.
- **Fix**: Fix a comparator infinite loop vector.
- **Fix**: Fix `@ElementsIntoSet` multibinding contributions triggering a dependency cycle in some situations.
- **Fix**: Fix assertion error for generated multibinding name hint when using both @Multibinds and @ElementsIntoSet for the same multibinding.
- **Fix**: Fix contributed graph extensions not inheriting empty declared multibindings.
- **Fix**: Ensure we report the `@Multibinds` declaration location in errors if one is available.
- **Fix**: Dedupe overrides by all parameters not just value parameters.
- **Fix**: Dedupe overrides by signature rather than name when generating contributed graphs.
- **Fix**: Fix accidentally adding contributed graphs as child elements of parent graphs twice.
- **Fix**: Fix not deep copying `extensionReceiverParameter` when implementing fake overrides in contributed graphs.
- **Fix**: Report fully qualified qualifier renderings in diagnostics.
- **Fix**: Don't generate provider fields for multibinding elements unnecessarily.
- When debug logging + reports dir is enabled, output a `logTrace.txt` to the reports dir for tracing data.
- Update to Kotlin `2.1.21`.

Special thanks to [@asapha](https://github.com/asapha), [@gabrielittner](https://github.com/gabrielittner), [@jzbrooks](https://github.com/jzbrooks), and [@JoelWilcox](https://github.com/JoelWilcox) for contributing to this release!

0.3.0
-----

_2025-05-05_

- **New**: Add support for `@ContributesGraphExtension`! See the [docs](https://zacsweers.github.io/metro/dependency-graphs#contributed-graph-extensions) for more info.
- **New**: Add a `asContribution()` compiler intrinsic to upcast graphs to expected contribution types. For example: `val contributedInterface = appGraph.asContribution<ContributedInterface>()`. This is validated at compile-time.
- **New**: Automatically transform `@Provides` functions to be `private`. This is enabled by defaults and supersedes the `publicProviderSeverity` when enabled, and can be disabled via the Gradle extension or `transform-providers-to-private` compiler option. Note that properties and providers with any annotations with `KClass` arguments are not supported yet pending upstream kotlinc changes.
- **Enhancement**: Rewrite the internal `BindingGraph` implementation to be more performant, accurate, and testable.
- **Enhancement**: Add diagnostic to check that graph factories don't provide their target graphs as parameters.
- **Enhancement**: Add diagnostic to check that a primary scope is defined if any additionalScopes are also defined on a graph annotation.
- **Enhancement**: Add diagnostic to validate that contributed types do not have narrower visibility that aggregating graphs. i.e. detect if you accidentally try to contribute an `internal` type to a `public` graph.
- **Enhancement**: Optimize supertype lookups when building binding classes by avoiding previously visited classes.
- **Enhancement**: Don't generate hints for contributed types with non-public API visibility.
- **Enhancement**: When reporting duplicate binding errors where one of the bindings is contributed, report the contributing class in the error message.
- **Enhancement**: When reporting scope incompatibility, check any extended parents match the scope and suggest a workaround in the error diagnostic.
- **Enhancement**: Allow AssistedFactory methods to be protected.
- **Fix**: Fix incremental compilation when a parent graph or supertype modifies/removes a provider.
- **Fix**: Fix rank processing error when the outranked binding is contributed using Metro's ContributesBinding annotation.
- **Fix**: Fix `@Provides` graph parameters not getting passed on to extended child graphs.
- **Fix**: Fix qualifiers on bindings not getting seen by extended child graphs.
- **Fix**: Fix qualifiers getting ignored on accessors from `@Includes` dependencies.
- **Fix**: Fix transitive scoped dependencies not always getting initialized first in graph provider fields.
- **Fix**: Fix injected `lateinit var` properties being treated as if they have default values.
- **Fix**: Alias bindings not always having their backing type visited during graph validation.
- **Fix**: Fix race condition in generating parent graphs first even if child graph is encountered first in processing.
- **Fix**: Fallback `AssistedInjectChecker` error report to the declaration source.
- **Fix**: Fix missing parent supertype bindings in graph extensions.
- **Change**: `InstanceFactory` is no longer a value class. This wasn't going to offer much value in practice.
- **Change**: Change debug reports dir to be per-compilation rather than per-platform.

Special thanks to [@gabrielittner](https://github.com/gabrielittner), [@kevinguitar](https://github.com/kevinguitar), [@JoelWilcox](https://github.com/JoelWilcox), and [@japplin](https://github.com/japplin) for contributing to this release!

0.2.0
-----

_2025-04-21_

- **New**: Nullable bindings are now allowed! See the [nullability docs](https://zacsweers.github.io/metro/bindings#nullability) for more info.
- **Enhancement**: Add diagnostics for multibindings with star projections.
- **Enhancement**: Add diagnostic for map multibindings with nullable keys.
- **Fix**: Ensure assisted factories' target bindings' parameters are processed in MetroGraph creation. Previously, these weren't processed and could result in scoped bindings not being cached.
- **Fix**: Fix duplicate field accessors generated for graph supertypes.
- Add [compose navigation sample](https://github.com/ZacSweers/metro/tree/main/samples/compose-navigation-app).

Special thanks to [@bnorm](https://github.com/bnorm) and [@yschimke](https://github.com/yschimke) for contributing to this release!

0.1.3
-----

_2025-04-18_

- **Change**: Multibindings may not be empty by default. To allow an empty multibinding, `@Multibinds(allowEmpty = true)` must be explicitly declared now.
- **New**: Write graph metadata to reports (if enabled).
- **New**: Support configuring debug and reports globally via `metro.debug` and `metro.reportsDestination` Gradle properties (respectively).
- **Enhancement**: Change how aggregation hints are generated to improve incremental compilation. Externally contributed hints are now looked up lazily per-scope instead of all at once.
- **Enhancement**: Optimize empty map multibindings to reuse a singleton instance.
- **Enhancement**: Report error diagnostic if Dagger's `@Reusable` is used on a provider or injected class.
- **Enhancement**: Tweak diagnostic error strings for members so that IDE terminals auto-link them better. i.e., instead of printing `example.AppGraph.provideString`, Metro will print `example.AppGraph#provideString` instead.
- **Enhancement**: Support repeatable @ContributesBinding annotations with different scopes.
- **Fix**: Fix incremental compilation when `@Includes`-annotated graph parameters change accessor signatures.
- **Fix**: Don't allow graph extensions to use the same scope as any extended ancestor graphs.
- **Fix**: Don't allow multiple ancestor graphs of graph extensions to use the same scope.
- **Fix**: Handle scenarios where the compose-compiler plugin runs _before_ Metro's when generating wrapper classes for top-level `@Composable` functions.
- **Fix**: Fix an edge case in graph extensions where child graphs would miss a provided scoped binding in a parent graph that was also exposed as an accessor.
- **Fix**: Fix Dagger interop issue when calling Javax/Jakarta/Dagger providers from Metro factories.
- **Fix**: Fix Dagger interop issue when calling `dagger.Lazy` from Metro factories.
- **Fix**: Preserve the original `Provider` or `Lazy` type used in injected types when generating factory creators.
- Temporarily disable hint generation in WASM targets to avoid file count mismatches until [KT-75865](https://youtrack.jetbrains.com/issue/KT-75865).
- Add an Android sample: https://github.com/ZacSweers/metro/tree/main/samples/android-app
- Add a multiplatform Circuit sample: https://github.com/ZacSweers/metro/tree/main/samples/circuit-app
- Add samples docs: https://zacsweers.github.io/metro/samples
- Add FAQ docs: https://zacsweers.github.io/metro/faq

Special thanks to [@JoelWilcox](https://github.com/JoelWilcox), [@bnorm](https://github.com/bnorm), and [@japplin](https://github.com/japplin) for contributing to this release!

0.1.2
-----

_2025-04-08_

- **Enhancement**: Implement `createGraph` and `createGraphFactory` FIR checkers for better error diagnostics on erroneous type arguments.
- **Enhancement**: Add `ContributesBinding.rank` interop support with Anvil.
- **Enhancement**: Check Kotlin version compatibility. Use the `metro.version.check=false` Gradle property to disable these warnings if you're feeling adventurous.
- **Fix**: Fix class-private qualifiers on multibinding contributions in other modules not being recognized in downstream graphs.
- **Fix**: Fix member injectors not getting properly visited in graph validation.
- **Fix**: Fix a bug where `Map<Key, Provider<Value>>` multibindings weren't always unwrapped correctly.
- **Fix**: Fix `Map<Key, Provider<Value>>` type keys not correctly interpreting the underlying type key as `Map<Key, Value>`.
- **Change**: Change `InstanceFactory` to a value class.
- **Change**: Make `providerOf` use `InstanceFactory` under the hood.

Special thanks to [@JoelWilcox](https://github.com/JoelWilcox), [@bnorm](https://github.com/bnorm), [@japplin](https://github.com/japplin), [@kevinguitar](https://github.com/kevinguitar), and [@erawhctim](https://github.com/erawhctim) for contributing to this release!

0.1.1
-----

_2025-04-03_

Initial release!

See the announcement blog post: https://www.zacsweers.dev/introducing-metro/
