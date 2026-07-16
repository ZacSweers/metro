# Diagnostics Reference

<!-- Generated from MetroDiagnosticId by DiagnosticsDocGenerator. -->
<!-- Run `./gradlew :compiler:generateDiagnosticsDocs` to update. -->

Reference for Metro's common graph-validation diagnostics: the messages reported with `[Metro/...]` IDs while validating dependency graphs.

This is not an exhaustive list of everything Metro reports. Many finer-grained declaration checks, such as annotation misuse and visibility errors, are reported directly in the frontend or IDE without an ID.

Diagnostics rendering is controlled by the `diagnosticsRenderMode` Gradle option: `AUTO`, `PLAIN`, or `RICH`.

| Diagnostic | Summary |
|------------|---------|
| [`Metro/AssistedFactorySuspendRequired`](#assistedfactorysuspendrequired) | An assisted factory creates a target that depends on suspend bindings. |
| [`Metro/DependencyCycle`](#dependencycycle) | A binding cycle has no deferred edge. |
| [`Metro/DuplicateBinding`](#duplicatebinding) | Multiple bindings match the same type key. |
| [`Metro/DuplicateMapKeys`](#duplicatemapkeys) | Multiple map contributions use the same key. |
| [`Metro/EmptyMultibinding`](#emptymultibinding) | A declared multibinding has no contributions. |
| [`Metro/Error`](#error) | A general Metro graph validation error. |
| [`Metro/IncompatibleOverrides`](#incompatibleoverrides) | Merged declarations conflict in their binding roles. |
| [`Metro/IncompatibleReturnTypes`](#incompatiblereturntypes) | Merged declarations have incompatible return types. |
| [`Metro/IncompatiblyScopedBindings`](#incompatiblyscopedbindings) | A graph uses bindings with incompatible scopes. |
| [`Metro/InvalidBinding`](#invalidbinding) | A binding declaration uses an unsupported form. |
| [`Metro/MemberInjectionOverSuspendBinding`](#memberinjectionoversuspendbinding) | Member injection depends on a suspend binding. |
| [`Metro/MissingBinding`](#missingbinding) | No binding satisfies a requested type. |
| [`Metro/MissingRuntimeCoroutines`](#missingruntimecoroutines) | Suspend codegen needs the optional runtime-coroutines artifact. |
| [`Metro/MultibindingOverSuspendBindings`](#multibindingoversuspendbindings) | A multibinding aggregates suspend bindings. |
| [`Metro/QualifierOverrideMismatch`](#qualifieroverridemismatch) | An override changes the qualifiers on a declaration. |
| [`Metro/SuspendBindingFromNonSuspendAccessor`](#suspendbindingfromnonsuspendaccessor) | A non-suspend accessor requests a binding that needs a suspend context. |
| [`Metro/SuspendBindingWrappedInLazy`](#suspendbindingwrappedinlazy) | A suspend binding is requested through a synchronous `Lazy`. |
| [`Metro/SuspendBindingWrappedInProvider`](#suspendbindingwrappedinprovider) | A suspend binding is requested through a synchronous `Provider`. |
| [`Metro/SuspendProvidersNotEnabled`](#suspendprovidersnotenabled) | A suspend binding is used while suspend providers are disabled. |
| [`Metro/SuspiciousUnusedMultibinding`](#suspiciousunusedmultibinding) | A multibinding has contributions but is never requested. |
| [`Metro/UnprocessedUpstreamDeclaration`](#unprocessedupstreamdeclaration) | An upstream declaration does not appear to have been processed by Metro. |
| [`Metro/UnusedGraphInputs`](#unusedgraphinputs) | A graph input is unused and can be removed. |

## AssistedFactorySuspendRequired

**Diagnostic:** `Metro/AssistedFactorySuspendRequired`

**Summary:** An assisted factory creates a target that depends on suspend bindings.

An `@AssistedFactory` creates a type whose non-assisted dependencies include suspend bindings.
The factory function must await them. Declare the factory function as a `suspend` function so the
generated implementation can await the suspend dependencies.

## DependencyCycle

**Diagnostic:** `Metro/DependencyCycle`

**Summary:** A binding cycle has no deferred edge.

Bindings depend on each other in a loop where every dependency is needed immediately. Break the
cycle by changing one edge to a deferred type such as `() -> T` or `Lazy<T>`, which delays
initialization until first use. If the cycle is between graphs that extend or depend on each
other, restructure the graph relationship instead.

## DuplicateBinding

**Diagnostic:** `Metro/DuplicateBinding`

**Summary:** Multiple bindings match the same type key.

Two or more bindings resolve to the same type and qualifier. Each conflicting declaration is
listed with its location. Remove all but one, give them distinct qualifiers, or annotate them
with `@IntoSet`/`@IntoMap` if the bindings are meant to contribute to a collection.

## DuplicateMapKeys

**Diagnostic:** `Metro/DuplicateMapKeys`

**Summary:** Multiple map contributions use the same key.

Two `@IntoMap` contributions declare the same key for the same map binding. Each map key may
only be contributed once. Change one key or remove the duplicate contribution.

## EmptyMultibinding

**Diagnostic:** `Metro/EmptyMultibinding`

**Summary:** A declared multibinding has no contributions.

A `Set` or `Map` multibinding is declared or requested, but no binding contributes to it. If an
empty collection is valid, declare it with `@Multibinds(allowEmpty = true)`. Otherwise check that
the intended contributions target the same collection type, qualifier, and graph scope. When
Metro finds a near match, the report lists it as a similar multibinding.

## Error

**Diagnostic:** `Metro/Error`

**Summary:** A general Metro graph validation error.

Metro reported a graph validation error that does not have a dedicated diagnostic ID. The
diagnostic message contains the specific failure and any available fix guidance.

## IncompatibleOverrides

**Diagnostic:** `Metro/IncompatibleOverrides`

**Summary:** Merged declarations conflict in their binding roles.

Declarations merged into a graph have the same signature but incompatible Metro annotations or
binding roles. For example, one declaration may be an accessor while another is an `@Provides`
function. Align the declarations or rename one of the members.

## IncompatibleReturnTypes

**Diagnostic:** `Metro/IncompatibleReturnTypes`

**Summary:** Merged declarations have incompatible return types.

Declarations merged into a graph override each other but return incompatible types. This can
happen with members inherited from contributed supertypes. Make the return types compatible or
rename one of the members so they no longer override each other.

## IncompatiblyScopedBindings

**Diagnostic:** `Metro/IncompatiblyScopedBindings`

**Summary:** A graph uses bindings with incompatible scopes.

A graph references a scoped binding without declaring that scope, or an unscoped graph
references a scoped binding. Add the binding's scope to the graph's `@SingleIn`/scope
annotations, move the binding to a graph that declares the scope, or remove the binding's scope.

## InvalidBinding

**Diagnostic:** `Metro/InvalidBinding`

**Summary:** A binding declaration uses an unsupported form.

A binding is requested in a way its declaration does not support. The most common case is
injecting an assisted-injected class directly. Inject its `@AssistedFactory` instead and call the
factory's `create()` function.

## MemberInjectionOverSuspendBinding

**Diagnostic:** `Metro/MemberInjectionOverSuspendBinding`

**Summary:** Member injection depends on a suspend binding.

A member-injected dependency resolves to a suspend binding, but member injection runs without a
suspend context and cannot await it. Defer the dependency as `suspend () -> T` (or
`SuspendLazy<T>`) so the member holds a deferred value instead.

## MissingBinding

**Diagnostic:** `Metro/MissingBinding`

**Summary:** No binding satisfies a requested type.

A graph accessor, injected constructor parameter, or injected member requests a type that no
visible binding provides. The report includes the dependency path that led to the request and
any similar bindings Metro found, such as the same type with a different qualifier, nullability,
subtype/supertype relationship, or multibinding.

Add an `@Inject` constructor, an `@Provides` function, or a `@Binds`/contributed binding visible
to the graph that requests the type.

## MissingRuntimeCoroutines

**Diagnostic:** `Metro/MissingRuntimeCoroutines`

**Summary:** Suspend codegen needs the optional runtime-coroutines artifact.

A binding or request needs runtime support from the optional `runtime-coroutines` artifact, such
as a scoped suspend binding or a `SuspendLazy<T>` request, but that artifact is not on the
classpath. Add `dev.zacsweers.metro:runtime-coroutines` to the compile and runtime classpath.

## MultibindingOverSuspendBindings

**Diagnostic:** `Metro/MultibindingOverSuspendBindings`

**Summary:** A multibinding aggregates suspend bindings.

A `Set` or `Map` multibinding aggregates suspend contributions. Aggregation code runs without a
suspend context and cannot await each element. Set multibindings over suspend bindings are
unsupported. A map multibinding must be consumed as a deferred-value form such as
`Map<K, suspend () -> V>` or `Map<K, SuspendProvider<V>>` so each value defers its resolution.

## QualifierOverrideMismatch

**Diagnostic:** `Metro/QualifierOverrideMismatch`

**Summary:** An override changes the qualifiers on a declaration.

Qualifier annotations are not inherited. An override with different or missing qualifiers
resolves a different binding than the declaration it overrides. Declare the same qualifiers on
the override and the overridden declaration.

## SuspendBindingFromNonSuspendAccessor

**Diagnostic:** `Metro/SuspendBindingFromNonSuspendAccessor`

**Summary:** A non-suspend accessor requests a binding that needs a suspend context.

An accessor returns a type that transitively depends on suspend bindings, so resolving it
requires awaiting them. A non-suspend accessor cannot do that. Mark the accessor as
`suspend fun`, or change its return type to a deferred form such as `suspend () -> T` or
`SuspendProvider<T>`.

## SuspendBindingWrappedInLazy

**Diagnostic:** `Metro/SuspendBindingWrappedInLazy`

**Summary:** A suspend binding is requested through a synchronous `Lazy`.

A suspend binding is wrapped in a synchronous `Lazy`. `Lazy` resolves its value without a suspend
context and cannot await the binding. Use `SuspendLazy<T>` instead.

## SuspendBindingWrappedInProvider

**Diagnostic:** `Metro/SuspendBindingWrappedInProvider`

**Summary:** A suspend binding is requested through a synchronous `Provider`.

A suspend binding is wrapped in a synchronous `Provider` (or `() -> T`). That wrapper resolves
its value without a suspend context and cannot await the binding. Use the deferred suspend form
`suspend () -> T` (or `SuspendProvider<T>`) instead.

## SuspendProvidersNotEnabled

**Diagnostic:** `Metro/SuspendProvidersNotEnabled`

**Summary:** A suspend binding is used while suspend providers are disabled.

A binding is provided by a `suspend` function, but suspend provider support is turned off. Enable
the `enable-suspend-providers` compiler option or set `metro.enableSuspendProviders` to true.
Otherwise make the provider non-suspend.

## SuspiciousUnusedMultibinding

**Diagnostic:** `Metro/SuspiciousUnusedMultibinding`

**Summary:** A multibinding has contributions but is never requested.

Contributions exist for a multibinding that nothing in the graph requests. This usually means
the contributions target the wrong collection type, qualifier, or graph scope. The report lists
the unused contributions and any child graph scopes where the multibinding is requested.

## UnprocessedUpstreamDeclaration

**Diagnostic:** `Metro/UnprocessedUpstreamDeclaration`

**Summary:** An upstream declaration does not appear to have been processed by Metro.

Metro was asked to use an injected declaration from another module or compilation unit, but that
declaration does not appear to have been processed by Metro. This usually means Metro was
enabled only in the downstream module while the referenced declaration lives upstream.

Enable Metro in the upstream module too. If this happens while using framework interop, make
sure the upstream module also runs that framework's code generation when Metro relies on its
generated code.

## UnusedGraphInputs

**Diagnostic:** `Metro/UnusedGraphInputs`

**Summary:** A graph input is unused and can be removed.

A graph factory parameter, included graph, or binding container is never used by any binding in
the graph. Remove it. If a binding container is present only to expose other containers, include
the used containers directly. Configure this diagnostic with the `unusedGraphInputsSeverity`
Gradle option.
