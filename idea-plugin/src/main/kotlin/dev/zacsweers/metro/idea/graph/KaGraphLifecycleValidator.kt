// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import androidx.collection.ScatterMap
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.compiler.diagnostics.textOf
import dev.zacsweers.metro.compiler.graph.GraphAdjacency
import dev.zacsweers.metro.compiler.graph.toTraceSection
import dev.zacsweers.metro.idea.model.BindingResolutionSession
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.GraphComposition
import dev.zacsweers.metro.idea.model.GraphQueryContext
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.canonicalContextKey
import dev.zacsweers.metro.idea.model.graphTypeKey

internal val GraphComposition.isSuspendCloseable: Boolean
  get() = supertypeKeys.any { it.type.classId == MetroClassIds.suspendCloseableGraph }

internal val GraphComposition.isCloseable: Boolean
  get() =
    isSuspendCloseable || supertypeKeys.any { it.type.classId == MetroClassIds.closeableGraph }

/**
 * Checks callbacks after interface selection and uses resolved ownership for lifecycle warnings.
 */
internal class KaGraphLifecycleValidator(
  private val session: BindingResolutionSession,
  private val queryContext: GraphQueryContext,
  private val options: MetroOptions,
  private val consumers: List<ConsumerEntry>,
  private val sources: ValidationSourceSnapshot,
  private val report: (MetroDiagnostic, KaBindingStack) -> Unit,
) {
  private val graph = queryContext.graphContext.graph
  val composition = session.graphComposition(queryContext)

  fun validateDeclarations() {
    val callbacks = composition.lifecycleCallbacks
    if (callbacks.isNotEmpty() && !composition.isCloseable) {
      error(
        graph.pointer,
        "The assembled graph must implement CloseableGraph or SuspendCloseableGraph to declare lifecycle callbacks.",
      )
    }
    if (composition.isCloseable) {
      for (implementation in composition.closeImplementations) {
        error(implementation, "Metro generates close(). Put cleanup in an @OnGraphClose callback.")
      }
    }
    val suspends = composition.isSuspendCloseable || callbacks.any { it.isSuspend }
    if (suspends && !options.enableSuspendProviders) {
      error(
        graph.pointer,
        "Suspend graph lifecycle requires enableSuspendProviders.",
        MetroDiagnosticId.SUSPEND_PROVIDERS_NOT_ENABLED,
      )
      if (composition.isSuspendCloseable && !graph.runtimeCoroutinesAvailable) {
        error(
          graph.pointer,
          "Add dev.zacsweers.metro:runtime-coroutines to the compile and runtime classpath.",
          MetroDiagnosticId.MISSING_RUNTIME_COROUTINES,
        )
      }
    }
    for (callback in callbacks) {
      if (!callback.isImplementedUnit) {
        error(
          callback.pointer,
          "Lifecycle callbacks must be implemented member functions returning Unit.",
        )
      }
      if (callback.hasInvalidParameters) {
        error(callback.pointer, "Lifecycle callbacks cannot declare receivers or type parameters.")
      }
      if (!callback.isSuspend) {
        continue
      }
      if (callback.onInit && !graph.factoryIsSuspend) {
        error(
          callback.pointer,
          "Suspending initialization requires a graph factory with a suspend SAM.",
        )
      }
      if (callback.onClose && !composition.isSuspendCloseable) {
        error(callback.pointer, "Suspending close callbacks require SuspendCloseableGraph.")
      }
    }
    val parent = queryContext.graphContext.chain.getOrNull(1) ?: return
    val parentComposition = session.graphComposition(queryContext, parent)
    val synchronousParent = parentComposition.isCloseable && !parentComposition.isSuspendCloseable
    if (synchronousParent && composition.isSuspendCloseable) {
      error(
        graph.pointer,
        "A CloseableGraph parent cannot own a SuspendCloseableGraph child. Make the parent implement SuspendCloseableGraph.",
      )
    }
    if (callbacks.none { it.onInit && it.isSuspend }) {
      return
    }
    for (creation in parentComposition.synchronousExtensionCreations) {
      if (creation.extension in graph.selfReferences) {
        error(
          creation.pointer,
          "A child with suspending initialization must be created through a suspend factory SAM or suspend function.",
        )
      }
    }
  }

  fun validateBindings(
    bindings: ScatterMap<KaTypeKey, KaBinding>,
    adjacency: GraphAdjacency<KaTypeKey>,
    childUses: Iterable<KaTypeKey>,
  ) {
    fun reachableFrom(roots: Iterable<KaTypeKey>): Set<KaTypeKey> {
      val visited = hashSetOf<KaTypeKey>()
      val pending = ArrayDeque<KaTypeKey>()
      pending.addAll(roots)
      while (pending.isNotEmpty()) {
        ProgressManager.checkCanceled()
        val key = pending.removeFirst()
        if (visited.add(key)) {
          pending.addAll(adjacency.forward[key].orEmpty())
        }
      }
      return visited
    }
    fun resolveAlias(key: KaTypeKey): KaBinding? {
      var binding = bindings[key]
      val visited = hashSetOf<KaTypeKey>()
      while (binding is KaBinding.Alias && visited.add(binding.typeKey)) {
        binding = binding.consumedKey?.typeKey?.let(bindings::get)
      }
      return binding
    }
    val ordinaryRoots =
      consumers
        .filter { it.graphRequestKind != ConsumerEntry.GraphRequestKind.CLOSE_CALLBACK }
        .map { it.key } + childUses
    val usedOutsideClose = reachableFrom(ordinaryRoots)
    for (consumer in consumers) {
      if (consumer.lifecycleOwner == null) {
        continue
      }
      val binding = resolveAlias(consumer.key) ?: continue
      val scoped =
        when (binding) {
          is KaBinding.GraphDependency -> binding.isLifecycleScoped
          is KaBinding.BoundInstance -> true
          else -> binding.scope != null
        }
      val scalar = !consumer.contextKey.isWrapped
      if (scalar && !scoped) {
        warning(
          consumer,
          MetroDiagnosticId.UNSCOPED_LIFECYCLE_PARAMETER,
          "Unscoped ${binding.typeKey.render(short = true)} is injected into @${consumer.lifecycleAnnotation}. Scope the binding or request a provider or lazy value to resolve it explicitly.",
        )
      }
      if (consumer.graphRequestKind != ConsumerEntry.GraphRequestKind.CLOSE_CALLBACK) {
        continue
      }
      val closeOnly =
        reachableFrom(listOf(consumer.key))
          .mapNotNull(::resolveAlias)
          .distinctBy { it.typeKey }
          .filter { resolved ->
            val external =
              resolved is KaBinding.BoundInstance || resolved is KaBinding.GraphDependency
            resolved.typeKey !in usedOutsideClose && !external
          }
      if (closeOnly.isNotEmpty()) {
        val names = closeOnly.joinToString { it.typeKey.render(short = true) }
        warning(
          consumer,
          MetroDiagnosticId.CLOSE_ONLY_BINDING,
          "$names can be created for the first time during close. Request these bindings during initialization or ordinary graph use if cleanup should only see initialized values.",
        )
      }
    }
  }

  private fun warning(consumer: ConsumerEntry, id: MetroDiagnosticId, message: String) {
    val suppressed = consumer.lifecycleSuppressions + graph.lifecycleSuppressions
    if (id.name in suppressed || "all" in suppressed) {
      return
    }
    val stack = KaBindingStack(graph)
    stack.push(
      KaBindingStack.Entry.requestedAt(
        consumer.contextKey,
        consumer,
        graph.classId?.asFqNameString() ?: graph.name.orEmpty(),
        sources.name(consumer),
      ),
    )
    report(
      MetroDiagnostic(
        id,
        MetroSeverity.WARNING,
        textOf(message),
        sections = listOfNotNull(stack.toTraceSection()),
      ),
      stack,
    )
  }

  private fun error(
    pointer: SmartPsiElementPointer<out PsiElement>,
    message: String,
    id: MetroDiagnosticId = MetroDiagnosticId.GRAPH_LIFECYCLE,
  ) {
    val stack = KaBindingStack(graph)
    val key = graph.graphTypeKey() ?: return
    stack.push(KaBindingStack.Entry(key.canonicalContextKey(), pointer = pointer))
    report(MetroDiagnostic(id, MetroSeverity.ERROR, textOf(message)), stack)
  }
}
