# Hilt Interop Notes

Internal notes for Metro's Hilt interop implementation. User-facing docs live in
[`docs/interop.md`](../interop.md#hilt).

## Goal

`metro { interop { includeHilt() } }` lets a Metro graph merge Hilt declarations that target the
same logical scope:

- `@InstallIn(<component>) @Module` contributes its `@Provides` declarations as a binding
  container.
- `@InstallIn(<component>) @EntryPoint` contributes the entry point interface as a graph
  supertype.

The Hilt layer only discovers and translates Hilt metadata. Metro's normal contribution pipeline
still owns nested contribution generation, hint emission, supertype merging, binding-container
processing, replacements, exclusions, and IR graph validation.

## Main Files

- `compiler/.../hilt/HiltSymbols.kt`: Hilt `ClassId`s, FIR predicates, standard component IDs, and
  canonical component scopes.
- `compiler/.../hilt/HiltComponentScopeMapping.kt`: component-to-scope mapping, including
  user-defined `@DefineComponent` scope lookup and per-consumer in-round `@InstallIn` scans.
- `compiler/.../hilt/HiltAggregatedDepsScanner.kt`: parses compiled Hilt `@AggregatedDeps` markers
  from `hilt_aggregated_deps`.
- `compiler/.../hilt/HiltFirDeclarationExtension.kt`: emits contribution hints and contribution
  targets for in-round Hilt modules and entry points.
- `compiler/.../hilt/HiltContributionExtension.kt`: contributes Hilt entry points during FIR
  supertype merging.
- `compiler/.../hilt/HiltIrContributionExtension.kt`: contributes Hilt modules and entry points to
  IR-only merge paths.
- `compiler/.../api/fir/MetroFirDeclarationGenerationExtension.kt`: exposes
  `getContributionTargets()` so interop layers can ask Metro to generate the same nested
  contribution type as `@ContributesTo`.
- `compiler/.../api/ir/MetroIrContributionExtension.kt`: exposes IR-side binding-container and
  supertype contribution hooks.
- `compiler/.../fir/generators/ContributedInterfaceSupertypeGenerator.kt`: treats
  `@InstallIn(component)` modules as matching a graph scope when the component maps to that scope.
- `compiler/.../fir/generators/ContributionsFirGenerator.kt`: generates nested contribution
  interfaces for extension-provided `ContributionTarget`s.

## Discovery Paths

There are two broad inputs.

In-round source:

- `HiltComponentScopeMapping.inRoundInstallIns` scans source classes annotated with `@InstallIn`.
- Modules emit hints and are later handled as binding containers because `includeHilt()` also
  enables Dagger annotation interop.
- Entry points emit hints and `ContributionTarget`s. Metro generates a nested
  `MetroContributionTo<Scope>` interface that extends the entry point, matching native
  `@ContributesTo` behavior.

Compiled classpath metadata:

- Hilt's processor writes `@AggregatedDeps` markers.
- `HiltAggregatedDepsScanner` parses marker fields into component/module/entry-point class IDs.
- FIR merge paths use `HiltContributionExtension`; IR-only paths use `HiltIrContributionExtension`.

## Component Scope Mapping

The built-in Hilt component mapping is:

- `SingletonComponent` -> `@Singleton`
- `ActivityRetainedComponent` -> `@ActivityRetainedScoped`
- `ActivityComponent` -> `@ActivityScoped`
- `ViewModelComponent` -> `@ViewModelScoped`
- `FragmentComponent` -> `@FragmentScoped`
- `ServiceComponent` -> `@ServiceScoped`
- `ViewComponent` -> `@ViewScoped`
- `ViewWithFragmentComponent` -> `@ViewScoped`

For user-defined `@DefineComponent` interfaces, Metro looks for an annotation on the component
whose annotation class is itself annotated with `@Scope`. The `parent` argument is intentionally
not used to derive Metro graph-extension relationships.

This mapping is an aggregation key mapping. It does not make `@FeatureScoped` equal to
`@SingleIn(FeatureScoped::class)` for binding scope validation. If Hilt module bindings are scoped
with the concrete Hilt scope annotation, the Metro graph must also carry that concrete annotation.

## FIR Resolution Notes

Do not rely on `lazyResolveToPhase` from these compiler extensions. In compiler mode it is not a
general way to force annotation resolution, and using it from the wrong phase can create re-entry
problems.

The working pattern here is:

- Read raw `fir.annotations` when the code needs all annotations on an in-round symbol.
  `resolvedCompilerAnnotationsWithClassIds` can be cached before every annotation is resolved.
- Use `toAnnotationClassIdSafe(session)` so both resolved and unresolved annotation forms work.
- For `@InstallIn` class-array arguments, use the `installInComponents(...)` helpers with either a
  `MetroFirTypeResolver` or `TypeResolveService`.

## Cache Invariant

`HiltComponentScopeMapping` is intentionally not a shared session component. Each consumer creates
its own instance.

The scope cache only stores successful lookups. A user-defined `@DefineComponent` may be queried
before its annotations are ready in an early phase; caching `null` would make later phases silently
miss that component. Keeping instances local and retrying failed lookups avoids poisoning later
consumers such as IR.

## Hint Modes

FIR hint generation calls `HiltFirDeclarationExtension.getContributionHints()` and supports
cross-module Metro-only Hilt interop for modules and entry points.

IR hint generation only visits classes with Metro contribution annotations, so plain Hilt classes
do not get Metro hints in that mode. Cross-module Hilt-only inputs are still supported through
Hilt's `@AggregatedDeps` markers. Same-module inputs are handled by the in-round scan.

Box tests for cross-module Metro-only Hilt inputs should set
`// GENERATE_CONTRIBUTION_HINTS_IN_FIR`. `ContributionProvidersBoxTest` enables this by default.

## Tests

- `WITH_HILT`: adds `hilt-core`.
- `ENABLE_HILT_INTEROP`: enables Metro Hilt interop and implies `WITH_HILT`.
- `ENABLE_HILT_KSP`: runs Hilt KSP processors, implies `WITH_HILT`, and also enables Dagger KSP.
- `GENERATE_CONTRIBUTION_HINTS_IN_FIR`: needed for cross-module Metro-only Hilt tests.

`Ksp2AdditionalSourceProvider` loads all `dagger.hilt.processor.*` KSP processors for
`ENABLE_HILT_KSP`. Loading only `AggregatedDepsProcessor` is not enough because Hilt's processors
coordinate validation across multiple processors.

The compiler-test registrar wires the Hilt extensions explicitly instead of relying on
ServiceLoader.

## Current Limitations

- `@TestInstallIn` and `@CustomTestApplication` are not supported.
- `@DefineComponent.parent` is not used to derive Metro graph-extension relationships.
- There is no `EntryPointAccessors` equivalent; cast the graph to the entry-point interface.
- `AggregatedDeps.componentEntryPoints` is ignored.
- A custom `@DefineComponent` without a `@Scope` annotation is skipped without a diagnostic.
- Hilt component generation from `@HiltAndroidApp` is not consumed.
