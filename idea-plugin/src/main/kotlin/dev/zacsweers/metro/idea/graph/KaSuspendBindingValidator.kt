// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import androidx.collection.ScatterMap
import com.intellij.openapi.progress.ProgressManager
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.compiler.diagnostics.textOf
import dev.zacsweers.metro.compiler.graph.SuspendAssistedFactoryMetadata
import dev.zacsweers.metro.compiler.graph.SuspendBindingKind
import dev.zacsweers.metro.compiler.graph.SuspendBindingMetadata
import dev.zacsweers.metro.compiler.graph.SuspendBindingValidator
import dev.zacsweers.metro.compiler.graph.SuspendGraphRequest
import dev.zacsweers.metro.compiler.graph.SuspendGraphRequestKind
import dev.zacsweers.metro.compiler.graph.SuspendMemberInjectionMetadata
import dev.zacsweers.metro.compiler.graph.SuspendMultibindingMetadata
import dev.zacsweers.metro.compiler.graph.SuspendValidationIssue
import dev.zacsweers.metro.compiler.graph.SuspendValidationSite
import dev.zacsweers.metro.compiler.graph.toTraceSection
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import org.jetbrains.kotlin.name.StandardClassIds

/** Adapts IDEA binding snapshots and diagnostic locations to shared suspend validation. */
internal class KaSuspendBindingValidator(
  private val graph: KaGraphDeclaration,
  private val graphName: String,
  private val options: MetroOptions,
  private val sources: ValidationSourceSnapshot,
  private val graphConsumers: List<ConsumerEntry>,
  private val bindings: ScatterMap<KaTypeKey, KaBinding>,
  private val runtimeCoroutinesAvailable: Boolean,
  private val report: (MetroDiagnostic, KaBindingStack, List<KaBinding>) -> Unit,
) {
  private val suspendBindingAnalysis = SuspendBindingAnalysis(bindings::get)

  fun validate(): Set<KaTypeKey> {
    val requests = graphConsumers.map { consumer ->
      SuspendGraphRequest(
        contextKey = consumer.contextKey,
        source = consumer,
        kind =
          when (consumer.graphRequestKind) {
            ConsumerEntry.GraphRequestKind.ACCESSOR -> SuspendGraphRequestKind.ACCESSOR
            ConsumerEntry.GraphRequestKind.MEMBERS_INJECTOR ->
              SuspendGraphRequestKind.MEMBERS_INJECTOR
            null -> SuspendGraphRequestKind.OTHER
          },
        isSuspend = consumer.isSuspend,
      )
    }
    val validation =
      SuspendBindingValidator(
          bindings = bindings,
          requests = requests,
          metadata = ::suspendMetadata,
          bindingKind = ::suspendBindingKind,
          bindingIsScoped = { it.scope != null },
          multibindingIsSet = {
            (it as KaBinding.Multibinding).typeKey.type.classId == StandardClassIds.Set
          },
          analyze = suspendBindingAnalysis::analyzeWithPaths,
          rules = suspendBindingAnalysis.rules,
          suspendProvidersEnabled = options.enableSuspendProviders,
          functionProvidersEnabled = options.enableFunctionProviders,
          runtimeCoroutinesAvailable = runtimeCoroutinesAvailable,
          checkCanceled = ProgressManager::checkCanceled,
        )
        .validate()

    for (issue in validation.issues) {
      val stack = issueStack(issue)
      report(
        MetroDiagnostic(
          id = issue.diagnosticId,
          severity = MetroSeverity.ERROR,
          title = textOf(issue.title),
          sections = listOfNotNull(stack.toTraceSection()),
        ),
        stack,
        issue.relatedBindings,
      )
    }
    return validation.suspendKeys
  }

  private fun suspendBindingKind(binding: KaBinding): SuspendBindingKind {
    return when (binding) {
      is KaBinding.Multibinding -> SuspendBindingKind.MULTIBINDING
      is KaBinding.AssistedFactory -> SuspendBindingKind.ASSISTED_FACTORY
      is KaBinding.ConstructorInjected ->
        if (binding.memberDependencies.isEmpty()) {
          SuspendBindingKind.ORDINARY
        } else {
          SuspendBindingKind.MEMBER_INJECTING
        }
      else -> SuspendBindingKind.ORDINARY
    }
  }

  private fun suspendMetadata(binding: KaBinding): SuspendBindingMetadata<KaContextualTypeKey> {
    val typeName = binding.originClassId?.asFqNameString() ?: binding.typeKey.render(short = true)
    val memberInjections =
      when (binding) {
        is KaBinding.ConstructorInjected ->
          buildList {
            if (binding.memberDependencies.isNotEmpty()) {
              add(
                SuspendMemberInjectionMetadata(
                  subject = "'$typeName' member injection",
                  dependencies = binding.memberDependencies,
                )
              )
              add(
                SuspendMemberInjectionMetadata(
                  subject = "'$typeName' has @Inject members and",
                  dependencies = binding.constructorDependencies,
                )
              )
            }
          }
        is KaBinding.AssistedFactory ->
          if (binding.targetMemberDependencies.isEmpty()) {
            emptyList()
          } else {
            listOf(
              SuspendMemberInjectionMetadata(
                subject = "'${binding.implementationName ?: "assisted target"}' member injection",
                dependencies = binding.targetMemberDependencies,
              )
            )
          }
        else -> emptyList()
      }
    val multibinding =
      (binding as? KaBinding.Multibinding)?.let {
        val isSet = binding.typeKey.type.classId == StandardClassIds.Set
        SuspendMultibindingMetadata(
          isSet = isSet,
          mapKeyType = binding.typeKey.type.typeArguments.getOrNull(0)?.type?.shortType,
          mapValueType = binding.typeKey.type.typeArguments.getOrNull(1)?.type?.shortType,
        )
      }
    val assistedFactory =
      (binding as? KaBinding.AssistedFactory)?.let {
        SuspendAssistedFactoryMetadata(
          factoryName = binding.typeKey.render(short = true),
          targetName = binding.implementationName ?: "its assisted target",
          functionName = binding.factoryFunctionName ?: "create",
          functionIsSuspend = binding.factoryFunctionIsSuspend,
          constructorDependencies = binding.targetConstructorDependencies,
          memberDependencies = binding.targetMemberDependencies,
        )
      }
    return SuspendBindingMetadata(
      isSuspend = binding.isSuspend,
      isScoped = binding.scope != null,
      multibinding = multibinding,
      memberInjections = memberInjections,
      assistedFactory = assistedFactory,
    )
  }

  private fun issueStack(
    issue: SuspendValidationIssue<KaTypeKey, KaContextualTypeKey, KaBinding, ConsumerEntry>
  ): KaBindingStack {
    val stack = KaBindingStack(graph)
    val path = issue.path
    when (val site = issue.site) {
      SuspendValidationSite.Graph -> Unit
      is SuspendValidationSite.GraphRequest -> {
        val request = site.request
        val entry =
          KaBindingStack.Entry.requestedAt(
            request.contextKey,
            request.source,
            graphName,
            sources.name(request.source),
          )
        stack.push(if (path == null) entry else entry.withTrailingComment(NEEDS_SUSPEND_SUPPORT))
      }
      is SuspendValidationSite.BindingDependency -> {
        val binding = site.binding
        val dependency = site.dependency
        val entry = KaBindingStack.Entry.injectedAt(dependency, binding, sources.location(binding))
        stack.push(if (path == null) entry else entry.withTrailingComment(NEEDS_SUSPEND_SUPPORT))
      }
    }
    if (path == null) return stack

    for (edge in path.edges) {
      val binding = bindings[edge.consumerKey] ?: break
      stack.push(
        KaBindingStack.Entry.injectedAt(edge.dependency, binding, sources.location(binding))
          .withTrailingComment(NEEDS_SUSPEND_SUPPORT)
      )
    }
    if (path.sourceIsSuspend) {
      bindings[path.sourceKey]?.let {
        stack.push(KaBindingStack.Entry.providedAt(it, sources.location(it)))
      }
    }
    return stack
  }

  private companion object {
    private const val NEEDS_SUSPEND_SUPPORT = "needs suspend support"
  }
}
