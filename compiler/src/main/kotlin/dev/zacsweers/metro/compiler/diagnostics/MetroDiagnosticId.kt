// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1

/**
 * Registry of Metro's common diagnostic IDs.
 *
 * @property fullId the user-visible tag, such as `[Metro/MissingBinding]`.
 * @property brief used in generated docs metadata.
 * @property explanation the Markdown body for the generated diagnostics reference page.
 * @property factory the default [MetroDiagnostics] factory that transports the rendered message
 *   through kotlinc's diagnostic reporting. Call sites with severity-dependent factories (e.g.
 *   [UNUSED_GRAPH_INPUTS], which maps a configured severity to warning/error variants) select their
 *   own instead.
 */
internal enum class MetroDiagnosticId(
  val fullId: String,
  val brief: String,
  val explanation: String,
  val factory: KtDiagnosticFactory1<String>,
) {
  MISSING_BINDING(
    "Metro/MissingBinding",
    "No binding satisfies a requested type.",
    """
    A graph accessor, injected constructor parameter, or injected member requests a type that no
    visible binding provides. The report includes the dependency path that led to the request and
    any similar bindings Metro found, such as the same type with a different qualifier, nullability,
    subtype/supertype relationship, or multibinding.

    Add an `@Inject` constructor, an `@Provides` function, or a `@Binds`/contributed binding visible
    to the graph that requests the type.
    """,
    MetroDiagnostics.MISSING_BINDING,
  ),
  DUPLICATE_BINDING(
    "Metro/DuplicateBinding",
    "Multiple bindings match the same type key.",
    """
    Two or more bindings resolve to the same type and qualifier. Each conflicting declaration is
    listed with its location. Remove all but one, give them distinct qualifiers, or annotate them
    with `@IntoSet`/`@IntoMap` if the bindings are meant to contribute to a collection.
    """,
    MetroDiagnostics.DUPLICATE_BINDING,
  ),
  DEPENDENCY_CYCLE(
    "Metro/DependencyCycle",
    "A binding cycle has no deferred edge.",
    """
    Bindings depend on each other in a loop where every dependency is needed immediately. Break the
    cycle by changing one edge to a deferred type such as `() -> T` or `Lazy<T>`, which delays
    initialization until first use. If the cycle is between graphs that extend or depend on each
    other, restructure the graph relationship instead.
    """,
    MetroDiagnostics.GRAPH_DEPENDENCY_CYCLE,
  ),
  DUPLICATE_MAP_KEYS(
    "Metro/DuplicateMapKeys",
    "Multiple map contributions use the same key.",
    """
    Two `@IntoMap` contributions declare the same key for the same map binding. Each map key may
    only be contributed once. Change one key or remove the duplicate contribution.
    """,
    MetroDiagnostics.DUPLICATE_MAP_KEY,
  ),
  EMPTY_MULTIBINDING(
    "Metro/EmptyMultibinding",
    "A declared multibinding has no contributions.",
    """
    A `Set` or `Map` multibinding is declared or requested, but no binding contributes to it. If an
    empty collection is valid, declare it with `@Multibinds(allowEmpty = true)`. Otherwise check that
    the intended contributions target the same collection type, qualifier, and graph scope. When
    Metro finds a near match, the report lists it as a similar multibinding.
    """,
    MetroDiagnostics.EMPTY_MULTIBINDING,
  ),
  SUSPICIOUS_UNUSED_MULTIBINDING(
    "Metro/SuspiciousUnusedMultibinding",
    "A multibinding has contributions but is never requested.",
    """
    Contributions exist for a multibinding that nothing in the graph requests. This usually means
    the contributions target the wrong collection type, qualifier, or graph scope. The report lists
    the unused contributions and any child graph scopes where the multibinding is requested.
    """,
    MetroDiagnostics.SUSPICIOUS_UNUSED_MULTIBINDING,
  ),
  INCOMPATIBLY_SCOPED_BINDINGS(
    "Metro/IncompatiblyScopedBindings",
    "A graph uses bindings with incompatible scopes.",
    """
    A graph references a scoped binding without declaring that scope, or an unscoped graph
    references a scoped binding. Add the binding's scope to the graph's `@SingleIn`/scope
    annotations, move the binding to a graph that declares the scope, or remove the binding's scope.
    """,
    MetroDiagnostics.INCOMPATIBLE_SCOPE,
  ),
  INCOMPATIBLE_RETURN_TYPES(
    "Metro/IncompatibleReturnTypes",
    "Merged declarations have incompatible return types.",
    """
    Declarations merged into a graph override each other but return incompatible types. This can
    happen with members inherited from contributed supertypes. Make the return types compatible or
    rename one of the members so they no longer override each other.
    """,
    MetroDiagnostics.INCOMPATIBLE_RETURN_TYPES,
  ),
  INCOMPATIBLE_OVERRIDES(
    "Metro/IncompatibleOverrides",
    "Merged declarations conflict in their binding roles.",
    """
    Declarations merged into a graph have the same signature but incompatible Metro annotations or
    binding roles. For example, one declaration may be an accessor while another is an `@Provides`
    function. Align the declarations or rename one of the members.
    """,
    MetroDiagnostics.INCOMPATIBLE_OVERRIDES,
  ),
  QUALIFIER_OVERRIDE_MISMATCH(
    "Metro/QualifierOverrideMismatch",
    "An override changes the qualifiers on a declaration.",
    """
    Qualifier annotations are not inherited. An override with different or missing qualifiers
    resolves a different binding than the declaration it overrides. Declare the same qualifiers on
    the override and the overridden declaration.
    """,
    MetroDiagnostics.QUALIFIER_OVERRIDE_MISMATCH,
  ),
  INVALID_BINDING(
    "Metro/InvalidBinding",
    "A binding declaration uses an unsupported form.",
    """
    A binding is requested in a way its declaration does not support. The most common case is
    injecting an assisted-injected class directly. Inject its `@AssistedFactory` instead and call the
    factory's `create()` function.
    """,
    MetroDiagnostics.INVALID_ASSISTED_BINDING,
  ),
  UNPROCESSED_UPSTREAM_DECLARATION(
    "Metro/UnprocessedUpstreamDeclaration",
    "An upstream declaration does not appear to have been processed by Metro.",
    """
    Metro was asked to use an injected declaration from another module or compilation unit, but that
    declaration does not appear to have been processed by Metro. This usually means Metro was
    enabled only in the downstream module while the referenced declaration lives upstream.

    Enable Metro in the upstream module too. If this happens while using framework interop, make
    sure the upstream module also runs that framework's code generation when Metro relies on its
    generated code.
    """,
    MetroDiagnostics.UNPROCESSED_UPSTREAM_DECLARATION,
  ),
  UNUSED_GRAPH_INPUTS(
    "Metro/UnusedGraphInputs",
    "A graph input is unused and can be removed.",
    """
    A graph factory parameter, included graph, or binding container is never used by any binding in
    the graph. Remove it. If a binding container is present only to expose other containers, include
    the used containers directly. Configure this diagnostic with the `unusedGraphInputsSeverity`
    Gradle option.
    """,
    MetroDiagnostics.UNUSED_GRAPH_INPUT_WARNING,
  ),
  SUSPEND_PROVIDERS_NOT_ENABLED(
    "Metro/SuspendProvidersNotEnabled",
    "A suspend binding is used while suspend providers are disabled.",
    """
    A binding is provided by a `suspend` function, but suspend provider support is turned off. Enable
    the `enable-suspend-providers` compiler option or set `metro.enableSuspendProviders` to true.
    Otherwise make the provider non-suspend.
    """,
    MetroDiagnostics.SUSPEND_PROVIDERS_NOT_ENABLED,
  ),
  SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR(
    "Metro/SuspendBindingFromNonSuspendAccessor",
    "A non-suspend accessor requests a binding that needs a suspend context.",
    """
    An accessor returns a type that transitively depends on suspend bindings, so resolving it
    requires awaiting them. A non-suspend accessor cannot do that. Mark the accessor as
    `suspend fun`, or change its return type to a deferred form such as `suspend () -> T` or
    `SuspendProvider<T>`.
    """,
    MetroDiagnostics.SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR,
  ),
  SUSPEND_BINDING_WRAPPED_IN_PROVIDER(
    "Metro/SuspendBindingWrappedInProvider",
    "A suspend binding is requested through a synchronous `Provider`.",
    """
    A suspend binding is wrapped in a synchronous `Provider` (or `() -> T`). That wrapper resolves
    its value without a suspend context and cannot await the binding. Use the deferred suspend form
    `suspend () -> T` (or `SuspendProvider<T>`) instead.
    """,
    MetroDiagnostics.SUSPEND_BINDING_WRAPPED_IN_PROVIDER,
  ),
  SUSPEND_BINDING_WRAPPED_IN_LAZY(
    "Metro/SuspendBindingWrappedInLazy",
    "A suspend binding is requested through a synchronous `Lazy`.",
    """
    A suspend binding is wrapped in a synchronous `Lazy`. `Lazy` resolves its value without a suspend
    context and cannot await the binding. Use `SuspendLazy<T>` instead.
    """,
    MetroDiagnostics.SUSPEND_BINDING_WRAPPED_IN_LAZY,
  ),
  MEMBER_INJECTION_OVER_SUSPEND_BINDING(
    "Metro/MemberInjectionOverSuspendBinding",
    "Member injection depends on a suspend binding.",
    """
    A member-injected dependency resolves to a suspend binding, but member injection runs without a
    suspend context and cannot await it. Defer the dependency as `suspend () -> T` (or
    `SuspendLazy<T>`) so the member holds a deferred value instead.
    """,
    MetroDiagnostics.MEMBER_INJECTION_OVER_SUSPEND_BINDING,
  ),
  ASSISTED_FACTORY_SUSPEND_REQUIRED(
    "Metro/AssistedFactorySuspendRequired",
    "An assisted factory creates a target that depends on suspend bindings.",
    """
    An `@AssistedFactory` creates a type whose non-assisted dependencies include suspend bindings.
    The factory function must await them. Declare the factory function as a `suspend` function so the
    generated implementation can await the suspend dependencies.
    """,
    MetroDiagnostics.ASSISTED_FACTORY_SUSPEND_REQUIRED,
  ),
  MULTIBINDING_OVER_SUSPEND_BINDINGS(
    "Metro/MultibindingOverSuspendBindings",
    "A multibinding aggregates suspend bindings.",
    """
    A `Set` or `Map` multibinding aggregates suspend contributions. Aggregation code runs without a
    suspend context and cannot await each element. Set multibindings over suspend bindings are
    unsupported. A map multibinding must be consumed as a deferred-value form such as
    `Map<K, suspend () -> V>` or `Map<K, SuspendProvider<V>>` so each value is initialized only when
    its provider is invoked.
    """,
    MetroDiagnostics.MULTIBINDING_OVER_SUSPEND_BINDINGS,
  ),
  MISSING_RUNTIME_COROUTINES(
    "Metro/MissingRuntimeCoroutines",
    "Suspend codegen needs the optional runtime-coroutines artifact.",
    """
    A binding or request needs runtime support from the optional `runtime-coroutines` artifact, such
    as a scoped suspend binding or a `SuspendLazy<T>` request, but that artifact is not on the
    classpath. Add `dev.zacsweers.metro:runtime-coroutines` to the compile and runtime classpath.
    """,
    MetroDiagnostics.MISSING_RUNTIME_COROUTINES,
  ),
  GENERIC(
    "Metro/Error",
    "A general Metro graph validation error.",
    """
    Metro reported a graph validation error that does not have a dedicated diagnostic ID. The
    diagnostic message contains the specific failure and any available fix guidance.
    """,
    MetroDiagnostics.METRO_ERROR,
  );

  /** Anchor on the generated diagnostics reference page. */
  val anchor: String
    get() = fullId.substringAfter('/').lowercase()

  val docsUrl: String
    get() = "$DOCS_BASE_URL#$anchor"

  companion object {
    const val DOCS_BASE_URL = "https://zacsweers.github.io/metro/latest/diagnostics/"

    /**
     * Selects the wrapped-in-synchronous-wrapper diagnostic for a suspend binding. [wrapper] is the
     * synchronous wrapper name nearest the bound value, either `Provider` or `Lazy`.
     */
    fun suspendBindingWrappedIn(wrapper: String): MetroDiagnosticId =
      when (wrapper) {
        "Provider" -> SUSPEND_BINDING_WRAPPED_IN_PROVIDER
        "Lazy" -> SUSPEND_BINDING_WRAPPED_IN_LAZY
        else -> error("Unexpected synchronous wrapper name: $wrapper")
      }
  }
}
