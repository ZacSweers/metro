// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.ScatterMap
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId

/**
 * Applies Metro's suspend binding rules independently of IR or the Analysis API.
 *
 * Frontends supply only declaration-derived metadata and retain responsibility for choosing a
 * diagnostic source anchor and rendering the shared witness path with their native binding stack.
 */
public class SuspendBindingValidator<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, TypeKey>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, ContextualTypeKey>,
  Binding : BaseBinding<Type, TypeKey, ContextualTypeKey>,
  Request,
>(
  private val bindings: ScatterMap<TypeKey, Binding>,
  private val requests: List<SuspendGraphRequest<ContextualTypeKey, Request>>,
  private val metadata: (Binding) -> SuspendBindingMetadata<ContextualTypeKey>,
  private val bindingKind: (Binding) -> SuspendBindingKind,
  private val bindingIsScoped: (Binding) -> Boolean,
  private val multibindingIsSet: (Binding) -> Boolean,
  private val bindingIsReachable: (Binding) -> Boolean = { true },
  private val hasAdditionalSuspendLazyUse: (Binding) -> Boolean = { false },
  private val analyze:
    (Iterable<TypeKey>) -> SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
  private val rules: SuspendBindingRules<Type, TypeKey, ContextualTypeKey, Binding>,
  private val suspendProvidersEnabled: Boolean,
  private val functionProvidersEnabled: Boolean,
  private val runtimeCoroutinesAvailable: Boolean,
  private val runtimeCoroutinesAlreadyRequired: Boolean = false,
  private val additionalRuntimeRequests: Iterable<ContextualTypeKey> = emptyList(),
  private val checkCanceled: () -> Unit = {},
) {
  /** Ordinary bindings never enter this cache or require declaration metadata. */
  private var metadataByKey: MutableMap<TypeKey, SuspendBindingMetadata<ContextualTypeKey>>? = null

  public fun validate():
    SuspendBindingValidationResult<
      TypeKey,
      ContextualTypeKey,
      Binding,
      Request,
    > {
    if (!suspendProvidersEnabled) {
      val issue = disabledFeatureIssue()
      return SuspendBindingValidationResult(
        suspendKeys = emptySet(),
        issues = listOfNotNull(issue),
        requiresRuntimeCoroutines = false,
      )
    }

    // Compute the set of type keys that transitively require a suspend context.
    val analysis = analyze(bindings.asMap().keys)
    val suspendKeys = analysis.suspendKeys
    val issues =
      mutableListOf<SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request>>()
    val suspendMultibindings =
      validateMultibindings(
        analysis,
        issues,
      )
    validateGraphRequests(analysis, suspendMultibindings, issues)
    validateMemberInjection(analysis, issues)
    validateDependencyWrappers(analysis, suspendMultibindings, issues)
    validateAssistedFactories(analysis, issues)

    val runtimeRequirement = runtimeRequirement(analysis)
    if (runtimeRequirement.required && !runtimeCoroutinesAvailable) {
      issues +=
        issue(
          kind = SuspendValidationIssueKind.MISSING_RUNTIME_COROUTINES,
          id = MetroDiagnosticId.MISSING_RUNTIME_COROUTINES,
          title =
            runtimeRequirement.trigger?.let { "$it " }.orEmpty() +
              SuspendDiagnosticMessages.MISSING_RUNTIME_COROUTINES_FIX,
          site = SuspendValidationSite.Graph,
        )
    }

    return SuspendBindingValidationResult(suspendKeys, issues, runtimeRequirement.required)
  }

  private fun bindingMetadata(binding: Binding): SuspendBindingMetadata<ContextualTypeKey> {
    val existingMetadata = metadataByKey
    if (existingMetadata != null) {
      return existingMetadata.getOrPut(binding.typeKey) { metadata(binding) }
    }

    val result = metadata(binding)
    val createdMetadata = mutableMapOf<TypeKey, SuspendBindingMetadata<ContextualTypeKey>>()
    createdMetadata[binding.typeKey] = result
    metadataByKey = createdMetadata
    return result
  }

  private fun disabledFeatureIssue():
    SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request>? {
    var bindingUse: Binding? = null
    bindings.forEachValue { binding ->
      if (bindingUse != null) return@forEachValue
      // Disabled graphs need only the first offending binding, not a project-wide metadata map.
      val bindingMetadata = metadata(binding)
      val usesSuspendWrapper =
        binding.contextualTypeKey.wrappedType.containsSuspendWrapper() ||
          (bindingMetadata.inspectDependencySuspendWrappers &&
            binding.dependencies.any { it.wrappedType.containsSuspendWrapper() })
      if (
        bindingMetadata.isSuspend ||
          bindingMetadata.hasAdditionalSuspendWrapperUse ||
          usesSuspendWrapper
      ) {
        bindingUse = binding
      }
    }
    val requestUse = requests.firstOrNull {
      it.isSuspend || it.contextKey.wrappedType.containsSuspendWrapper()
    }
    if (bindingUse == null && requestUse == null) return null

    val site =
      if (requestUse != null) {
        SuspendValidationSite.GraphRequest(requestUse)
      } else {
        SuspendValidationSite.Graph
      }
    return issue(
      kind = SuspendValidationIssueKind.FEATURE_DISABLED,
      id = MetroDiagnosticId.SUSPEND_PROVIDERS_NOT_ENABLED,
      title = SuspendDiagnosticMessages.SUSPEND_PROVIDERS_NOT_ENABLED,
      site = site,
      relatedBindings = listOfNotNull(bindingUse),
    )
  }

  /**
   * Indexes requests to multibindings. Assisted targets contribute their original constructor
   * requests because factory graph edges are Provider-wrapped for cycle detection.
   */
  private fun indexConsumers(): Map<TypeKey, List<BindingDependency<Binding, ContextualTypeKey>>> {
    val consumers =
      mutableMapOf<TypeKey, MutableList<BindingDependency<Binding, ContextualTypeKey>>>()
    bindings.forEachValue { binding ->
      checkCanceled()
      val dependencies =
        if (bindingKind(binding) == SuspendBindingKind.ASSISTED_FACTORY) {
          val assisted =
            checkNotNull(bindingMetadata(binding).assistedFactory) {
              "Only assisted factories can provide target constructor dependencies"
            }
          assisted.constructorDependencies
        } else {
          binding.dependencies
        }
      for (dependency in dependencies) {
        checkCanceled()
        consumers.getOrPut(dependency.typeKey, ::mutableListOf) +=
          BindingDependency(binding, dependency)
      }
    }
    return consumers
  }

  /**
   * Multibinding aggregation cannot await suspend element initialization. Deferred-value maps such
   * as `Map<K, suspend () -> V>` are supported; provider-valued set forms are not.
   */
  private fun validateMultibindings(
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
    issues: MutableList<SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request>>,
  ): Set<TypeKey> {
    val suspendMultibindings = mutableSetOf<TypeKey>()
    var consumersByKey: Map<TypeKey, List<BindingDependency<Binding, ContextualTypeKey>>>? = null
    bindings.forEachValue { binding ->
      checkCanceled()
      if (bindingKind(binding) != SuspendBindingKind.MULTIBINDING) return@forEachValue
      if (binding.typeKey !in analysis.suspendKeys) return@forEachValue
      val isSet = multibindingIsSet(binding)
      val firstSuspendElement =
        binding.dependencies.firstOrNull { it.typeKey in analysis.suspendKeys }
          ?: return@forEachValue
      suspendMultibindings += binding.typeKey

      // Check graph roots consuming this multibinding.
      for (request in requests) {
        if (request.contextKey.typeKey != binding.typeKey) continue
        if (rules.supportsSuspendMultibindingConsumption(isSet, request.contextKey)) {
          continue
        }
        issues += multibindingIssue(binding, request, firstSuspendElement, analysis)
      }

      // Most graphs have no suspend multibindings, so avoid indexing every dependency edge.
      var consumers = consumersByKey
      if (consumers == null) {
        consumers = indexConsumers()
        consumersByKey = consumers
      }
      // Check dependency edges consuming this multibinding.
      for ((consumer, dependency) in consumers[binding.typeKey].orEmpty()) {
        if (rules.supportsSuspendMultibindingConsumption(isSet, dependency)) {
          continue
        }
        issues +=
          multibindingIssue(
            binding,
            consumer,
            dependency,
            firstSuspendElement,
            analysis,
          )
      }
    }
    return suspendMultibindings
  }

  private fun multibindingIssue(
    binding: Binding,
    request: SuspendGraphRequest<ContextualTypeKey, Request>,
    firstSuspendElement: ContextualTypeKey,
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
  ): SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request> =
    issue(
      kind = SuspendValidationIssueKind.MULTIBINDING,
      id = MetroDiagnosticId.MULTIBINDING_OVER_SUSPEND_BINDINGS,
      title = multibindingTitle(binding),
      site = SuspendValidationSite.GraphRequest(request),
      path = analysis.pathFrom(firstSuspendElement.typeKey) { it.typeKey },
      relatedBindings = listOf(binding),
    )

  private fun multibindingIssue(
    binding: Binding,
    consumer: Binding,
    dependency: ContextualTypeKey,
    firstSuspendElement: ContextualTypeKey,
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
  ): SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request> =
    issue(
      kind = SuspendValidationIssueKind.MULTIBINDING,
      id = MetroDiagnosticId.MULTIBINDING_OVER_SUSPEND_BINDINGS,
      title = multibindingTitle(binding),
      site = SuspendValidationSite.BindingDependency(consumer, dependency),
      path = analysis.pathFrom(firstSuspendElement.typeKey) { it.typeKey },
      relatedBindings = listOf(binding),
    )

  private fun multibindingTitle(binding: Binding): String {
    val multibinding =
      checkNotNull(bindingMetadata(binding).multibinding) {
        "Only multibindings can produce suspend multibinding issues"
      }
    val typeRender = binding.typeKey.render(short = true)
    return if (multibinding.isSet) {
      "$typeRender aggregates suspend bindings, which is unsupported. Provider-valued set " +
        "multibindings are not supported. Remove the suspend contribution(s) or provide them eagerly."
    } else {
      val keyType = multibinding.mapKeyType ?: "K"
      val valueType = multibinding.mapValueType ?: "V"
      "$typeRender aggregates suspend bindings and must be consumed as " +
        "`Map<$keyType, suspend () -> $valueType>` so each value is initialized only when its " +
        "provider is invoked."
    }
  }

  /** Validates each accessor directly so accessors sharing a contextual key are all checked. */
  private fun validateGraphRequests(
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
    suspendMultibindings: Set<TypeKey>,
    issues: MutableList<SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request>>,
  ) {
    for (request in requests) {
      checkCanceled()
      if (request.kind != SuspendGraphRequestKind.ACCESSOR) continue
      val contextKey = request.contextKey
      if (contextKey.typeKey !in analysis.suspendKeys) continue
      // Suspend multibindings have already been reported with a more specific message.
      if (contextKey.typeKey in suspendMultibindings) continue
      // Included graphs can expose an exact wrapper value without flattening it.
      if (rules.isValidBoundary(contextKey)) continue

      // The innermost synchronous wrapper cannot await a suspend binding, regardless of outer ones.
      val blockingWrapper = contextKey.wrappedType.lowestSynchronousWrapperName()
      if (blockingWrapper != null) {
        issues += blockingWrapperIssue(contextKey, blockingWrapper, "access", request, analysis)
        continue
      }
      if (request.isSuspend || contextKey.isSuspendCapableBoundary) continue

      val typeRender = contextKey.typeKey.render(short = true)
      issues +=
        issue(
          kind = SuspendValidationIssueKind.NON_SUSPEND_ACCESSOR,
          id = MetroDiagnosticId.SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR,
          title =
            "$typeRender bindings must be a suspend function or " +
              "${suspendProviderRender(typeRender)} because it depends on suspend bindings and " +
              "requires a suspend context.",
          site = SuspendValidationSite.GraphRequest(request),
          path = analysis.pathFrom(contextKey.typeKey) { it.typeKey },
        )
    }
  }

  /**
   * Member injectors cannot await suspend bindings, and graph-local suspend factories do not run
   * ordinary member injection after construction.
   */
  private fun validateMemberInjection(
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
    issues: MutableList<SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request>>,
  ) {
    for (request in requests) {
      if (request.kind != SuspendGraphRequestKind.MEMBERS_INJECTOR) continue
      val dependency = request.contextKey
      if (!rules.propagates(dependency) { it in analysis.suspendKeys }) continue
      issues += memberInjectionIssue("Member injection", dependency, request, analysis)
    }

    bindings.forEachValue { binding ->
      val kind = bindingKind(binding)
      if (
        kind != SuspendBindingKind.MEMBER_INJECTING && kind != SuspendBindingKind.ASSISTED_FACTORY
      ) {
        return@forEachValue
      }
      for (memberInjection in bindingMetadata(binding).memberInjections) {
        val dependency =
          memberInjection.dependencies.firstOrNull {
            rules.propagates(it) { key -> key in analysis.suspendKeys }
          } ?: continue
        issues += memberInjectionIssue(memberInjection.subject, binding, dependency, analysis)
      }
    }
  }

  private fun memberInjectionIssue(
    subject: String,
    dependency: ContextualTypeKey,
    request: SuspendGraphRequest<ContextualTypeKey, Request>,
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
  ): SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request> =
    issue(
      kind = SuspendValidationIssueKind.MEMBER_INJECTION,
      id = MetroDiagnosticId.MEMBER_INJECTION_OVER_SUSPEND_BINDING,
      title = memberInjectionTitle(subject, dependency),
      site = SuspendValidationSite.GraphRequest(request),
      path = analysis.pathFrom(dependency.typeKey) { it.typeKey },
    )

  private fun memberInjectionIssue(
    subject: String,
    binding: Binding,
    dependency: ContextualTypeKey,
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
  ): SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request> =
    issue(
      kind = SuspendValidationIssueKind.MEMBER_INJECTION,
      id = MetroDiagnosticId.MEMBER_INJECTION_OVER_SUSPEND_BINDING,
      title = memberInjectionTitle(subject, dependency),
      site = SuspendValidationSite.BindingDependency(binding, dependency),
      path = analysis.pathFrom(dependency.typeKey) { it.typeKey },
      relatedBindings = listOf(binding),
    )

  private fun memberInjectionTitle(subject: String, dependency: ContextualTypeKey): String {
    val dependencyRender = dependency.typeKey.render(short = true)
    return "$subject depends on suspend binding '$dependencyRender', but member injection cannot " +
      "combine with suspend bindings. Defer the dependency as " +
      "`${suspendProviderRender(dependencyRender)}` (or `SuspendLazy<$dependencyRender>`) instead."
  }

  /**
   * Validates wrapper stacks whose innermost wrapper is Provider or Lazy over a suspend binding.
   */
  private fun validateDependencyWrappers(
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
    suspendMultibindings: Set<TypeKey>,
    issues: MutableList<SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request>>,
  ) {
    bindings.forEachValue { binding ->
      // Assisted-factory dependencies are synthetically Provider-wrapped for cycle detection.
      if (bindingKind(binding) == SuspendBindingKind.ASSISTED_FACTORY) return@forEachValue
      for (dependency in binding.dependencies) {
        if (dependency.typeKey !in analysis.suspendKeys) continue
        if (dependency.typeKey in suspendMultibindings) continue
        if (rules.isValidBoundary(dependency)) continue
        val blockingWrapper = dependency.wrappedType.lowestSynchronousWrapperName() ?: continue
        issues += blockingWrapperIssue(dependency, blockingWrapper, "depend on", binding, analysis)
      }
    }
  }

  /**
   * Assisted factories consuming suspend bindings must have a suspend SAM. Their targets are not in
   * the graph, so their Provider/Lazy-wrapped suspend dependencies must also be checked here.
   */
  private fun validateAssistedFactories(
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
    issues: MutableList<SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request>>,
  ) {
    bindings.forEachValue { binding ->
      if (bindingKind(binding) != SuspendBindingKind.ASSISTED_FACTORY) return@forEachValue
      val assisted =
        checkNotNull(bindingMetadata(binding).assistedFactory) {
          "Only assisted factories can produce assisted factory issues"
        }
      for (dependency in assisted.constructorDependencies + assisted.memberDependencies) {
        if (dependency.typeKey !in analysis.suspendKeys) continue
        if (rules.isValidBoundary(dependency)) continue
        val blockingWrapper = dependency.wrappedType.lowestSynchronousWrapperName() ?: continue
        issues += blockingWrapperIssue(dependency, blockingWrapper, "depend on", binding, analysis)
      }

      if (assisted.functionIsSuspend) return@forEachValue
      val dependency =
        assisted.constructorDependencies.firstOrNull {
          rules.propagates(it) { key -> key in analysis.suspendKeys }
        } ?: return@forEachValue
      issues +=
        issue(
          kind = SuspendValidationIssueKind.ASSISTED_FACTORY_SUSPEND_REQUIRED,
          id = MetroDiagnosticId.ASSISTED_FACTORY_SUSPEND_REQUIRED,
          title =
            "'${assisted.factoryName}' creates '${assisted.targetName}', which depends on suspend " +
              "bindings. Declare '${assisted.functionName}' as a suspend function so it can await them.",
          site = SuspendValidationSite.BindingDependency(binding, dependency),
          path = analysis.pathFrom(dependency.typeKey) { it.typeKey },
          relatedBindings = listOf(binding),
        )
    }
  }

  private fun blockingWrapperIssue(
    dependency: ContextualTypeKey,
    wrapper: String,
    action: String,
    request: SuspendGraphRequest<ContextualTypeKey, Request>,
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
  ): SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request> =
    issue(
      kind = SuspendValidationIssueKind.BLOCKING_WRAPPER,
      id = MetroDiagnosticId.suspendBindingWrappedIn(wrapper),
      title = blockingWrapperTitle(dependency, wrapper, action),
      site = SuspendValidationSite.GraphRequest(request),
      path = analysis.pathFrom(dependency.typeKey) { it.typeKey },
    )

  private fun blockingWrapperIssue(
    dependency: ContextualTypeKey,
    wrapper: String,
    action: String,
    binding: Binding,
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
  ): SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request> =
    issue(
      kind = SuspendValidationIssueKind.BLOCKING_WRAPPER,
      id = MetroDiagnosticId.suspendBindingWrappedIn(wrapper),
      title = blockingWrapperTitle(dependency, wrapper, action),
      site = SuspendValidationSite.BindingDependency(binding, dependency),
      path = analysis.pathFrom(dependency.typeKey) { it.typeKey },
      relatedBindings = listOf(binding),
    )

  private fun blockingWrapperTitle(
    dependency: ContextualTypeKey,
    wrapper: String,
    action: String,
  ): String {
    val typeRender = dependency.typeKey.render(short = true)
    val replacement =
      if (wrapper == "Provider") {
        "`${suspendProviderRender(typeRender)}`"
      } else {
        "`SuspendLazy<$typeRender>`"
      }
    return "Cannot $action suspend binding '$typeRender' " +
      "${dependency.wrappedType.blockingWrapperPhrase(wrapper)}. Use $replacement instead."
  }

  private fun runtimeRequirement(
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>
  ): RuntimeRequirement {
    if (runtimeCoroutinesAlreadyRequired && runtimeCoroutinesAvailable) {
      return RuntimeRequirement(required = true, trigger = null)
    }

    var requiresScopedSuspendRuntime = false
    var requiresSuspendLazyRuntime = false
    val scopedSuspendKeys = if (runtimeCoroutinesAvailable) null else mutableListOf<TypeKey>()
    val suspendLazyKeys = if (runtimeCoroutinesAvailable) null else mutableListOf<TypeKey>()
    bindings.forEachValue { binding ->
      if (!bindingIsReachable(binding)) return@forEachValue
      if (bindingIsScoped(binding) && binding.typeKey in analysis.suspendKeys) {
        requiresScopedSuspendRuntime = true
        scopedSuspendKeys?.add(binding.typeKey)
      }
      val requestsSuspendLazy =
        hasAdditionalSuspendLazyUse(binding) ||
          binding.contextualTypeKey.wrappedType.containsSuspendLazy() ||
          binding.dependencies.any { it.wrappedType.containsSuspendLazy() }
      if (requestsSuspendLazy) {
        requiresSuspendLazyRuntime = true
        suspendLazyKeys?.add(binding.typeKey)
      }
    }
    for (request in requests) {
      if (request.contextKey.wrappedType.containsSuspendLazy()) {
        requiresSuspendLazyRuntime = true
        suspendLazyKeys?.add(request.contextKey.typeKey)
      }
    }
    for (request in additionalRuntimeRequests) {
      if (request.wrappedType.containsSuspendLazy()) {
        requiresSuspendLazyRuntime = true
        suspendLazyKeys?.add(request.typeKey)
      }
    }

    val requiresRuntime =
      runtimeCoroutinesAlreadyRequired || requiresScopedSuspendRuntime || requiresSuspendLazyRuntime
    if (!requiresRuntime || runtimeCoroutinesAvailable) {
      return RuntimeRequirement(requiresRuntime, trigger = null)
    }

    if (!scopedSuspendKeys.isNullOrEmpty()) {
      val key = scopedSuspendKeys.map { it.render(short = true) }.sorted().first()
      return RuntimeRequirement(
        required = true,
        trigger = SuspendDiagnosticMessages.scopedSuspendRuntimeTrigger(key),
      )
    }
    if (!suspendLazyKeys.isNullOrEmpty()) {
      val key = suspendLazyKeys.map { it.render(short = true) }.sorted().first()
      return RuntimeRequirement(
        required = true,
        trigger = SuspendDiagnosticMessages.suspendLazyRuntimeTrigger("`$key`"),
      )
    }
    return RuntimeRequirement(runtimeCoroutinesAlreadyRequired, trigger = null)
  }

  private fun suspendProviderRender(typeRender: String): String {
    return if (functionProvidersEnabled) {
      "suspend () -> $typeRender"
    } else {
      "SuspendProvider<$typeRender>"
    }
  }

  private fun issue(
    kind: SuspendValidationIssueKind,
    id: MetroDiagnosticId,
    title: String,
    site: SuspendValidationSite<ContextualTypeKey, Binding, Request>,
    path: SuspendBindingPath<TypeKey, ContextualTypeKey>? = null,
    relatedBindings: List<Binding> = emptyList(),
  ): SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request> =
    SuspendValidationIssue(kind, id, title, site, path, relatedBindings)

  private data class BindingDependency<Binding, ContextualTypeKey>(
    val binding: Binding,
    val dependency: ContextualTypeKey,
  )

  private data class RuntimeRequirement(val required: Boolean, val trigger: String?)
}
