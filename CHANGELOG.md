Changelog
=========

**Unreleased**
--------------

- **Enhancement**: Support `excludes`, `bindingContainers`, and `additionalScopes` in `@ContributesGraphExtension`.
- **Enhancement**: Allow binding containers and regular contributed classes to replace each other in contribution merging.
- **Enhancement**: Allow `@ElementsIntoSet` on properties.
- **Enhancement**: Don't run FIR extensions on Java sources.
- **Fix**: Don't double-add `@ContributesTo` contributions while merging contributed graphs.
- **Fix**: Check `rawStatus` for overrides when merging contributed supertypes.
- **Fix**: Correctly extract the element type when creating implicit `Set` multibindings from `@ElementsIntoSet` contributors.
- **Fix**: Check `additionalScopes` when merging binding containers too.
- **Fix**: Don't fail if multiple contributing annotations on binding containers match the target scope when aggregating them.

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
