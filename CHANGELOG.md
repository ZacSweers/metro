Changelog
=========

**Unreleased**
--------------

### New

### Enhancements

- **[FIR]**: Disallow `_` assisted context parameter names in top-level function injection.
- **[FIR/IR]**: When generating class and provider factories now, the compiler dedupes non-assisted, non-optional injections of the same type key (i.e. type ± qualifier). This shrinks generated code size in (uncommon) scenarios where you inject the same type multiple types.

### Fixes

- **[FIR]**: Improve optional binding member injections detection.
- **[IR]**: Fix propagation of `Map` graph inputs down to graph extensions.

### Changes

- `enableGraphSharding` is now enabled by default. Note this only kicks in (by default) for graphs with 2000+ bindings by default.

### Contributors

Special thanks to the following contributors for contributing to this release!

- [@inorichi](https://github.com/inorichi)

0.10.4
------

_2026-02-13_

### Enhancements

- **[FIR]**: Add suspicious scope diagnostics for cases where a developer might accidentally try to contribute to a concrete `@Scope` class or graph-like class, as that's not usually what you want!
- **[FIR]**: Add a diagnostic error for function member injection parameters with default values as they are not currently supported.
- **[IR]**: Extend conflicting overrides diagnostic in synthetic graphs (graph extension impls, dynamic graphs) to also validate compatible annotations. This catches scenarios where you may accidentally contribute something like a `fun dependency(): Dependency` accessor _and_ `@Provides fun dependency(): Dependency` provider elsewhere, which previously resulted in undefined runtime behavior.
- **[IR]**: When reporting conflicting override types in synthetic graphs, underline the type and include the source location (when possible) to better indicate the issue.
- **[IR]**: Add a graph validation failure hint to report when a direct Map binding exists that cannot satisfy a Provider/Lazy map.
    - For example, the below snippet
      ```kotlin
      @DependencyGraph
      interface ExampleGraph {
        val mapSize: Int

        @Provides fun provideInt(map: Map<String, Provider<String>>): Int = map.size

        @DependencyGraph.Factory
        interface Factory {
          fun create(@Provides map: Map<String, String>): ExampleGraph
        }
      }
      ```

      Now yields this error trace

      ```
      error: [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.collections.Map<kotlin.String, kotlin.String>

          kotlin.collections.Map<kotlin.String, kotlin.String> is injected at
              [ExampleGraph] ExampleGraph.provideInt(…, map)
          kotlin.Int is requested at
              [ExampleGraph] ExampleGraph.mapSize

      (Hint)
      A directly-provided 'Map<String, String>' binding exists, but direct Map bindings cannot satisfy 'Map<String, Provider<String>>' requests.

          IncompatibleMapValueType.kt:15:16
              @Provides map: kotlin.collections.Map<kotlin.String, kotlin.String>
                             ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

      Provider/Lazy-wrapped map values (e.g., Map<K, Provider<V>>) only work with a Map **multibinding** created with `@IntoMap` or `@Multibinds`.
      ```

### Fixes

- **[IR]**: Gracefully handle skipping code gen for absent member-injected properties/single-arg setters.
- **[IR]**: Decompose `Map` graph factory inputs correctly so they can properly satisfy map requests on the graph.
- **[IR]**: Validate directly-provided map inputs from map-requesting injection sites.
- **[IR/Native]**: Fix mirror parameter check for providers in `object` classes in non-jvm compilations.

### Changes

- Deprecate the `generateThrowsAnnotations` option and make it no-op. This was only in place when debugging a past kotlin/native issue.

### Contributors

Special thanks to the following contributors for contributing to this release!

- [@scana](https://github.com/scana)

0.10.3
------

_2026-02-09_

### New

- Metro now has experimental support for Kotlin 2.4.0. At the time of writing, this is only really helpful if you are testing IDE support in IntelliJ 2026.1 EAPs.
- Metro's compiler now embeds `androidx.tracing` and can produce perfetto traces of its IR transformations.
- **[FIR]**: Metro now does early detection of whether or not it's running in the IDE or CLI. If it's in the IDE, Metro will disable any FIR generators that do not generate user-visible code.

### Enhancements

- **[FIR]**: When reporting diagnostics about types that are aliases, include the aliased type in the message. This is helpful for messages like below
    ```kotlin
    typealias UserId = String
    interface Bindings {
      // error: Binds receiver type `kotlin.String` is the same type and qualifier as the bound type `UserId (typealias to kotlin.String)`.
      @Binds fun String.bind(): UserId
    }
    ```
- **[FIR]**: Add full integration tests for FIR-based IDE features.
    - This is really only in the changelog because getting Android Studio to not show its blocking analytics consent dialog on CI might be the most difficult technical problem this project has faced so far and what's a changelog for if not the occasional itsfinallyover.gif bragging rights.
- **[IR]**: Use `androidx.collection` primitive and scatter collections in a few more places to further help improve memory performance.
- **[IR]**: Don't attempt to generate a graph impl if validation at any level in processing fails, as this could result in obscure extra errors getting reported after the relevant initial error.

### Fixes

- **[IR]**: Avoid `IllegalStateException: No value parameter found` issues when reconstructing dependency cycle stacks to report cycle errors.
- **[IR]**: Fix a scenario where bindings available in both graphs and their extensions didn't properly consolidate to one binding.
- **[Gradle]**: Make the `metrox-android` artifact single-variant (release only).

### Changes

- **[FIR/IR]** Add aliases for a bunch of "known" mappings for Kotlin IDE plugin versions to Kotlin versions. This is still best-effort but should hopefully be more robust, especially in situations like Android Studio canaries (which do not report real Kotlin versions). Please star this issue: https://issuetracker.google.com/issues/474940910
- **[FIR]**: One downside of the above is that it revealed that Android Studio Otter 3 is effectively running on Kotlin 2.2.0, which is just a bit too far back to still support. However, now that Studio is switching to monthly releases it should track upstream IJ changes much quicker and Studio Panda is in RC1 now.
    - Previously, an incompatible version could cause the IDE file analysis to hang or error out if IDE support was enabled. Now, Metro's IDE support will gracefully degrade on incompatible IDE versions. This includes Android Studio Otter and IntelliJ `2025.2.x` as of this version. Android Studio Panda and IntelliJ 2025.3 are tested and working though!
        ```
        2026-02-08 01:14:27,225 [  56672]   INFO - STDERR - [METRO] Skipping enabling Metro extensions in IDE. Detected Kotlin version '2.2.255-dev-255' is not supported for IDE use (CLI_ONLY).
        ```
- **[IR]**: Rework assisted inject bindings to be encapsulated by their consuming assisted factory bindings in graph validation.
    - This ensures these classes can't accidentally participate in `SwitchingProvider`s or valid cycle breaking with `DelegateFactory`, as both of those require `Provider` types and assisted-inject types' factories don't implement `Provider`.
- **[Gradle]**: Avoid deprecated `KotlinCompilation.implementationConfigurationName` API.
- `enableTopLevelFunctionInjection`, `generateContributionHintsInFir`, and `supportedHintContributionPlatforms` will error if enabled on Kotlin/JS with JS incremental compilation enabled as it turns out this does not yet support generating top-level declarations from compiler plugins with incremental compilation enabled.
    - Please star https://youtrack.jetbrains.com/issue/KT-82395 and https://youtrack.jetbrains.com/issue/KT-82989.
- Fold `2.3.20-dev-7791` compat into `2.3.20-Beta2` compat, meaning the former is no longer tested on CI.
- Fold `2.3.20-dev-5437` compat into `2.3.20-dev-5706` compat. This is to help Metro's main branch stay stable as the `5437` artifact came from a dev maven repo with ephemeral artifacts.
- Test Kotlin `2.3.20-Beta2`.
- Test Kotlin `2.3.10`.
- Test Kotlin `2.4.0-dev-539`.
- Drop testing of Kotlin `2.3.10-RC`.
- Metro now _compiles_ against Kotlin `2.3.0`. This allows it to test `2.4.0` builds, but is still compatible down to Kotlin `2.2.20`. Metro's runtime artifacts also explicitly have their language version still set to `2.2` (and `2.0` for the Gradle plugin).

### Contributors

Special thanks to the following contributors for contributing to this release!

- [@kevinguitar](https://github.com/kevinguitar)
- [@DaniilPavlenko](https://github.com/DaniilPavlenko)
- [@heorhiipopov](https://github.com/heorhiipopov)
- [@C2H6O](https://github.com/C2H6O)

0.10.2
------

_2026-01-28_

### Enhancements

- **[FIR]** Add a diagnostic warning for `@IntoSet` callables returning collections, as these probably intended to use `@ElementsIntoSet`.
- **[IR]** Automatically patch [#1556](https://github.com/ZacSweers/metro/issues/1556) issues between Kotlin versions `[2.3.0 - 2.3.20-Beta2)` for any klib-using compilation or JVM compilation that enables the `-Xannotations-in-metadata` compiler option (which isn't enabled by default). This auto-patching is best effort and a fix in kotlinx is targeting `2.3.20-Beta2`. If you have any issues, it can be disabled via the `patchKlibParams` Gradle DSL property.

### Fixes

- **[IR]** Don't try class binding lookups for nullable types. These must always be explicitly provided.
- **[IR]** Disambiguate return-type overloads of generated `@Binds` functions for `@Contributes` annotations that contribute multiple interfaces of the same simple name but different package name.
- **[IR]** Skip assisted parameters when validating parameter type matching on native compilations.

0.10.1
------

_2026-01-23_

### Enhancements

- **[IR]** Add a dedicated `UNUSED_GRAPH_INPUT` diagnostic for `unusedGraphInputsSeverity` option.
- **[IR]** Reduce memory usage when processing long graph extension chains. This is a combination of a few things:
    - Using a (shaded) `ScatterMap` implementation from `androidx.collections`
    - Relinquishing binding lookup caches after graph validation completes
    - Caching IrBinding instances
    - Route almost all bindings through `BindingLookup` for lazy lookups rather than eagerly adding to the binding graph.
- **[IR]** Don't report subtypes of `Any` in similar binding hints.
- **[IR]** Don't report multibinding elements in similar binding hints (only report the multibinding itself if relevant).
- **[Graph Reporting]** Since all bindings are lazily retrieved from `BindingLookup` now, this will also cut down graph analysis report sizes, as they should now be almost entirely free of unused bindings.

### Fixes

- **[IR]** Fix exclusions not inherited from parent graphs.
- **[IR]** When reporting unused graph inputs on graph extensions, report the diagnostic on the original source declaration if possible.
- **[IR/Native]** Fix parameter type equality check in native compilations in constructor-injected class factories.
- **[IR/Compat]** Fix compatibiltiy for source-less diagnostics in Kotlin `2.3.20-Beta1`.

### Contributors

Special thanks to the following contributors for contributing to this release!

- [@JoelWilcox](https://github.com/JoelWilcox)
- [@japplin](https://github.com/japplin)
- [@kevinguitar](https://github.com/kevinguitar)
- [@jonamireh](https://github.com/jonamireh)
- [@zsmb13](https://github.com/zsmb13)

0.10.0
------

_2026-01-19_

### Behavior Changes

- Enable `contributesAsInject` by default. See its docs for more details, but in short this means that `@Inject` is now optional on `@ContributesBinding`, `@ContributesIntoSet`, and `@ContributesIntoMap` annotated declarations.
  ```kotlin
  @ContributesBinding(AppScope::class)
  // @Inject // <-- now implicit!
  class TacoImpl(...) : Taco
  ```
- **[Gradle / FIR]** Enable FIR hint generation by default on Kotlin `2.3.20-Beta1` or later.
    - By extension, this resolves Metro's most subscribed issue ([#460](https://github.com/ZacSweers/metro/issues/460)) by enabling cross-module contribution/aggregation features in non-jvm/android compilations 🎉.
    - Note that there is a separate known kotlinc issue around qualifier annotations that affects native builds and is targeted for `2.3.20-Beta2`. Follow [#1556](https://github.com/ZacSweers/metro/issues/1556) for updates.
- **[Gradle / FIR]** Enable top-level function injection by default on Kotlin `2.3.20-Beta1` or later.
- **[Gradle / FIR]** Disable automatic transformation of providers to private + deprecate the option, as this results in less efficient code generation and somewhat unclear error messages. It's recommended to write a lint check in another static analysis tool if you want this, for example [this implementation from slack-lints](https://github.com/slackhq/slack-lints/blob/main/slack-lint-checks/src/main/java/slack/lint/DoNotCallProvidersDetector.kt).
  - Metro _may_ add an option for the inverse in the future. i.e., allow writing a private provider in source but then transform it to `internal` at compile-time.

### New

- Support `Map<K, Lazy<V>>` and `Map<K, Provider<Lazy<V>>` multibindings expressions.
- **[FIR/IR]** Report diagnostics for unmatched exclusions and replacements during contribution merging. These are written to `reportsDestination` if enabled, and should be used for debugging only.
- **[IR / Gradle]** Add new experimental `enableSwitchingProviders` option. If enabled, this changes code generation to use "switching providers" to defer classloading until a giving binding is actually requested.
  - This is analogous to Dagger's `fastInit` feature.
  - You should really only use this if you've benchmarked it and measured a meaningful difference, as it comes with the same tradeoffs (always holding a graph instance ref, etc.)
- **[IR / Gradle]** Add an `unusedGraphInputsSeverity` option to report diagnostics for unused _direct_ graph inputs. This includes any `@Provides` or `@Includes` parameters to graph factories or managed binding containers declared via the `@DependencyGraph.includes` annotation.
    - This is in addition to the existing `reportsDestination`-only diagnostic for unused bindings, but limited to bindings that we _know_ are not reused elsewhere and can thus safely soft-enforce at compile-time.
    - This matches a similar feature in Dagger.

### Enhancements

- **[IR]** Restructure graph validation and generation to be separate phases, allowing for whole-graph validation before any code gen runs and better optimizing shared bindings across graph extension hierarchies.
- **[IR]** Improve member injection error trace in graph validation.
    - Previously it would show something like this
      ```
      dev.zacsweers.metro.MembersInjector<test.FeatureScreen> is requested at
          [test.FeatureGraph] test.FeatureGraph#inject()
      ```
    - Now it will specify the injected type is being injected instead
      ```
      test.Dependency is requested at
          [test.FeatureGraph] test.FeatureScreen.dependency
      test.FeatureScreen is injected at
          [test.FeatureGraph] test.FeatureGraph#inject()
      ```
    - It now also reports the location at the exact member injection callable declaration, rather than the graph.
- **[IR]** Validate parameter type keys on native builds to help clarify encounters with [KT-83427](https://youtrack.jetbrains.com/issue/KT-83427). Example below:
    ```
    e: Mirror/create function parameter type mismatch:
      - Source:         com.example.navigation.NavigationProviders.navigationSerializationModule
      - Mirror param:   @com.example.app.navigation.NavigationSerializers kotlin.collections.Set<kotlinx.serialization.modules.SerializersModule>
      - create() param: kotlin.collections.Set<kotlinx.serialization.modules.SerializersModule>

    This is a known bug in the Kotlin compiler, follow https://github.com/ZacSweers/metro/issues/1556
    ```
- **[IR]** Avoid generating unnecessary `Provider` refcounts for bindings only used by graph injector functions.
- **[IR]** When reporting graph failures in dynamic graphs, report the original call location in error reporting.
- **[IR]** Optimize equals/hashCode in type keys. Benchmarks show a ~2% macro improvement.
- **[IR/Sharding]** Rebalance shard groups after computing shard bindings.
- **[Gradle]** Generalize support for sourcing default values from gradle/system properties + document.

### Fixes

- **[FIR]** Ensure hint functions generated by FIR hint generation match the visibility of the source contributor.
- **[FIR/IR]** Always check for `@Inject`-annotated constructor even if class is annotated. This is important for the `contributesAsInject` feature, as it would previously always use the primary constructor in these cases.
- **[FIR/IR]** Don't clear reports dir between FIR and IR compiler phases.
- **[IR]** Never eagerly init graph extension impls (scoped or not).
- **[IR]** Don't cache creator-less scoped graph extension impls in their parent graphs. This was initially implemented this way due to a misunderstanding to how Dagger generated subcomponents! Getters for graph extensions now always return new instances.
- **[IR]** Check for `open` or `final` modality on classes first before searching for injectable constructors.
- **[IR]** Mark all supertypes' member injections as dependencies of subtypes' member injections. That's a wordier way of saying that member-injecting a class `Dog` that extends `Animal` also depends on member-injected dependencies of `Animal`.
- **[IR]** Don't process companion objects of graphs or binding containers independently of their parent classes.
- **[IR/Native]** Work around `Abstract function '___' is not implemented in non-abstract class 'Impl'` info warnings from native compilations.
- **[IR/Dynamic Graphs]** Always check if a key is replaced by a dynamic type key when building binding graphs. Previously, some bindings would be added even if a dynamic key was available, resulting in unexpected `DuplicateBinding` errors.
- **[IC]** Record lookups of contributed classes when looking up hints from IR. Previously Metro only recorded a lookup of the generated hint function, which appears to not be enough for Kotlin 2.3.20.
- **[IC]** Link IR-generated hint function files back to source class via expect-actual tracker to link their compilations. This fixes an edge case where simply changing a contribution scope (or removing it) could leave behind a stale hint file that downstream compilations would incidentally read.

### Misc Changes

- **[IR]** Already mentioned above, but worth calling out again — creator-less scoped graph extensions _are no longer cached_ in their parent graphs. Accessors to this will always get new instances now.
- **[IR]** Report log files reported from within graph generation now use snake-cased fully-qualified names of the impl graph as the file name suffix.
- **[IR]** Do not report similar bindings when a missing binding has type `kotlin.Any`. In practice this reported all available bindings.
- **[interop-dagger]** Update to Dagger `2.58`.
- **[Docs]** Publish kdocs for guice/jakarta/javax interop and metrox artifacts to doc site.
- **[Docs]** Expand IDE support docs.
- **[Docs]** Update `adoption.md` docs about subcomponents interop.
- Test Kotlin `2.3.10-RC`.
- Test Kotlin `2.3.20-Beta1`.

### Contributors

Special thanks to the following contributors for contributing to this release!

- [@neworld](https://github.com/neworld)
- [@JoelWilcox](https://github.com/JoelWilcox)
- [@C2H6O](https://github.com/C2H6O)

0.9.4
-----

_2026-01-09_

### Enhancements

- [IR] Support generation of scalar multibinding sets that use `@ElementsIntoSet` source bindings. Previously these would always use a `SetFactory` under the hood.
- [IR] Refactor multibinding getter logic to align with all other binding field/getter logic, allowing more precise generation of multibindings based on different contextual needs (scalar, `Provider`, etc representations).

### Fixes

- [IR] Fix accidental potential for runtime eager calls to non-initialized bindings in some multi-level multibinding scenarios.
- [IR] Always use a provider field if multiple provider and scalar refs are found. Previously we would possibly use just a scalar getter field wrapped in `InstanceFactory` for provider refs.
- [IR / Dagger Interop] Ensure `@BindsOptionalOf` bindings that are satisfied by scoped bindings use the scoped instance.
- [IR / Reports] Don't de-dupe alias/memberinject bindings in graph metadata reports if one is already present.

### Changes

- Remove `-checkdiscard` rules from embedded proguard rules.

### Contributors

Special thanks to the following contributors for contributing to this release!

- [@C2H6O](https://github.com/C2H6O)
- [@chrisbanes](https://github.com/chrisbanes)

0.9.3
-----

_2026-01-02_

Happy new year!

### Enhancements

- [FIR] Add diagnostic for multiple graph-like annotations on a single declaration.
- [IR] Stub `@Binds` expressions in origin points rather than graphs. This reduces unnecessary extra generated functions.
- [IR] Generate reusable getters for scalar-only dependencies that are used multiple times. This helps avoid `MethodTooLargeException` errors in the JVM.
- [IR] Detect suspicious member injections from inherited inject functions too.
- [IR] Don't try to validate graphs if `QualifierOverrideMismatch` errors were reported prior to graph validation. This helps reduce noise in failure cases, as it would otherwise also likely report `MissingBinding` errors caused by the source of the `QualifierOverrideMismatch` error.

### Fixes

- [FIR, Anvil Interop] Fix rank-based binding replacements getting dropped for multi-contribution classes in root graphs when contributions are from external modules.
- [FIR] Named annotation arguments in different order from declared parameters getting silently skipped.
- [FIR] Fix `Map<K, Provider<V>>` parameters to `@Provides` functions mistakenly unwrapping the Provider value type.
- [FIR] Disallow star projections in `@ElementsIntoSet` binding types.
- [FIR] Require exactly one generic type argument `@ElementsIntoSet` binding types.
- [IR, Anvil Interop] Fix rank-based binding replacements getting dropped for multi-contribution classes in graph extensions when contributions are from external modules.
- [IR] Prohibit duplicate map keys.
- [IR] Fix propagation of scoped multibinding sources to graph extensions.
- [IR] Don't eagerly validate duplicate bindings (only report them if used).
- [IC] Fix member injection metadata not always being written and causing IC issues with member inject function transformation.
- [Gradle] Report all tested Kotlin versions when emitting compatibility warnings.

### Changes

- Generated graph extension impls are now static nested classes rather inner classes. This should be a non-functional change.
- `DelegateFactory` property fields are now initialized in topological order rather just always first. This should be a non-functional change.
- [FIR] Change `SUSPICIOUS_MEMBER_INJECT_FUNCTION` diagnostic to be a warning instead of an error.
- Remove testing of 2.3.0 pre-releases. IntelliJ stable currently builds off Kotlin `2.3.2x` and Android Studio stable currently points to Kotlin `2.2.2x`.

### Contributors

Special thanks to the following contributors for contributing to this release!

- [@JoelWilcox](https://github.com/JoelWilcox)
- [@japplin](https://github.com/japplin)
- [@vRallev](https://github.com/vRallev)

0.9.2
-----

_2025-12-18_

### New

- [Gradle DSL + FIR] Configurable `nonPublicContributionSeverity` diagnostic that allows for configuring non-public contributions to warn or error. See its kdoc for full details.

### Enhancements

- [IR] Avoid deeply nested IR call chains for generated SetFactory/MapFactory multibindings that can cause `StackOverflowError`s in large graphs at compile-time.
- [IR] Short-circuit `visitCall` expressions in Metro IR transformation.
- [IR] Include fully qualified names of scopes when reporting incompatible scope binding errors.

### Fixes
- [IR] Don't apply `replaces` effect from excluded contributions in cross-module scenarios.
- [IR] Fix binding replacements getting dropped in classes with multiple contributions in graph extensions.
- [IR] Fix dynamic graph bindings not being propagated to graph extensions.
- [IR] Fix detection of `internal` contributions from non-friend compilations in IR contribution merging.
- [IR] Fix detection of `internal` contributions from friend compilations in IR contribution merging. Not a typo, turns out both scenarios were misbehaving in different ways.

### Contributors

Special thanks to the following contributors for contributing to this release!

- [@JoelWilcox](https://github.com/JoelWilcox)
- [@ansman](https://github.com/ansman)
- [@jingibus](https://github.com/jingibus)
- [@mateherber-jt](https://github.com/mateherber-jt)
- [@vRallev](https://github.com/vRallev)

0.9.1
-----

_2025-12-16_

This is a bufix release to fix compatibility with kotlinc `2.3.0`. There was a bug in Metro's compatibility layer that caused it to not realize it could use the `2.3.0-x` compatibility code with Kotlin `2.3.0` final. This release is otherwise identical to `0.9.0`.

This release also remains backward-compatible all the way to Kotlin `2.2.20` (including all the 2.3.0 preview releases) and forward compatible to `2.3.20` dev builds. See the [compatibility docs](https://zacsweers.github.io/metro/latest/compatibility/) for a full table of compatible versions.

0.9.0
-----

_2025-12-15_

This release significantly improves the runtime performance of Metro-generated graphs, bringing them to parity with Dagger components' typical runtime performance (without fastInit enabled).

- 🚀 Improves graph init runtime performance in large graphs by **30–40%**
- 🤏 Reduces generated graph code size by **60–70%** (even higher if you heavily use multibindings)
- [docs] Add runtime benchmarks to performance docs: https://zacsweers.github.io/metro/latest/performance#runtime-performance
- [docs] Refresh build performance docs: https://zacsweers.github.io/metro/latest/performance#build-performance
- [docs] Add a new quick start doc: Last bit: Metro now has a quick start doc! https://zacsweers.github.io/metro/latest/quickstart/
- **Behavior change**: When using top-level function injection, the generated class now has the same name as the function. Previously it was suffixed with `Class`.
- **New**: Experimental support for sharding large graphs. For extremely large dependency graphs on the JVM, their generated implementations could exceed the JVM class size limit. To avoid this, Metro now supports sharding within graphs (as needed) to distribute initialization code across multiple inner _shard_ classes. This is currently disabled by default but can be enabled via the `enableGraphSharding` Gradle DSL property.
- **New**: Support `@Provides` properties with `@JvmField` annotations.
- **Enhancement**: Avoid unnecessary intermediate `Provider` instance allocations during graph expression gen. This means that when a direct type is requested in code gen, Metro will skip instantiating the intermediate `MetroFactory` instance if possible, avoiding unnecessary allocations.
- **Enhancement**: Don't always generate accessors for multibinding contributors. This is no longer necessary now that we also avoid provider instance allocations, and should significantly reduce generated method counts if using a lot of multibindings.
- **Enhancement**: Include graph roots in `Provider` refcounting. Previously, when detecting if `Provider` instance were used multiple times, Metro only checked for usages from other bindings.
- **Enhancement**: For multibindings that would use `Provider` elements/values, mark their sources as provider-accessed in refcounting.
- **Enhancement**: Don't generate provider fields for graph self instances unless necessary.
- **Enhancement**: Improve accuracy of diagnostic location when reporting graph validation issues from binding callable declarations.
- **Enhancement**: Validate bindings from interop with Dagger `@BindsOptionalOf` lazily.
- **Enhancement**: [graph analysis] Title graph edges with semantically meaningful descriptions of the edge type. They should read a bit more like plain English.
- **Enhancement**: [graph analysis] Better handle visualizing bound instance inputs.
- **Enhancement**: [graph analysis] Better visualize member injection from graph injectors.
- **Enhancement**: [metrox-viewmodel] Allow viewmodel map keys on callable and type targets.
- **Fix**: Ensure IC lookups are recorded for all merged contributions.
- **Fix**: Fix `newInstance()` args not stripping `Lazy` in top-level function inject classes.
- **Fix**: Allow `Any` to be a `binding<...>()` type if no explicit supertypes are declared.
- **Fix**: Mark `MembersInjected` bindings as deferrable in graph metadata reporting.
- **Fix**: Use eager graphs for dominator tree analysis.
- **Fix**: Don't apply `replaces` effect from excluded contributions (regular and binding containers).
- **Fix**: Improve error messaging and hints when invisible map keys are detected.

Special thanks to the following contributors for contributing to this release!

- [@ansman](https://github.com/ansman)
- [@bherbst](https://github.com/bherbst)
- [@hossain-khan](https://github.com/hossain-khan)
- [@izantech](https://github.com/izantech)
- [@jingibus](https://github.com/jingibus)
- [@jonamireh](https://github.com/jonamireh)
- [@jonhan](https://github.com/jonhan)
- [@kevinguitar](https://github.com/kevinguitar)
- [@mateherber-jt](https://github.com/mateherber-jt)
- [@neworld](https://github.com/neworld)
- [@ritesh-singh](https://github.com/ritesh-singh)
- [@zsmb13](https://github.com/zsmb13)

0.8.2
-----

_2025-12-04_

- **Fix**: Avoid JDK 21 `List.removeFirst()` call in the compiler artifact. That said, Metro's compiler may move to JDK 21+ in the future (note this is separate from the runtime JVM targets).
- **Fix**: Fix accidental `androidx.test` transitive dependency in `metrox-viewmodel-compose` artifact.

0.8.1
-----

_2025-12-03_

- **New**: Experimental support for Kotlin `2.3.20` dev builds. See the [compatibility docs](https://zacsweers.github.io/metro/latest/compatibility/) for tested versions.
- **Fix**: Follow chained alias bindings when ref-counting used bindings in property collection.
- **Fix**: Fix `@ViewModelAssistedFactoryKey` KClass key type to use `out ViewModel` instead.

Special thanks to [@ansman](https://github.com/ansman) and [@jisungbin](https://github.com/jisungbin) for contributing to this release!

0.8.0
-----

_2025-12-01_

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
- **Fix**: Use correct type args for empty map provider return types.
- **Fix**: Add Dagger's internal `SetFactory`, `MapFactory` and `MapProviderFactory` to known Dagger ClassIds in `Provider` interop.
- **Removed**: Removed deprecated `enableScopedInjectClassHints` Gradle DSL property.
- **Removed**: Removed deprecated `enableStrictValidation` Gradle DSL property.
- **Removed**: Removed deprecated `contributesGraphExtension` Gradle DSL property.
- **Removed**: Removed deprecated `contributesGraphExtensionFactory` Gradle DSL property.
- **Removed**: Removed deprecated `optionalDependencyBehavior` Gradle DSL property.
- **Removed**: Removed deprecated `OptionalDependencyBehavior` Gradle plugin class.
- **Removed**: Removed deprecated `includeAnvil` Gradle DSL function.
- **Removed**: Removed deprecated `ContributesGraphExtension` annotation.
- **Removed**: Removed deprecated `OptionalDependency` annotation.

Special thanks to the following contributors for contributing to this release!

- [@yschimke](https://github.com/yschimke)
- [@jonapoul](https://github.com/jonapoul)
- [@kevinguitar](https://github.com/kevinguitar)
- [@jonamireh](https://github.com/jonamireh)
- [@zsmb13](https://github.com/zsmb13)
- [@bjdodson](https://github.com/bjdodson)
- [@consp1racy](https://github.com/consp1racy)

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
