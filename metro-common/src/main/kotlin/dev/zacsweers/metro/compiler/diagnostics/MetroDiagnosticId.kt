// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics

/**
 * Registry of Metro's common diagnostic IDs.
 *
 * @property fullId the user-visible tag, such as `[Metro/MissingBinding]`.
 * @property brief used in generated docs metadata.
 * @property explanation the Markdown body for the generated diagnostics reference page.
 */
public enum class MetroDiagnosticId(
  public val fullId: String,
  public val brief: String,
  public val explanation: String,
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
  ),
  DUPLICATE_BINDING(
    "Metro/DuplicateBinding",
    "Multiple bindings match the same type key.",
    """
    Two or more bindings resolve to the same type and qualifier. Each conflicting declaration is
    listed with its location. Remove all but one, give them distinct qualifiers, or annotate them
    with `@IntoSet`/`@IntoMap` if the bindings are meant to contribute to a collection.
    """,
  ),
  DEPENDENCY_CYCLE(
    "Metro/DependencyCycle",
    "A binding cycle has no deferred edge.",
    """
    Bindings depend on each other in a loop where every dependency is needed immediately. Break the
    cycle by changing one edge to a deferred type such as `() -> T` or `Lazy<T>`, which delays
    construction until first use. If the cycle is between graphs that extend or depend on each
    other, restructure the graph relationship instead.
    """,
  ),
  DUPLICATE_MAP_KEYS(
    "Metro/DuplicateMapKeys",
    "Multiple map contributions use the same key.",
    """
    Two `@IntoMap` contributions declare the same key for the same map binding. Each map key may
    only be contributed once. Change one key or remove the duplicate contribution.
    """,
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
  ),
  SUSPICIOUS_UNUSED_MULTIBINDING(
    "Metro/SuspiciousUnusedMultibinding",
    "A multibinding has contributions but is never requested.",
    """
    Contributions exist for a multibinding that nothing in the graph requests. This usually means
    the contributions target the wrong collection type, qualifier, or graph scope. The report lists
    the unused contributions and any child graph scopes where the multibinding is requested.
    """,
  ),
  INCOMPATIBLY_SCOPED_BINDINGS(
    "Metro/IncompatiblyScopedBindings",
    "A graph uses bindings with incompatible scopes.",
    """
    A graph references a scoped binding without declaring that scope, or an unscoped graph
    references a scoped binding. Add the binding's scope to the graph's `@SingleIn`/scope
    annotations, move the binding to a graph that declares the scope, or remove the binding's scope.
    """,
  ),
  INCOMPATIBLE_RETURN_TYPES(
    "Metro/IncompatibleReturnTypes",
    "Merged declarations have incompatible return types.",
    """
    Declarations merged into a graph override each other but return incompatible types. This can
    happen with members inherited from contributed supertypes. Make the return types compatible or
    rename one of the members so they no longer override each other.
    """,
  ),
  INCOMPATIBLE_OVERRIDES(
    "Metro/IncompatibleOverrides",
    "Merged declarations conflict in their binding roles.",
    """
    Declarations merged into a graph have the same signature but incompatible Metro annotations or
    binding roles. For example, one declaration may be an accessor while another is an `@Provides`
    function. Align the declarations or rename one of the members.
    """,
  ),
  QUALIFIER_OVERRIDE_MISMATCH(
    "Metro/QualifierOverrideMismatch",
    "An override changes the qualifiers on a declaration.",
    """
    Qualifier annotations are not inherited. An override with different or missing qualifiers
    resolves a different binding than the declaration it overrides. Declare the same qualifiers on
    the override and the overridden declaration.
    """,
  ),
  INVALID_BINDING(
    "Metro/InvalidBinding",
    "A binding declaration uses an unsupported form.",
    """
    A binding is requested in a way its declaration does not support. The most common case is
    injecting an assisted-injected class directly. Inject its `@AssistedFactory` instead and call the
    factory's `create()` function.
    """,
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
  ),
  GENERIC(
    "Metro/Error",
    "A general Metro graph validation error.",
    """
    Metro reported a graph validation error that does not have a dedicated diagnostic ID. The
    diagnostic message contains the specific failure and any available fix guidance.
    """,
  );

  /** Anchor on the generated diagnostics reference page. */
  public val anchor: String
    get() = fullId.substringAfter('/').lowercase()

  public val docsUrl: String
    get() = "$DOCS_BASE_URL#$anchor"

  public companion object {
    public const val DOCS_BASE_URL: String = "https://zacsweers.github.io/metro/latest/diagnostics/"
  }
}
