// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import androidx.collection.ScatterMap
import dev.zacsweers.metro.compiler.compat.propertyIfAccessorCompat
import dev.zacsweers.metro.compiler.diagnostics.factory
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.graph.GraphAdjacency
import dev.zacsweers.metro.compiler.graph.SuspendAssistedFactoryMetadata
import dev.zacsweers.metro.compiler.graph.SuspendBindingKind
import dev.zacsweers.metro.compiler.graph.SuspendBindingMetadata
import dev.zacsweers.metro.compiler.graph.SuspendBindingValidationResult
import dev.zacsweers.metro.compiler.graph.SuspendBindingValidator
import dev.zacsweers.metro.compiler.graph.SuspendGraphRequest
import dev.zacsweers.metro.compiler.graph.SuspendGraphRequestKind
import dev.zacsweers.metro.compiler.graph.SuspendMemberInjectionMetadata
import dev.zacsweers.metro.compiler.graph.SuspendMultibindingMetadata
import dev.zacsweers.metro.compiler.graph.SuspendValidationIssue
import dev.zacsweers.metro.compiler.graph.SuspendValidationIssueKind
import dev.zacsweers.metro.compiler.graph.SuspendValidationSite
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.graph.IrBinding.AssistedFactory
import dev.zacsweers.metro.compiler.ir.graph.IrBinding.ConstructorInjected
import dev.zacsweers.metro.compiler.ir.graph.IrBinding.MembersInjected
import dev.zacsweers.metro.compiler.ir.graph.IrBinding.Provided
import dev.zacsweers.metro.compiler.ir.injectedFunctionOrNull
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.render
import dev.zacsweers.metro.compiler.ir.requireSimpleType
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.util.kotlinFqName

/** IR metadata, source-location, and stack-rendering adapter for shared suspend validation. */
internal class IrSuspendBindingValidator(
  metroContext: IrMetroContext,
  private val node: GraphNode.Local,
  private val bindings: ScatterMap<IrTypeKey, IrBinding>,
  private val roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
  private val adjacency: GraphAdjacency<IrTypeKey>,
  private val runtimeCoroutinesAlreadyRequired: Boolean,
  private val report: (Sequence<IrDeclaration?>, String, KtDiagnosticFactory1<String>) -> Unit,
) : IrMetroContext by metroContext {
  fun validate():
    SuspendBindingValidationResult<
      IrTypeKey,
      IrContextualTypeKey,
      IrBinding,
      GraphAccessor,
    > {
    val analysis = SuspendBindingAnalysis(bindings::get)
    val requests =
      node.accessors.map { accessor ->
        SuspendGraphRequest(
          contextKey = accessor.contextKey,
          source = accessor,
          kind = SuspendGraphRequestKind.ACCESSOR,
          isSuspend = accessor.metroFunction.ir.isSuspend,
        )
      }
    val validation =
      SuspendBindingValidator(
          bindings = bindings,
          requests = requests,
          metadata = { binding -> binding.suspendMetadata() },
          bindingKind = { binding ->
            when (binding) {
              is IrBinding.Multibinding -> SuspendBindingKind.MULTIBINDING
              is AssistedFactory -> SuspendBindingKind.ASSISTED_FACTORY
              is MembersInjected -> SuspendBindingKind.MEMBER_INJECTING
              is ConstructorInjected ->
                if (binding.injectedMembers.isEmpty()) {
                  SuspendBindingKind.ORDINARY
                } else {
                  SuspendBindingKind.MEMBER_INJECTING
                }
              else -> SuspendBindingKind.ORDINARY
            }
          },
          bindingIsScoped = { it.isScoped() },
          multibindingIsSet = { (it as IrBinding.Multibinding).isSet },
          bindingIsReachable = { it.typeKey in adjacency.forward },
          analyze = analysis::analyzeWithPaths,
          rules = analysis.rules,
          suspendProvidersEnabled = metroContext.options.enableSuspendProviders,
          functionProvidersEnabled = metroContext.options.enableFunctionProviders,
          // The compiler checks this once after sealing, including graphs without suspend bindings.
          runtimeCoroutinesAvailable = true,
          runtimeCoroutinesAlreadyRequired = runtimeCoroutinesAlreadyRequired,
          additionalRuntimeRequests = roots.keys,
        )
        .validate()
    for (issue in validation.issues) {
      reportIssue(issue)
    }
    return validation
  }

  private fun IrBinding.suspendMetadata(): SuspendBindingMetadata<IrContextualTypeKey> {
    val memberInjections =
      when (this) {
        is MembersInjected ->
          listOf(
            SuspendMemberInjectionMetadata(
              subject = "'${targetClassId.asFqNameString()}' member injection",
              dependencies = dependencies,
            )
          )
        is ConstructorInjected ->
          if (injectedMembers.isEmpty()) {
            emptyList()
          } else {
            listOf(
              SuspendMemberInjectionMetadata(
                subject = "'${type.kotlinFqName}' has @Inject members and",
                dependencies =
                  parameters.nonDispatchParameters
                    .filterNot { it.isAssisted }
                    .map { it.contextualTypeKey },
              )
            )
          }
        else -> emptyList()
      }
    val multibinding =
      (this as? IrBinding.Multibinding)?.let { binding ->
        if (binding.isSet) {
          SuspendMultibindingMetadata(isSet = true)
        } else {
          val arguments = binding.typeKey.type.requireSimpleType().arguments
          SuspendMultibindingMetadata(
            isSet = false,
            mapKeyType = arguments.getOrNull(0)?.typeOrFail?.render(short = true),
            mapValueType = arguments.getOrNull(1)?.typeOrFail?.render(short = true),
          )
        }
      }
    val assistedFactory =
      (this as? AssistedFactory)?.let { binding ->
        val target = binding.targetBinding
        SuspendAssistedFactoryMetadata(
          factoryName = binding.type.kotlinFqName.asString(),
          targetName = target.type.kotlinFqName.asString(),
          functionName = binding.function.name.asString(),
          functionIsSuspend = binding.function.isSuspend,
          constructorDependencies =
            target.parameters.nonDispatchParameters
              .filterNot { it.isAssisted }
              .map { it.contextualTypeKey },
        )
      }
    return SuspendBindingMetadata(
      isSuspend = isSuspend,
      isScoped = isScoped(),
      multibinding = multibinding,
      memberInjections = memberInjections,
      assistedFactory = assistedFactory,
    )
  }

  private fun reportIssue(
    issue: SuspendValidationIssue<IrTypeKey, IrContextualTypeKey, IrBinding, GraphAccessor>
  ) {
    val trace = issueTrace(issue)
    val message = buildString {
      append("[${issue.diagnosticId.fullId}] ")
      append(issue.title)
      if (trace.isNotEmpty()) {
        appendLine()
        appendLine()
        appendLine("Trace:")
        appendBindingStackEntries(node.sourceGraph.kotlinFqName, trace)
      }
      if (issue.kind == SuspendValidationIssueKind.NON_SUSPEND_ACCESSOR) {
        val site =
          issue.site.expectAs<
            SuspendValidationSite.GraphRequest<IrContextualTypeKey, GraphAccessor>
          >()
        val request = site.request
        val typeRender = request.contextKey.typeKey.render(short = true)
        val deferredForm = suspendFunctionRender(typeRender)
        appendLine()
        appendLine("Either:")
        appendLine(
          "  - Mark this accessor as `suspend fun` so it can await suspend dependencies, or"
        )
        append("  - Make the return type `$deferredForm`.")
      }
    }
    report(reportCandidates(issue), message, issue.diagnosticId.factory)
  }

  private fun reportCandidates(
    issue: SuspendValidationIssue<IrTypeKey, IrContextualTypeKey, IrBinding, GraphAccessor>
  ): Sequence<IrDeclaration?> {
    if (
      issue.kind == SuspendValidationIssueKind.FEATURE_DISABLED ||
        issue.kind == SuspendValidationIssueKind.MISSING_RUNTIME_COROUTINES
    ) {
      return sequenceOf(node.sourceGraph)
    }
    val requestSite =
      issue.site as? SuspendValidationSite.GraphRequest<IrContextualTypeKey, GraphAccessor>
    if (requestSite != null) {
      val request = requestSite.request
      // Resolve to the property (if it's a getter) so the diagnostic lands on the property
      // declaration, then walk fake overrides back to the original interface declaration.
      val declaration =
        request.source.metroFunction.ir.propertyIfAccessorCompat.expectAs<IrDeclarationWithName>()
      return sequenceOf(declaration.originalDeclarationIfOverride())
    }

    val dependencySite =
      issue.site as? SuspendValidationSite.BindingDependency<IrContextualTypeKey, IrBinding>
        ?: return sequenceOf(node.sourceGraph)
    val binding = dependencySite.binding
    if (issue.kind == SuspendValidationIssueKind.ASSISTED_FACTORY_SUSPEND_REQUIRED) {
      val assistedFactory = binding.expectAs<AssistedFactory>()
      return sequenceOf(assistedFactory.function.originalDeclarationIfOverride())
    }
    if (issue.kind == SuspendValidationIssueKind.MEMBER_INJECTION) {
      return when (binding) {
        is MembersInjected ->
          sequenceOf(
            dependencySite.dependency
              .let { binding.parameterFor(it.typeKey)?.ir }
              ?.originalDeclarationIfOverride(),
            node.sourceGraph,
          )
        is ConstructorInjected ->
          binding.injectedMembers
            .asSequence()
            .mapNotNull { bindings[it.typeKey] as? MembersInjected }
            .flatMap { it.parameters.nonDispatchParameters.asSequence() }
            .filter { it.isMember }
            .mapNotNull { it.ir?.originalDeclarationIfOverride() }
            .plus(node.sourceGraph)
        else -> sequenceOf(binding.reportableDeclaration, node.sourceGraph)
      }
    }

    val dependency = dependencySite.dependency
    val sourceBinding = if (binding is AssistedFactory) binding.targetBinding else binding
    // Prefer the specific parameter that wraps the suspend binding over its enclosing declaration.
    val parameter = dependency.let { dep ->
      sourceBinding.parameters.allParameters.firstOrNull { it.contextualTypeKey == dep }?.ir
        ?: sourceBinding.parameters.allParameters.firstOrNull { it.typeKey == dep.typeKey }?.ir
    }
    return sequenceOf(
      parameter?.originalDeclarationIfOverride(),
      sourceBinding.reportableDeclaration?.originalDeclarationIfOverride(),
      node.sourceGraph,
    )
  }

  /**
   * Traces dependency edges to a directly suspend source. Each step uses
   * [bindingStackEntryForDependency], and the trace ends with a `providedAt` entry naming the
   * suspend function. Non-suspend steps are annotated with [NEEDS_SUSPEND_SUPPORT] so the user can
   * see which declarations stand in the way.
   */
  private fun issueTrace(
    issue: SuspendValidationIssue<IrTypeKey, IrContextualTypeKey, IrBinding, GraphAccessor>
  ): List<IrBindingStack.Entry> {
    val path = issue.path ?: return emptyList()
    val head =
      when (val site = issue.site) {
        is SuspendValidationSite.GraphRequest -> {
          val request = site.request
          IrBindingStack.Entry.requestedAt(request.contextKey, request.source.metroFunction.ir)
        }
        is SuspendValidationSite.BindingDependency -> {
          val binding = site.binding
          val dependency = site.dependency
          val sourceBinding = if (binding is AssistedFactory) binding.targetBinding else binding
          bindingStackEntryForDependency(sourceBinding, dependency, dependency.typeKey)
        }
        SuspendValidationSite.Graph -> return emptyList()
      }
    val result = mutableListOf(head.withAnnotation(NEEDS_SUSPEND_SUPPORT))
    for (edge in path.edges) {
      val binding = bindings[edge.consumerKey] ?: break
      result +=
        bindingStackEntryForDependency(binding, edge.dependency, edge.dependency.typeKey)
          .withAnnotation(NEEDS_SUSPEND_SUPPORT)
    }
    if (path.sourceIsSuspend) {
      val source = bindings[path.sourceKey]
      val sourceFunction = (source as? Provided)?.providerFactory?.function
      if (source != null && sourceFunction != null) {
        // The source function is already suspend, so its providedAt entry needs no annotation.
        result += IrBindingStack.Entry.providedAt(source.contextualTypeKey, sourceFunction)
      }
    }
    return result
  }

  /**
   * Renders the recommended deferred-suspend wrapper for a type. Prefers `suspend () -> T` when
   * function-providers are enabled (the default and idiomatic form), falls back to
   * `SuspendProvider<T>` otherwise.
   */
  private fun suspendFunctionRender(typeRender: String): String {
    return if (metroContext.options.enableFunctionProviders) {
      "suspend () -> $typeRender"
    } else {
      "SuspendProvider<$typeRender>"
    }
  }

  private companion object {
    private const val NEEDS_SUSPEND_SUPPORT = "❌ needs suspend support"
  }
}

/** Checks source parameter metadata that may have been compiled with a different option value. */
context(context: IrMetroContext)
internal fun IrBinding.containsSuspendWrapperUse(): Boolean {
  if (this is ConstructorInjected) {
    val injectedFunction = type.injectedFunctionOrNull()?.owner
    if (injectedFunction != null) {
      return injectedFunction.parameters().nonDispatchParameters.any {
        !it.isAssisted && it.contextualTypeKey.wrappedType.containsSuspendWrapper()
      }
    }
  }
  val sourceParameters =
    if (this is AssistedFactory) {
      targetBinding.parameters.allParameters
    } else {
      parameters.allParameters
    }
  return sourceParameters.any {
    !it.isAssisted && it.contextualTypeKey.wrappedType.containsSuspendWrapper()
  }
}

context(context: IrMetroContext)
internal fun IrBinding.injectedFunctionUsesSuspendLazy(): Boolean {
  val injectedClass = (this as? ConstructorInjected)?.type ?: return false
  val injectedFunction = injectedClass.injectedFunctionOrNull()?.owner ?: return false
  return injectedFunction.parameters().nonDispatchParameters.any {
    !it.isAssisted && it.contextualTypeKey.wrappedType.containsSuspendLazy()
  }
}

/**
 * We always want to report the original declaration for overridable nodes, as fake overrides won't
 * necessarily have source that is reportable. Preserve parameter positions when following
 * overrides.
 */
internal fun IrDeclaration.originalDeclarationIfOverride(): IrDeclaration {
  return when (this) {
    is IrValueParameter -> {
      // Need to check if the parent is a fakeOverride function or property setter.
      val originalParent = parent.expectAs<IrFunction>().originalDeclarationIfOverride()
      originalParent.expectAs<IrFunction>().parameters[indexInParameters]
    }
    is IrSimpleFunction if isFakeOverride -> overriddenSymbolsSequence().last().owner
    is IrProperty if isFakeOverride -> overriddenSymbolsSequence().last().owner
    else -> this
  }
}
