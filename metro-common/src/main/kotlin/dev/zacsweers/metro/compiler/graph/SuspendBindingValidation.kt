// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId

/** The graph-level behavior of a request that participates in suspend validation. */
public enum class SuspendGraphRequestKind {
  ACCESSOR,
  MEMBERS_INJECTOR,
  OTHER,
}

/** Binding categories that need declaration-specific suspend validation. */
public enum class SuspendBindingKind {
  ORDINARY,
  MULTIBINDING,
  MEMBER_INJECTING,
  ASSISTED_FACTORY,
}

/** A frontend request for a graph binding, retaining its native declaration in [source]. */
public data class SuspendGraphRequest<ContextualTypeKey, Source>(
  val contextKey: ContextualTypeKey,
  val source: Source,
  val kind: SuspendGraphRequestKind,
  val isSuspend: Boolean = false,
)

/** Metadata needed to validate one multibinding without inspecting frontend-native types. */
public data class SuspendMultibindingMetadata(
  val isSet: Boolean,
  val mapKeyType: String? = null,
  val mapValueType: String? = null,
)

/** One member-injection operation whose dependencies must remain synchronous. */
public data class SuspendMemberInjectionMetadata<ContextualTypeKey>(
  val subject: String,
  val dependencies: List<ContextualTypeKey>,
)

/** Metadata for the target created by an assisted factory. */
public data class SuspendAssistedFactoryMetadata<ContextualTypeKey>(
  val factoryName: String,
  val targetName: String,
  val functionName: String,
  val functionIsSuspend: Boolean,
  val constructorDependencies: List<ContextualTypeKey>,
  val memberDependencies: List<ContextualTypeKey> = emptyList(),
)

/** Frontend-normalized facts used by the shared suspend validator. */
public data class SuspendBindingMetadata<ContextualTypeKey>(
  val isSuspend: Boolean,
  /** Whether this binding participates in the graph being validated. */
  val isReachable: Boolean = true,
  val isScoped: Boolean = false,
  val hasAdditionalSuspendWrapperUse: Boolean = false,
  val inspectDependencySuspendWrappers: Boolean = true,
  val hasAdditionalSuspendLazyUse: Boolean = false,
  val multibinding: SuspendMultibindingMetadata? = null,
  val memberInjections: List<SuspendMemberInjectionMetadata<ContextualTypeKey>> = emptyList(),
  val assistedFactory: SuspendAssistedFactoryMetadata<ContextualTypeKey>? = null,
)

/** Native frontend data needed to anchor and trace a shared validation issue. */
public sealed interface SuspendValidationSite<out ContextualTypeKey, out Binding, out Request> {
  /** A graph-level issue with no more specific source declaration. */
  public data object Graph : SuspendValidationSite<Nothing, Nothing, Nothing>

  /** An issue originating at a graph accessor or members-injector request. */
  public data class GraphRequest<ContextualTypeKey, Request>(
    val request: SuspendGraphRequest<ContextualTypeKey, Request>
  ) : SuspendValidationSite<ContextualTypeKey, Nothing, Request>

  /** An issue originating at one dependency of [binding]. */
  public data class BindingDependency<ContextualTypeKey, Binding>(
    val binding: Binding,
    val dependency: ContextualTypeKey,
  ) : SuspendValidationSite<ContextualTypeKey, Binding, Nothing>
}

/** Semantic category of a suspend validation issue. */
public enum class SuspendValidationIssueKind {
  FEATURE_DISABLED,
  MULTIBINDING,
  NON_SUSPEND_ACCESSOR,
  BLOCKING_WRAPPER,
  MEMBER_INJECTION,
  ASSISTED_FACTORY_SUSPEND_REQUIRED,
  MISSING_RUNTIME_COROUTINES,
}

/** A frontend-independent suspend validation failure. */
public data class SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request>(
  val kind: SuspendValidationIssueKind,
  val diagnosticId: MetroDiagnosticId,
  val title: String,
  val site: SuspendValidationSite<ContextualTypeKey, Binding, Request>,
  val path: SuspendBindingPath<TypeKey, ContextualTypeKey>? = null,
  val relatedBindings: List<Binding> = emptyList(),
)

/** Shared suspend analysis and validation output. */
public data class SuspendBindingValidationResult<TypeKey, ContextualTypeKey, Binding, Request>(
  val suspendKeys: Set<TypeKey>,
  val issues: List<SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request>>,
  val requiresRuntimeCoroutines: Boolean,
)
