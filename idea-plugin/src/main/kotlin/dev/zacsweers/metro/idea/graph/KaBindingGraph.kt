// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import androidx.collection.ScatterMap
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.diagnostics.LocatedItem
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.compiler.diagnostics.Note
import dev.zacsweers.metro.compiler.diagnostics.SimilarBindingItem
import dev.zacsweers.metro.compiler.diagnostics.buildText
import dev.zacsweers.metro.compiler.diagnostics.render.DiagnosticRenderer
import dev.zacsweers.metro.compiler.diagnostics.render.RenderProfile
import dev.zacsweers.metro.compiler.diagnostics.textOf
import dev.zacsweers.metro.compiler.graph.ErrorReporter
import dev.zacsweers.metro.compiler.graph.GraphTopology
import dev.zacsweers.metro.compiler.graph.MissingBindingHints
import dev.zacsweers.metro.compiler.graph.MutableBindingGraph
import dev.zacsweers.metro.compiler.graph.duplicateMapKeysDiagnostic
import dev.zacsweers.metro.compiler.graph.emptyMultibindingDiagnostic
import dev.zacsweers.metro.compiler.graph.toText
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphNode
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.name.StandardClassIds

/** A structured diagnostic from a graph seal, with navigable stack entries. */
internal class KaGraphDiagnostic(
  val diagnostic: MetroDiagnostic,
  val stack: List<KaBindingStack.Entry>,
  /** The bindings this diagnostic is about, such as the sources of a duplicate. */
  val related: List<KaBinding> = emptyList(),
) {
  val id: MetroDiagnosticId
    get() = diagnostic.id

  val severity: MetroSeverity
    get() = diagnostic.severity

  /** Renders the full diagnostic with the compiler's plain console renderer. */
  fun render(): String = PLAIN_RENDERER.render(diagnostic)

  private companion object {
    private val PLAIN_RENDERER = DiagnosticRenderer(RenderProfile.PLAIN)
  }
}

/** The outcome of sealing one graph. */
internal class GraphValidationResult(
  val context: GraphContext,
  val diagnostics: List<KaGraphDiagnostic>,
  /** Null when a fatal error aborted the seal before sorting. */
  val topology: GraphTopology<KaTypeKey>?,
  val bindings: ScatterMap<KaTypeKey, KaBinding>,
) {
  val graph: KaGraphNode
    get() = context.graph
}

/**
 * The Analysis API analog of the compiler's `IrBindingGraph`. Adapts one graph's index view to the
 * shared [MutableBindingGraph] and runs its validation via [seal]. Missing bindings, duplicates,
 * and cycles come from the shared core. Multibinding aggregate checks run after. One instance per
 * seal.
 */
internal class KaBindingGraph(
  private val index: BindingIndex,
  private val context: GraphContext,
  private val options: MetroOptions,
) :
  // The TraceScope delegation satisfies seal()'s tracing context parameter with a no-op tracer
  TraceScope by TraceScope.noop(),
  ErrorReporter<KaBindingStack> {

  private val graph = context.graph
  private val graphName = graph.classId?.asFqNameString() ?: graph.name ?: "<unknown>"
  private val diagnostics = mutableListOf<KaGraphDiagnostic>()

  private val useSiteModule: KaModule? =
    graph.pointer.element?.let { KaModuleProvider.getModule(it.project, it, useSiteModule = null) }

  // Cleared once sealing completes so lookup state doesn't outlive the population phase.
  private var _bindingLookup: KaBindingLookup? =
    KaBindingLookup(index, graph, context, options, useSiteModule)
    set(value) {
      if (value == null) {
        field?.clear()
      }
      field = value
    }

  private val bindingLookup
    get() = _bindingLookup ?: error("Binding lookup already cleared")

  private val realGraph =
    MutableBindingGraph<
      KaTypeSnapshot,
      KaTypeKey,
      KaContextualTypeKey,
      KaBinding,
      KaBindingStack.Entry,
      KaBindingStack,
    >(
      newBindingStack = { KaBindingStack(graph) },
      newBindingStackEntry = { contextKey, callingBinding, roots ->
        // A null calling binding means the key was requested directly by a root
        if (callingBinding == null) {
          roots.getValue(contextKey)
        } else {
          KaBindingStack.Entry.injectedAt(contextKey, callingBinding)
        }
      },
      computeBindings = { contextKey, _, stack ->
        bindingLookup.lookup(contextKey) { key, bindings ->
          reportDuplicateBindings(key, bindings, stack)
        }
      },
      errorReporter = this,
      missingBindingHints = ::missingBindingHints,
    )

  fun seal(): GraphValidationResult {
    val setupStack = KaBindingStack(graph)
    for (chainGraph in context.chain) {
      realGraph.tryPut(graphInstanceBinding(chainGraph) ?: continue, setupStack)
    }

    val roots = LinkedHashMap<KaContextualTypeKey, KaBindingStack.Entry>()
    for (consumer in index.accessorsFor(graph)) {
      // hasDefault is what makes the shared core treat an absent optional binding as not missing
      val contextKey = consumer.contextKey.withDefault(consumer.isOptional)
      roots[contextKey] = KaBindingStack.Entry.requestedAt(contextKey, consumer, graphName)
    }

    val topology =
      try {
        val topo =
          realGraph.seal(roots = roots, shrinkUnusedBindings = options.shrinkUnusedBindings)
        validateAggregates()
        topo
      } catch (e: ProcessCanceledException) {
        throw e
      } catch (_: SealAborted) {
        null
      } catch (e: Exception) {
        // Covers reportCompilerBug and unexpected model states. Report instead of crashing.
        logger<KaBindingGraph>().warn("Sealing $graphName failed", e)
        report(
          MetroDiagnostic(
            id = MetroDiagnosticId.GENERIC,
            severity = MetroSeverity.ERROR,
            title = textOf(e.message ?: "Unknown graph validation error"),
          ),
          KaBindingStack(graph),
        )
        null
      } finally {
        // Clear out the binding lookup now that we're done
        _bindingLookup = null
      }

    // The seal's ScatterMap is handed off directly. The graph adapter is discarded after seal,
    // so nothing else can mutate it.
    return GraphValidationResult(context, diagnostics.toList(), topology, realGraph.bindings)
  }

  // The bindings the in-flight report is about, attached to the next reported diagnostic. The
  // shared core builds the diagnostic itself, so this is the only seam to carry them through.
  private var pendingRelated: List<KaBinding> = emptyList()

  override fun report(diagnostic: MetroDiagnostic, stack: KaBindingStack) {
    diagnostics += KaGraphDiagnostic(diagnostic, stack.entries.toList(), pendingRelated)
  }

  override fun reportFatal(diagnostic: MetroDiagnostic, stack: KaBindingStack): Nothing {
    report(diagnostic, stack)
    throw SealAborted()
  }

  override fun flush() {}

  private fun reportDuplicateBindings(
    key: KaTypeKey,
    bindings: List<KaBinding>,
    stack: KaBindingStack,
  ) {
    pendingRelated = bindings
    try {
      realGraph.reportDuplicateBindings(key, bindings, stack)
    } finally {
      pendingRelated = emptyList()
    }
  }

  /** Checks aggregates for duplicate map keys and unexpected emptiness after sealing. */
  private fun validateAggregates() {
    for (node in bindingLookup.aggregates) {
      ProgressManager.checkCanceled()
      val aggregate = node.binding
      val contributions = node.contributions
      if (contributions.isEmpty()) {
        if (!aggregate.allowEmpty) {
          report(emptyMultibindingDiagnostic(aggregate.typeKey), KaBindingStack(graph))
        }
        continue
      }

      val isMap = aggregate.typeKey.type.classId == StandardClassIds.Map
      if (!isMap) continue
      val duplicates =
        contributions
          .filter { it.mapKeyValue != null }
          .groupBy { it.mapKeyValue }
          .filterValues { it.size > 1 }
      for ((mapKey, dupes) in duplicates) {
        val locations = dupes.map { dupe ->
          val locationDiagnostic = dupe.renderLocationDiagnostic(short = true)
          LocatedItem(
            location = locationDiagnostic.location,
            code = locationDiagnostic.description,
          )
        }
        pendingRelated = dupes
        try {
          report(
            duplicateMapKeysDiagnostic(aggregate.typeKey, mapKey.orEmpty(), locations),
            KaBindingStack(graph),
          )
        } finally {
          pendingRelated = emptyList()
        }
      }
    }
  }

  private fun missingBindingHints(typeKey: KaTypeKey): MissingBindingHints {
    val notes = mutableListOf<Note>()
    val similar = mutableListOf<SimilarBindingItem>()

    for (binding in index.bindings) {
      when {
        binding.typeKey == typeKey -> {
          // A binding for this exact key exists but is not a member of this graph.
          notes +=
            Note.note(
              buildText {
                append("a binding for this key exists at ")
                append(binding.location() ?: "<unknown>")
                append(" but is not a member of this graph. Check its scope, its container's ")
                append("wiring, or its contribution scope.")
              }
            )
        }
        binding.typeKey.type == typeKey.type && binding.typeKey.qualifier != typeKey.qualifier -> {
          similar +=
            SimilarBindingItem(
              key = binding.typeKey.toText(),
              description = "same type, different qualifier",
              location = binding.location(),
            )
        }
      }
    }

    val shortName = typeKey.type.classId?.shortClassName?.asString()
    if (shortName != null) {
      val factory =
        index.bindings.filterIsInstance<KaBinding.AssistedFactory>().firstOrNull {
          it.implementationName == shortName
        }
      if (factory != null) {
        notes +=
          Note.help(
            buildText {
              append(typeKey.toText())
              append(" is assisted-injected. Inject its factory ")
              append(factory.typeKey.toText())
              append(" instead and call its create function.")
            }
          )
      }
    }

    return MissingBindingHints(notes = notes, similarBindings = similar)
  }

  private fun graphInstanceBinding(chainGraph: KaGraphNode): KaBinding.GraphInstance? {
    val classId = chainGraph.classId ?: return null
    val snapshot =
      KaTypeSnapshot(classId.asFqNameString(), classId.shortClassName.asString(), classId)
    return KaBinding.GraphInstance(chainGraph.pointer, KaTypeKey(snapshot))
  }
}

/** Thrown by [KaBindingGraph.reportFatal] and caught by [KaBindingGraph.seal]. */
private class SealAborted : RuntimeException() {
  // Control flow only, so skip the expensive stack trace capture
  override fun fillInStackTrace(): Throwable = this
}
