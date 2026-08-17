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

    val allKeys = mutableSetOf<TypeKey>()
    val metadataByKey = mutableMapOf<TypeKey, SuspendBindingMetadata<ContextualTypeKey>>()
    bindings.forEachValue { binding ->
      allKeys += binding.typeKey
      metadataByKey[binding.typeKey] = metadata(binding)
    }

    val analysis = analyze(allKeys)
    val suspendKeys = analysis.suspendKeys
    val issues =
      mutableListOf<SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request>>()
    val suspendMultibindings =
      validateMultibindings(
        analysis,
        metadataByKey,
        issues,
      )
    validateGraphRequests(analysis, suspendMultibindings, issues)
    validateMemberInjection(analysis, metadataByKey, issues)
    validateDependencyWrappers(analysis, metadataByKey, suspendMultibindings, issues)
    validateAssistedFactories(analysis, metadataByKey, issues)

    val runtimeRequirement = runtimeRequirement(analysis, metadataByKey)
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

  private fun indexConsumers(): Map<TypeKey, List<BindingDependency<Binding, ContextualTypeKey>>> {
    val consumers =
      mutableMapOf<TypeKey, MutableList<BindingDependency<Binding, ContextualTypeKey>>>()
    bindings.forEachValue { binding ->
      for (dependency in binding.dependencies) {
        consumers.getOrPut(dependency.typeKey, ::mutableListOf) +=
          BindingDependency(binding, dependency)
      }
    }
    return consumers
  }

  private fun validateMultibindings(
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
    metadataByKey: Map<TypeKey, SuspendBindingMetadata<ContextualTypeKey>>,
    issues: MutableList<SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request>>,
  ): Set<TypeKey> {
    val suspendMultibindings = mutableSetOf<TypeKey>()
    var consumersByKey: Map<TypeKey, List<BindingDependency<Binding, ContextualTypeKey>>>? = null
    bindings.forEachValue { binding ->
      checkCanceled()
      val multibinding = metadataByKey.getValue(binding.typeKey).multibinding ?: return@forEachValue
      if (binding.typeKey !in analysis.suspendKeys) return@forEachValue
      val firstSuspendElement =
        binding.dependencies.firstOrNull { it.typeKey in analysis.suspendKeys }
          ?: return@forEachValue
      suspendMultibindings += binding.typeKey

      for (request in requests) {
        if (request.contextKey.typeKey != binding.typeKey) continue
        if (rules.supportsSuspendMultibindingConsumption(multibinding.isSet, request.contextKey)) {
          continue
        }
        issues += multibindingIssue(binding, multibinding, request, firstSuspendElement, analysis)
      }

      // Most graphs have no suspend multibindings, so avoid indexing every dependency edge.
      var consumers = consumersByKey
      if (consumers == null) {
        consumers = indexConsumers()
        consumersByKey = consumers
      }
      for ((consumer, dependency) in consumers[binding.typeKey].orEmpty()) {
        if (metadataByKey.getValue(consumer.typeKey).assistedFactory != null) continue
        if (rules.supportsSuspendMultibindingConsumption(multibinding.isSet, dependency)) continue
        issues +=
          multibindingIssue(
            binding,
            multibinding,
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
    multibinding: SuspendMultibindingMetadata,
    request: SuspendGraphRequest<ContextualTypeKey, Request>,
    firstSuspendElement: ContextualTypeKey,
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
  ): SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request> =
    issue(
      kind = SuspendValidationIssueKind.MULTIBINDING,
      id = MetroDiagnosticId.MULTIBINDING_OVER_SUSPEND_BINDINGS,
      title = multibindingTitle(binding, multibinding),
      site = SuspendValidationSite.GraphRequest(request),
      path = analysis.pathFrom(firstSuspendElement.typeKey) { it.typeKey },
      relatedBindings = listOf(binding),
    )

  private fun multibindingIssue(
    binding: Binding,
    multibinding: SuspendMultibindingMetadata,
    consumer: Binding,
    dependency: ContextualTypeKey,
    firstSuspendElement: ContextualTypeKey,
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
  ): SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request> =
    issue(
      kind = SuspendValidationIssueKind.MULTIBINDING,
      id = MetroDiagnosticId.MULTIBINDING_OVER_SUSPEND_BINDINGS,
      title = multibindingTitle(binding, multibinding),
      site = SuspendValidationSite.BindingDependency(consumer, dependency),
      path = analysis.pathFrom(firstSuspendElement.typeKey) { it.typeKey },
      relatedBindings = listOf(binding),
    )

  private fun multibindingTitle(
    binding: Binding,
    multibinding: SuspendMultibindingMetadata,
  ): String {
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
      if (contextKey.typeKey in suspendMultibindings) continue
      if (rules.isValidBoundary(contextKey)) continue

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

  private fun validateMemberInjection(
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
    metadataByKey: Map<TypeKey, SuspendBindingMetadata<ContextualTypeKey>>,
    issues: MutableList<SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request>>,
  ) {
    for (request in requests) {
      if (request.kind != SuspendGraphRequestKind.MEMBERS_INJECTOR) continue
      val dependency = request.contextKey
      if (!rules.propagates(dependency) { it in analysis.suspendKeys }) continue
      issues += memberInjectionIssue("Member injection", dependency, request, analysis)
    }

    bindings.forEachValue { binding ->
      for (memberInjection in metadataByKey.getValue(binding.typeKey).memberInjections) {
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

  private fun validateDependencyWrappers(
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
    metadataByKey: Map<TypeKey, SuspendBindingMetadata<ContextualTypeKey>>,
    suspendMultibindings: Set<TypeKey>,
    issues: MutableList<SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request>>,
  ) {
    bindings.forEachValue { binding ->
      if (metadataByKey.getValue(binding.typeKey).assistedFactory != null) return@forEachValue
      for (dependency in binding.dependencies) {
        if (dependency.typeKey !in analysis.suspendKeys) continue
        if (dependency.typeKey in suspendMultibindings) continue
        if (rules.isValidBoundary(dependency)) continue
        val blockingWrapper = dependency.wrappedType.lowestSynchronousWrapperName() ?: continue
        issues += blockingWrapperIssue(dependency, blockingWrapper, "depend on", binding, analysis)
      }
    }
  }

  private fun validateAssistedFactories(
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
    metadataByKey: Map<TypeKey, SuspendBindingMetadata<ContextualTypeKey>>,
    issues: MutableList<SuspendValidationIssue<TypeKey, ContextualTypeKey, Binding, Request>>,
  ) {
    bindings.forEachValue { binding ->
      val assisted = metadataByKey.getValue(binding.typeKey).assistedFactory ?: return@forEachValue
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
    analysis: SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>,
    metadataByKey: Map<TypeKey, SuspendBindingMetadata<ContextualTypeKey>>,
  ): RuntimeRequirement {
    var requiresScopedSuspendRuntime = false
    var requiresSuspendLazyRuntime = false
    val scopedSuspendKeys = if (runtimeCoroutinesAvailable) null else mutableListOf<TypeKey>()
    val suspendLazyKeys = if (runtimeCoroutinesAvailable) null else mutableListOf<TypeKey>()
    bindings.forEachValue { binding ->
      val bindingMetadata = metadataByKey.getValue(binding.typeKey)
      if (!bindingMetadata.isReachable) return@forEachValue
      if (bindingMetadata.isScoped && binding.typeKey in analysis.suspendKeys) {
        requiresScopedSuspendRuntime = true
        scopedSuspendKeys?.add(binding.typeKey)
      }
      val requestsSuspendLazy =
        bindingMetadata.hasAdditionalSuspendLazyUse ||
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
