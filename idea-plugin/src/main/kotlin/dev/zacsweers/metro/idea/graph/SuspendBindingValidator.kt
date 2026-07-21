// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import androidx.collection.ScatterMap
import com.intellij.openapi.progress.ProgressManager
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.compiler.diagnostics.textOf
import dev.zacsweers.metro.compiler.graph.toTraceSection
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphNode
import dev.zacsweers.metro.idea.model.KaTypeKey
import org.jetbrains.kotlin.name.StandardClassIds

/** Validates IDEA binding snapshots with the compiler's suspend-propagation rules. */
internal class SuspendBindingValidator(
  private val graph: KaGraphNode,
  private val graphName: String,
  private val options: MetroOptions,
  private val graphConsumers: List<ConsumerEntry>,
  private val bindings: ScatterMap<KaTypeKey, KaBinding>,
  private val runtimeCoroutinesAvailable: Boolean,
  private val report: (MetroDiagnostic, KaBindingStack, List<KaBinding>) -> Unit,
) {
  fun validate(): Set<KaTypeKey> {
    if (!options.enableSuspendProviders) {
      validateDisabledUse()
      return emptySet()
    }

    val allKeys = mutableSetOf<KaTypeKey>()
    bindings.forEachKey(allKeys::add)
    val suspendKeys = SuspendBindingAnalysis(bindings::get).analyze(allKeys)
    if (suspendKeys.isEmpty()) {
      validateRuntimeCoroutines(suspendKeys)
      return emptySet()
    }

    val suspendMultibindings = validateSuspendMultibindings(suspendKeys)
    validateGraphAccessors(suspendKeys, suspendMultibindings)
    validateMemberInjection(suspendKeys)
    validateDependencyWrappers(suspendKeys, suspendMultibindings)
    validateAssistedFactories(suspendKeys)
    validateRuntimeCoroutines(suspendKeys)
    return suspendKeys
  }

  private fun validateDisabledUse() {
    var bindingUse: KaBinding? = null
    bindings.forEachValue { binding ->
      if (bindingUse != null) return@forEachValue
      val usesSuspendWrapper =
        binding.contextualTypeKey.wrappedType.containsSuspendWrapper() ||
          binding.dependencies.any { it.wrappedType.containsSuspendWrapper() }
      if (binding.isSuspend || usesSuspendWrapper) {
        bindingUse = binding
      }
    }
    val consumerUse = graphConsumers.firstOrNull {
      it.isSuspend || it.contextKey.wrappedType.containsSuspendWrapper()
    }
    if (bindingUse == null && consumerUse == null) return

    val stack = KaBindingStack(graph)
    if (consumerUse != null) {
      stack.push(KaBindingStack.Entry.requestedAt(consumerUse.contextKey, consumerUse, graphName))
    }
    reportDiagnostic(
      MetroDiagnosticId.SUSPEND_PROVIDERS_NOT_ENABLED,
      "Suspend provider support is disabled. Enable the `enable-suspend-providers` compiler " +
        "option or set `metro.enableSuspendProviders` to true.",
      stack,
      listOfNotNull(bindingUse),
    )
  }

  private fun validateSuspendMultibindings(suspendKeys: Set<KaTypeKey>): Set<KaTypeKey> {
    val suspendMultibindings = mutableSetOf<KaTypeKey>()
    bindings.forEachValue { binding ->
      ProgressManager.checkCanceled()
      if (binding !is KaBinding.Multibinding) return@forEachValue
      if (binding.typeKey !in suspendKeys) return@forEachValue
      val firstSuspendElement =
        binding.dependencies.firstOrNull { it.typeKey in suspendKeys } ?: return@forEachValue
      suspendMultibindings += binding.typeKey

      for (consumer in graphConsumers) {
        if (consumer.contextKey.typeKey != binding.typeKey) continue
        if (binding.supportsSuspendConsumption(consumer.contextKey)) continue
        val head = requestedAt(consumer)
        reportSuspendMultibinding(binding, firstSuspendElement, head, suspendKeys)
      }

      bindings.forEachValue inner@{ consumerBinding ->
        if (consumerBinding is KaBinding.AssistedFactory) return@inner
        for (dependency in consumerBinding.dependencies) {
          if (dependency.typeKey != binding.typeKey) continue
          if (binding.supportsSuspendConsumption(dependency)) continue
          val head = injectedAt(dependency, consumerBinding)
          reportSuspendMultibinding(binding, firstSuspendElement, head, suspendKeys)
        }
      }
    }
    return suspendMultibindings
  }

  private fun reportSuspendMultibinding(
    binding: KaBinding.Multibinding,
    firstSuspendElement: KaContextualTypeKey,
    head: KaBindingStack.Entry,
    suspendKeys: Set<KaTypeKey>,
  ) {
    val typeRender = binding.typeKey.render(short = true)
    val isSet = binding.typeKey.type.classId == StandardClassIds.Set
    val title =
      if (isSet) {
        "$typeRender aggregates suspend bindings, which is unsupported. Provider-valued set " +
          "multibindings are not supported. Remove the suspend contribution(s) or provide them " +
          "eagerly."
      } else {
        val keyType = binding.typeKey.type.typeArguments.getOrNull(0)?.shortType ?: "K"
        val valueType = binding.typeKey.type.typeArguments.getOrNull(1)?.shortType ?: "V"
        "$typeRender aggregates suspend bindings and must be consumed as " +
          "`Map<$keyType, suspend () -> $valueType>` so each value is initialized only when its " +
          "provider is invoked."
      }
    val stack = buildSuspendTrace(firstSuspendElement.typeKey, head, suspendKeys)
    reportDiagnostic(
      MetroDiagnosticId.MULTIBINDING_OVER_SUSPEND_BINDINGS,
      title,
      stack,
      listOf(binding),
    )
  }

  private fun validateGraphAccessors(
    suspendKeys: Set<KaTypeKey>,
    suspendMultibindings: Set<KaTypeKey>,
  ) {
    for (consumer in graphConsumers) {
      ProgressManager.checkCanceled()
      if (consumer.graphRequestKind != ConsumerEntry.GraphRequestKind.ACCESSOR) continue
      val contextKey = consumer.contextKey
      if (contextKey.typeKey !in suspendKeys) continue
      if (contextKey.typeKey in suspendMultibindings) continue
      if (contextKey.isValidSuspendBoundary()) continue

      val blockingWrapper = contextKey.wrappedType.lowestSynchronousWrapperName()
      if (blockingWrapper != null) {
        reportBlockingWrapper(
          contextKey,
          blockingWrapper,
          "access",
          requestedAt(consumer),
          suspendKeys,
        )
        continue
      }
      if (consumer.isSuspend || contextKey.isSuspendCapableBoundary) continue

      val typeRender = contextKey.typeKey.render(short = true)
      val deferredForm = suspendProviderRender(typeRender)
      val stack = buildSuspendTrace(contextKey.typeKey, requestedAt(consumer), suspendKeys)
      reportDiagnostic(
        MetroDiagnosticId.SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR,
        "$typeRender bindings must be a suspend function or $deferredForm because it depends on " +
          "suspend bindings and requires a suspend context.",
        stack,
      )
    }
  }

  private fun validateMemberInjection(suspendKeys: Set<KaTypeKey>) {
    for (consumer in graphConsumers) {
      if (consumer.graphRequestKind != ConsumerEntry.GraphRequestKind.MEMBERS_INJECTOR) continue
      val dependency = consumer.contextKey
      if (!dependency.propagatesSuspend(suspendKeys)) continue
      reportMemberInjection(
        subject = "Member injection",
        dependency = dependency,
        head = requestedAt(consumer),
        suspendKeys = suspendKeys,
      )
    }

    bindings.forEachValue { binding ->
      if (binding !is KaBinding.ConstructorInjected || !binding.hasInjectedMembers) {
        return@forEachValue
      }
      if (binding.typeKey !in suspendKeys) return@forEachValue
      val dependency =
        binding.dependencies.firstOrNull { it.propagatesSuspend(suspendKeys) }
          ?: return@forEachValue
      val typeName = binding.originClassId?.asFqNameString() ?: binding.typeKey.render(short = true)
      reportMemberInjection(
        subject = "'$typeName' has @Inject members and",
        dependency = dependency,
        head = injectedAt(dependency, binding),
        suspendKeys = suspendKeys,
        related = listOf(binding),
      )
    }
  }

  private fun validateDependencyWrappers(
    suspendKeys: Set<KaTypeKey>,
    suspendMultibindings: Set<KaTypeKey>,
  ) {
    bindings.forEachValue { binding ->
      if (binding is KaBinding.AssistedFactory) return@forEachValue
      for (dependency in binding.dependencies) {
        if (dependency.typeKey !in suspendKeys) continue
        if (dependency.typeKey in suspendMultibindings) continue
        if (dependency.isValidSuspendBoundary()) continue
        val blockingWrapper = dependency.wrappedType.lowestSynchronousWrapperName() ?: continue
        reportBlockingWrapper(
          dependency,
          blockingWrapper,
          "depend on",
          injectedAt(dependency, binding),
          suspendKeys,
          listOf(binding),
        )
      }
    }
  }

  private fun validateAssistedFactories(suspendKeys: Set<KaTypeKey>) {
    bindings.forEachValue { binding ->
      if (binding !is KaBinding.AssistedFactory) return@forEachValue

      for (dependency in binding.dependencies) {
        if (dependency.typeKey !in suspendKeys) continue
        if (dependency.isValidSuspendBoundary()) continue
        val blockingWrapper = dependency.wrappedType.lowestSynchronousWrapperName() ?: continue
        reportBlockingWrapper(
          dependency,
          blockingWrapper,
          "depend on",
          injectedAt(dependency, binding),
          suspendKeys,
          listOf(binding),
        )
      }

      val memberDependency =
        binding.targetMemberDependencies.firstOrNull { it.propagatesSuspend(suspendKeys) }
      if (memberDependency != null) {
        val target = binding.implementationName ?: "assisted target"
        reportMemberInjection(
          subject = "'$target' member injection",
          dependency = memberDependency,
          head = injectedAt(memberDependency, binding),
          suspendKeys = suspendKeys,
          related = listOf(binding),
        )
      }

      if (binding.factoryFunctionIsSuspend) return@forEachValue
      val constructorDependency =
        binding.targetConstructorDependencies.firstOrNull { it.propagatesSuspend(suspendKeys) }
          ?: return@forEachValue
      val factory = binding.typeKey.render(short = true)
      val target = binding.implementationName ?: "its assisted target"
      val function = binding.factoryFunctionName ?: "create"
      val stack =
        buildSuspendTrace(
          constructorDependency.typeKey,
          injectedAt(constructorDependency, binding),
          suspendKeys,
        )
      reportDiagnostic(
        MetroDiagnosticId.ASSISTED_FACTORY_SUSPEND_REQUIRED,
        "'$factory' creates '$target', which depends on suspend bindings. Declare '$function' as " +
          "a suspend function so it can await them.",
        stack,
        listOf(binding),
      )
    }
  }

  private fun validateRuntimeCoroutines(suspendKeys: Set<KaTypeKey>) {
    if (runtimeCoroutinesAvailable) return

    val scopedSuspendKeys = mutableListOf<KaTypeKey>()
    val suspendLazyKeys = mutableListOf<KaTypeKey>()
    var relatedBinding: KaBinding? = null
    bindings.forEachValue { binding ->
      if (binding.scope != null && binding.typeKey in suspendKeys) {
        scopedSuspendKeys += binding.typeKey
        if (relatedBinding == null) relatedBinding = binding
      }
      val requestsSuspendLazy =
        binding.contextualTypeKey.wrappedType.containsSuspendLazy() ||
          binding.dependencies.any { it.wrappedType.containsSuspendLazy() }
      if (requestsSuspendLazy) {
        suspendLazyKeys += binding.typeKey
        if (relatedBinding == null) relatedBinding = binding
      }
    }
    for (consumer in graphConsumers) {
      if (consumer.contextKey.wrappedType.containsSuspendLazy()) {
        suspendLazyKeys += consumer.contextKey.typeKey
      }
    }
    if (scopedSuspendKeys.isEmpty() && suspendLazyKeys.isEmpty()) return

    val trigger =
      if (scopedSuspendKeys.isNotEmpty()) {
        val key = scopedSuspendKeys.map { it.render(short = true) }.sorted().first()
        "The scoped suspend binding `$key` caches its awaited value, which needs the optional " +
          "runtime-coroutines artifact."
      } else {
        val key = suspendLazyKeys.map { it.render(short = true) }.sorted().first()
        "`$key` requests a `SuspendLazy` value, which needs the optional runtime-coroutines " +
          "artifact."
      }
    reportDiagnostic(
      MetroDiagnosticId.MISSING_RUNTIME_COROUTINES,
      "$trigger Add `dev.zacsweers.metro:runtime-coroutines` to the compile and runtime classpath.",
      KaBindingStack(graph),
      listOfNotNull(relatedBinding),
    )
  }

  private fun reportMemberInjection(
    subject: String,
    dependency: KaContextualTypeKey,
    head: KaBindingStack.Entry,
    suspendKeys: Set<KaTypeKey>,
    related: List<KaBinding> = emptyList(),
  ) {
    val dependencyRender = dependency.typeKey.render(short = true)
    val stack = buildSuspendTrace(dependency.typeKey, head, suspendKeys)
    reportDiagnostic(
      MetroDiagnosticId.MEMBER_INJECTION_OVER_SUSPEND_BINDING,
      "$subject depends on suspend binding '$dependencyRender', but member injection cannot " +
        "combine with suspend bindings. Defer the dependency as " +
        "`${suspendProviderRender(dependencyRender)}` (or `SuspendLazy<$dependencyRender>`) instead.",
      stack,
      related,
    )
  }

  private fun reportBlockingWrapper(
    dependency: KaContextualTypeKey,
    wrapper: String,
    action: String,
    head: KaBindingStack.Entry,
    suspendKeys: Set<KaTypeKey>,
    related: List<KaBinding> = emptyList(),
  ) {
    val typeRender = dependency.typeKey.render(short = true)
    val replacement =
      if (wrapper == "Provider") {
        "`${suspendProviderRender(typeRender)}`"
      } else {
        "`SuspendLazy<$typeRender>`"
      }
    val diagnosticId = MetroDiagnosticId.suspendBindingWrappedIn(wrapper)
    val phrase = dependency.wrappedType.blockingWrapperPhrase(wrapper)
    val stack = buildSuspendTrace(dependency.typeKey, head, suspendKeys)
    reportDiagnostic(
      diagnosticId,
      "Cannot $action suspend binding '$typeRender' $phrase. Use $replacement instead.",
      stack,
      related,
    )
  }

  private fun buildSuspendTrace(
    start: KaTypeKey,
    head: KaBindingStack.Entry,
    suspendKeys: Set<KaTypeKey>,
  ): KaBindingStack {
    val stack = KaBindingStack(graph)
    stack.push(head.withTrailingComment(NEEDS_SUSPEND_SUPPORT))
    val visited = mutableSetOf<KaTypeKey>()
    var currentKey: KaTypeKey? = start
    while (currentKey != null && visited.add(currentKey)) {
      val current = bindings[currentKey] ?: break
      if (current.isSuspend) {
        stack.push(KaBindingStack.Entry.providedAt(current))
        break
      }
      val dependency =
        current.dependencies.firstOrNull { it.propagatesSuspend(suspendKeys) } ?: break
      stack.push(injectedAt(dependency, current).withTrailingComment(NEEDS_SUSPEND_SUPPORT))
      currentKey = dependency.typeKey
    }
    return stack
  }

  private fun KaContextualTypeKey.propagatesSuspend(suspendKeys: Set<KaTypeKey>): Boolean {
    return typeKey in suspendKeys && !stopsSuspendPropagation()
  }

  private fun KaContextualTypeKey.stopsSuspendPropagation(): Boolean {
    if (isDeferrable) return true
    return (bindings[typeKey] as? KaBinding.GraphDependency)?.canPassThrough(this) == true
  }

  private fun KaContextualTypeKey.isValidSuspendBoundary(): Boolean {
    if (isSuspendCapableBoundary) return true
    return (bindings[typeKey] as? KaBinding.GraphDependency)?.canPassThrough(this) == true
  }

  private fun KaBinding.Multibinding.supportsSuspendConsumption(
    contextKey: KaContextualTypeKey
  ): Boolean {
    val isSet = typeKey.type.classId == StandardClassIds.Set
    return !isSet && contextKey.isMapSuspendProvider
  }

  private fun suspendProviderRender(typeRender: String): String {
    return if (options.enableFunctionProviders) {
      "suspend () -> $typeRender"
    } else {
      "SuspendProvider<$typeRender>"
    }
  }

  private fun requestedAt(consumer: ConsumerEntry): KaBindingStack.Entry {
    return KaBindingStack.Entry.requestedAt(consumer.contextKey, consumer, graphName)
  }

  private fun injectedAt(
    dependency: KaContextualTypeKey,
    binding: KaBinding,
  ): KaBindingStack.Entry = KaBindingStack.Entry.injectedAt(dependency, binding)

  private fun reportDiagnostic(
    id: MetroDiagnosticId,
    title: String,
    stack: KaBindingStack,
    related: List<KaBinding> = emptyList(),
  ) {
    report(
      MetroDiagnostic(
        id = id,
        severity = MetroSeverity.ERROR,
        title = textOf(title),
        sections = listOfNotNull(stack.toTraceSection()),
      ),
      stack,
      related,
    )
  }

  private companion object {
    private const val NEEDS_SUSPEND_SUPPORT = "needs suspend support"
  }
}
