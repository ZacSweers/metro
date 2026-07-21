// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import androidx.collection.ScatterMap
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.diagnostics.DiagnosticBatch
import dev.zacsweers.metro.compiler.diagnostics.DiagnosticSection
import dev.zacsweers.metro.compiler.diagnostics.LocatedItem
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.compiler.diagnostics.Note
import dev.zacsweers.metro.compiler.diagnostics.SimilarBindingItem
import dev.zacsweers.metro.compiler.diagnostics.Style
import dev.zacsweers.metro.compiler.diagnostics.buildText
import dev.zacsweers.metro.compiler.diagnostics.factory
import dev.zacsweers.metro.compiler.diagnostics.textOf
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.filterToSet
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.SUSPEND_PROVIDERS_NOT_ENABLED_MESSAGE
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.getValue
import dev.zacsweers.metro.compiler.graph.ErrorReporter
import dev.zacsweers.metro.compiler.graph.GraphAdjacency
import dev.zacsweers.metro.compiler.graph.MissingBindingHints
import dev.zacsweers.metro.compiler.graph.MutableBindingGraph
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.graph.duplicateMapKeysDiagnostic
import dev.zacsweers.metro.compiler.graph.emptyMultibindingDiagnostic
import dev.zacsweers.metro.compiler.graph.partitionBySCCs
import dev.zacsweers.metro.compiler.graph.toText
import dev.zacsweers.metro.compiler.graph.toTraceSection
import dev.zacsweers.metro.compiler.ir.IrBoundTypeResolver
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrContributionData
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.MISSING_RUNTIME_COROUTINES_MESSAGE
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.getAnnotation
import dev.zacsweers.metro.compiler.ir.hasErrorTypes
import dev.zacsweers.metro.compiler.ir.implements
import dev.zacsweers.metro.compiler.ir.injectedFunctionOrNull
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.locationOrNull
import dev.zacsweers.metro.compiler.ir.originContextOrNull
import dev.zacsweers.metro.compiler.ir.originOrNull
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.padForConsole
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.render
import dev.zacsweers.metro.compiler.ir.renderSourceLocation
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.requireMapKeyType
import dev.zacsweers.metro.compiler.ir.requireMapValueType
import dev.zacsweers.metro.compiler.ir.requireSetElementType
import dev.zacsweers.metro.compiler.ir.requireSimpleType
import dev.zacsweers.metro.compiler.ir.sourceGraphIfMetroGraph
import dev.zacsweers.metro.compiler.ir.toDiagnosticSpan
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.safePathString
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isSubtypeOf
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.name.ClassId

private const val MAX_SUSPICIOUS_UNUSED_MULTIBINDINGS_TO_REPORT = 3

internal data class ChildGraphScopeInfo(
  val reachableKeys: Set<IrTypeKey>,
  val scopeNames: Set<ClassId>,
)

internal class IrBindingGraph(
  metroContext: IrMetroContext,
  private val node: GraphNode.Local,
  private val newBindingStack: () -> IrBindingStack,
  // TODO improve this cleanup
  bindingLookup: BindingLookup,
  private val contributionData: IrContributionData,
  private val boundTypeResolver: IrBoundTypeResolver,
) : IrMetroContext by metroContext, ErrorReporter<IrBindingStack> {
  private var hasErrors = false

  /**
   * Type keys whose bindings transitively require a suspend context. Populated during
   * [validateSuspendBindings]. Consumed by codegen to pick suspend-flavored shapes (SuspendProvider
   * fields, nested SuspendFactory classes, SuspendDoubleCheck, SuspendDelegateFactory).
   */
  private var transitivelySuspendKeys: Set<IrTypeKey> = emptySet()

  /** Whether reachable codegen needs APIs from the optional runtime-coroutines artifact. */
  private var requiresRuntimeCoroutines = false

  /** Incremented whenever [realGraph] gains a binding before it is sealed. */
  private var graphGeneration = 0

  /** Resolves parent suspend bindings before this graph is sealed, when a child first needs it. */
  private var parentSuspendBindingAnalysis: SuspendBindingAnalysis? = null

  /**
   * Guards [parentSuspendBindingAnalysis] and the binding lookups it performs. Sibling extensions
   * may be validated concurrently, while the underlying binding lookup is not thread-safe.
   */
  private val parentSuspendResolutionLock = Any()

  /** Returns true if the binding for [typeKey] transitively requires a suspend context. */
  internal fun isTransitivelySuspend(typeKey: IrTypeKey): Boolean =
    typeKey in transitivelySuspendKeys

  private fun recordRuntimeCoroutinesUse(contextKey: IrContextualTypeKey) {
    if (
      metroContext.options.enableSuspendProviders && contextKey.wrappedType.containsSuspendLazy()
    ) {
      requiresRuntimeCoroutines = true
    }
  }

  /**
   * Resolves whether [typeKey] requires a suspend context before this graph is sealed. Child graph
   * extensions are validated first, so they cannot use [transitivelySuspendKeys] yet.
   */
  internal fun isTransitivelySuspendForChild(typeKey: IrTypeKey): Boolean {
    if (!metroContext.options.enableSuspendProviders) return false
    return synchronized(parentSuspendResolutionLock) {
      if (_bindingLookup == null) {
        return@synchronized isTransitivelySuspend(typeKey)
      }
      val analysis =
        parentSuspendBindingAnalysis
          ?: SuspendBindingAnalysis(
              findBinding = { findBinding(it, allowLookup = true) },
              currentGraphGeneration = { graphGeneration },
            )
            .also { parentSuspendBindingAnalysis = it }
      analysis.isSuspend(typeKey)
    }
  }

  private sealed interface PendingDiagnostic {
    val factory: KtDiagnosticFactory1<String>
    val declaration: IrDeclaration

    data class Structured(
      override val factory: KtDiagnosticFactory1<String>,
      override val declaration: IrDeclaration,
      val diagnostic: MetroDiagnostic,
    ) : PendingDiagnostic
  }

  private val pendingDiagnostics = mutableListOf<PendingDiagnostic>()

  private fun collectDiagnostic(diagnostic: MetroDiagnostic, declaration: IrDeclaration) {
    hasErrors = true
    pendingDiagnostics +=
      PendingDiagnostic.Structured(diagnostic.id.factory, declaration, diagnostic)
  }

  override fun report(diagnostic: MetroDiagnostic, stack: IrBindingStack) {
    val element =
      stack.lastEntryOrGraph?.originalDeclarationIfOverride()
        ?: node.reportableSourceGraphDeclaration
    hasErrors = true
    if (element is IrDeclaration) {
      // For missing bindings the anchor is the interesting injection/request site, so carry its
      // source span so rich console mode draws a frame for it. Other codes anchor at the graph
      // declaration, where a frame adds noise rather than signal.
      val enriched =
        if (diagnostic.primarySpan == null && diagnostic.id == MetroDiagnosticId.MISSING_BINDING) {
          diagnostic.copy(primarySpan = element.toDiagnosticSpan())
        } else {
          diagnostic
        }
      pendingDiagnostics += PendingDiagnostic.Structured(enriched.id.factory, element, enriched)
    } else {
      // Rare non-declaration anchor. Report immediately, since there are no batch passes to share
      // with.
      val prepared = DiagnosticBatch.prepare(listOf(diagnostic)).single()
      with(metroContext) {
        diagnosticReporter.reportAt(
          element,
          node.metroGraphOrFail.file,
          diagnostic.id.factory,
          diagnosticRenderer.render(prepared.diagnostic, prepared.renderContext).padForConsole(),
        )
      }
    }
  }

  override fun reportFatal(diagnostic: MetroDiagnostic, stack: IrBindingStack): Nothing {
    report(diagnostic, stack)
    flush()
    exitProcessing()
  }

  /**
   * Flushes collected diagnostics, grouping by (factory, declaration) to batch multiple messages
   * targeting the same diagnostic slot into a single report. This avoids kotlinc's diagnostic
   * deduplication which drops subsequent reports with the same factory on the same source element.
   *
   * Structured diagnostics first run through [DiagnosticBatch] passes (type-name disambiguation,
   * trace dedup) before rendering with the context's configured console mode.
   */
  override fun flush() {
    if (pendingDiagnostics.isEmpty()) return
    val structured = pendingDiagnostics.filterIsInstance<PendingDiagnostic.Structured>()
    val prepared = DiagnosticBatch.prepare(structured.map { it.diagnostic })
    val renderedStructured =
      structured
        .zip(prepared)
        .associateBy(
          keySelector = { (pending, _) -> pending },
          valueTransform = { (_, prep) ->
            metroContext.diagnosticRenderer.render(prep.diagnostic, prep.renderContext)
          },
        )

    pendingDiagnostics
      .groupBy { it.factory to it.declaration }
      .forEach { (key, diagnostics) ->
        val (factory, declaration) = key
        val rendered = diagnostics.map { pending ->
          when (pending) {
            is PendingDiagnostic.Structured -> renderedStructured.getValue(pending)
          }
        }
        // Stable sort so output is deterministic across kotlin versions
        val combinedMessage = rendered.sorted().joinToString(separator = "\n\n")
        metroContext.reportCompat(declaration, factory, combinedMessage.padForConsole())
      }
    pendingDiagnostics.clear()
  }

  private var _bindingLookup: BindingLookup? = bindingLookup
    set(value) {
      if (value != null) {
        field = value
        return
      }
      synchronized(parentSuspendResolutionLock) {
        field?.clear()
        parentSuspendBindingAnalysis = null
        field = null
      }
    }

  private val bindingLookup
    get() =
      _bindingLookup ?: reportCompilerBug("Tried to access bindingLookup after it's been cleared!")

  private val realGraph =
    MutableBindingGraph<_, _, _, IrBinding, _, _>(
      newBindingStack = newBindingStack,
      newBindingStackEntry = { contextKey, callingBinding, roots ->
        if (callingBinding == null) {
          roots.getValue(contextKey)
        } else {
          bindingStackEntryForDependency(callingBinding, contextKey, contextKey.typeKey)
        }
      },
      computeBindings = { contextKey, currentBindings, stack ->
        this.bindingLookup.lookup(contextKey, currentBindings, stack) { key, bindings ->
          reportDuplicateBindings(key, bindings, stack)
        }
      },
      errorReporter = this,
      missingBindingHints = ::missingBindingHints,
      findSuspendCycleKey = ::findSuspendCycleKey,
    )

  private fun findSuspendCycleKey(
    cycleKeys: List<IrTypeKey>,
    bindings: ScatterMap<IrTypeKey, IrBinding>,
  ): IrTypeKey? {
    if (!metroContext.options.enableSuspendProviders) return null
    val suspendKeys = SuspendBindingAnalysis(bindings::get).analyze(cycleKeys)
    return cycleKeys.firstOrNull { it in suspendKeys }
  }

  // TODO hoist accessors up and visit in seal?
  private val accessors = mutableMapOf<IrContextualTypeKey, IrBindingStack.Entry>()
  private val injectors = mutableMapOf<IrContextualTypeKey, IrBindingStack.Entry>()
  private val extraKeeps = mutableMapOf<IrContextualTypeKey, IrBindingStack.Entry>()
  /**
   * Context keys that child graphs need from this parent. Used to ensure these bindings are
   * reachable during seal() and to inform BindingPropertyCollector about child usage.
   */
  private val reservedContextKeys = mutableSetOf<IrContextualTypeKey>()

  // Thin immutable view over the internal bindings
  fun bindingsSnapshot(): ScatterMap<IrTypeKey, IrBinding> = realGraph.bindings

  fun keeps(): Set<IrContextualTypeKey> = extraKeeps.keys

  fun addAccessor(key: IrContextualTypeKey, entry: IrBindingStack.Entry) {
    recordRuntimeCoroutinesUse(key)
    accessors[key] = entry
  }

  fun addInjector(key: IrContextualTypeKey, entry: IrBindingStack.Entry) {
    recordRuntimeCoroutinesUse(key)
    injectors[key] = entry
  }

  fun addBinding(key: IrTypeKey, binding: IrBinding, bindingStack: IrBindingStack) {
    synchronized(parentSuspendResolutionLock) {
      val previousSize = realGraph.bindings.size
      realGraph.tryPut(binding, bindingStack, key)
      if (realGraph.bindings.size != previousSize) {
        graphGeneration++
      }
    }
  }

  fun keep(key: IrContextualTypeKey, entry: IrBindingStack.Entry) {
    recordRuntimeCoroutinesUse(key)
    extraKeeps[key] = entry
  }

  /**
   * Tracks a context key as requested by a child graph. This ensures the binding is kept during
   * seal() and informs BindingPropertyCollector about child usage.
   */
  fun reserveContextKey(contextKey: IrContextualTypeKey) {
    recordRuntimeCoroutinesUse(contextKey)
    reservedContextKeys.add(contextKey)
  }

  /** Returns all context keys reserved by child graphs. */
  fun reservedContextKeys(): Set<IrContextualTypeKey> = reservedContextKeys

  /** Checks if a specific context key is reserved by child graphs. */
  fun isContextKeyReserved(contextKey: IrContextualTypeKey): Boolean =
    contextKey in reservedContextKeys

  /**
   * Checks if any variant (provider or instance) of this type key is reserved by child graphs.
   * Provider variant is checked first since scoped bindings need Provider fields.
   */
  fun hasReservedKey(key: IrTypeKey): Boolean {
    // Check provider key first
    val providerType = metroContext.metroSymbols.metroProvider.typeWith(key.type)
    val providerKey =
      IrContextualTypeKey.create(key, isWrappedInProvider = true, rawType = providerType)
    if (providerKey in reservedContextKeys) return true

    // Check instance key
    // TODO cache these in metrocontext. Just IrTypekey -> IrContextualTypeKey
    val instanceKey = IrContextualTypeKey.create(key)
    return instanceKey in reservedContextKeys
  }

  fun findBinding(key: IrTypeKey, allowLookup: Boolean = false): IrBinding? =
    realGraph[key]
      ?: run {
        if (allowLookup) {
          bindingLookup
            .lookup(IrContextualTypeKey(key), realGraph.bindings, IrBindingStack.empty()) { _, _ ->
              // handled separately
            }
            .firstOrNull()
        } else {
          null
        }
      }

  // For bindings we expect to already be cached
  fun requireBinding(key: IrTypeKey): IrBinding {
    return requireBinding(IrContextualTypeKey.create(key))
  }

  fun requireBinding(contextKey: IrContextualTypeKey): IrBinding {
    return realGraph[contextKey.typeKey]
      ?: if (contextKey.hasDefault) {
        IrBinding.Absent(contextKey.typeKey)
      } else {
        reportCompilerBug("No expected binding found for key $contextKey")
      }
  }

  operator fun contains(key: IrTypeKey): Boolean = key in realGraph

  data class BindingGraphResult(
    val sortedKeys: List<IrTypeKey>,
    val deferredTypes: Set<IrTypeKey>,
    val reachableKeys: Set<IrTypeKey>,
    val shardGroups: List<List<IrTypeKey>>?,
    // Map of unused keys to graph inputs, if available
    val unusedKeys: Map<IrTypeKey, IrBinding.BoundInstance?>,
    val hasErrors: Boolean,
  ) {
    companion object {
      val ERROR =
        BindingGraphResult(
          sortedKeys = emptyList(),
          deferredTypes = emptySet(),
          reachableKeys = emptySet(),
          shardGroups = emptyList(),
          unusedKeys = emptyMap(),
          hasErrors = true,
        )
    }
  }

  data class GraphError(
    val declaration: IrDeclaration?,
    val message: String,
    val factory: KtDiagnosticFactory1<String> = MetroDiagnostics.METRO_ERROR,
  )

  context(traceScope: TraceScope)
  fun seal(
    childGraphScopes: List<ChildGraphScopeInfo> = emptyList(),
    onError: (List<GraphError>) -> Unit,
  ): BindingGraphResult {
    val topologyResult =
      trace("seal graph") {
        val roots = buildMap {
          putAll(accessors)
          putAll(injectors)
        }

        realGraph.seal(
          roots = roots,
          keep = extraKeeps,
          shrinkUnusedBindings = metroContext.options.shrinkUnusedBindings,
          onPopulated = {
            writeDiagnostic(
              "keys-populated",
              "${node.metroGraphOrFail.classIdOrFail.safePathString}.txt",
            ) {
              val keys = mutableListOf<IrTypeKey>()
              realGraph.bindings.forEachKey(keys::add)
              keys.sorted().joinToString("\n")
            }
          },
          onSortedCycle = { elementsInCycle ->
            writeDiagnostic(
              "cycle",
              "${node.metroGraphOrFail.classIdOrFail.safePathString}-${elementsInCycle[0].render(short = true, includeQualifier = false)}.txt",
            ) {
              elementsInCycle.plus(elementsInCycle[0]).joinToString("\n")
            }
          },
          validateBindings = ::validateBindings,
        )
      }

    val sortedKeys = topologyResult.sortedKeys
    val deferredTypes = topologyResult.deferredTypes
    val reachableKeys = topologyResult.reachableKeys

    validateRuntimeCoroutinesAvailability()

    if (hasErrors) {
      // Flush any collected errors before returning
      flush()
      // Clear out the binding lookup now that we're done
      _bindingLookup = null
      return BindingGraphResult.ERROR
    }

    writeDiagnostic("keys-validated", "${node.metroGraphOrFail.classIdOrFail.safePathString}.txt") {
      sortedKeys.joinToString(separator = "\n")
    }

    writeDiagnostic("keys-deferred", "${node.metroGraphOrFail.classIdOrFail.safePathString}.txt") {
      deferredTypes.joinToString(separator = "\n")
    }

    trace("check empty multibindings") { checkEmptyMultibindings(onError) }
    trace("check for absent bindings") {
      check(!realGraph.bindings.any { _, v -> v is IrBinding.Absent }) {
        "Found absent bindings in the binding graph: ${dumpGraph("Absent bindings", short = true)}"
      }
    }

    // Only report unused keys that were explicitly declared in this graph
    // Only relevant if shrinkUnusedBindings is enabled
    val unusedKeys: Map<IrTypeKey, IrBinding.BoundInstance?>
    if (options.shrinkUnusedBindings) {
      val declaredKeys = bindingLookup.getDeclaredKeys()
      val unused = declaredKeys - reachableKeys
      if (unused.isNotEmpty()) {
        val unusedMultibindingElements = unused.filterToSet {
          it.multibindingBindingElementId != null
        }
        if (unusedMultibindingElements.isNotEmpty()) {
          val allMultibindings by memoize {
            buildList {
              realGraph.bindings.forEachValue { b -> if (b is IrBinding.Multibinding) add(b) }
              addAll(bindingLookup.getAvailableMultibindings().values)
            }
              .distinctBy { it.typeKey }
          }
          val suspiciousDiagnostics = mutableListOf<MetroDiagnostic>()
          for ((key, binding) in bindingLookup.getAvailableMultibindings()) {
            if (binding.declaration != null) continue // Skip explicitly declared
            val unusedSources = binding.sourceBindings.intersect(unusedMultibindingElements)
            if (unusedSources.isEmpty()) continue

            // Report the first few bindings
            val examples =
              unusedSources
                .mapNotNull { source ->
                  val sourceBinding = bindingLookup[source] ?: return@mapNotNull null
                  if (sourceBinding is IrBinding.Provided) {
                    sourceBinding.renderContributionLocationDiagnostic(
                      short = true,
                      shortLocation = MetroOptions.SystemProperties.SHORTEN_LOCATIONS,
                    ) ?: sourceBinding.renderLocationDiagnostic(underlineTypeKey = false)
                  } else {
                    sourceBinding.renderLocationDiagnostic(underlineTypeKey = false)
                  }
                }
                // Stable sort
                .sortedBy { it.location }
            val locationItems = buildList {
              for (example in examples.take(MAX_SUSPICIOUS_UNUSED_MULTIBINDINGS_TO_REPORT)) {
                add(
                  example.toLocatedItem(
                    preferSourceSnippet = true,
                    includeLeadingAnnotations = false,
                  )
                )
              }
              if (unusedSources.size > MAX_SUSPICIOUS_UNUSED_MULTIBINDINGS_TO_REPORT) {
                val remaining = unusedSources.size - MAX_SUSPICIOUS_UNUSED_MULTIBINDINGS_TO_REPORT
                add(
                  LocatedItem(
                    location = null,
                    code = null,
                    description = textOf("...and $remaining more"),
                  )
                )
              }
            }

            val notes = buildList {
              add(
                Note.help(
                  "did you possibly bind them to the wrong type or contribute them to the wrong scope?"
                )
              )
              similarMultibindingsNote(binding, allMultibindings)?.let(::add)
              addAll(examples.flatMap { it.notes }.distinct())

              // Check if this multibinding type is used in any child graph extension
              val childScopesUsingThis =
                childGraphScopes.filter { key in it.reachableKeys }.flatMapToSet { it.scopeNames }
              if (childScopesUsingThis.isNotEmpty()) {
                add(
                  Note.note(
                    buildText {
                      append(key.toText())
                      append(" is used in the following child graph scope(s): ")
                      append(childScopesUsingThis.joinToString(", ") { it.asFqNameString() })
                      append(
                        ". These bindings may need to be contributed to one of those scopes instead."
                      )
                    }
                  )
                )
              }
            }

            suspiciousDiagnostics +=
              MetroDiagnostic(
                id = MetroDiagnosticId.SUSPICIOUS_UNUSED_MULTIBINDING,
                severity = MetroSeverity.WARNING,
                title =
                  buildText {
                    append("Synthetic multibinding ")
                    append(key.toText())
                    append(" is unused but has ${binding.sourceBindings.size} source binding(s)")
                  },
                sections =
                  listOf(DiagnosticSection.Locations(header = null, items = locationItems)),
                notes = notes,
              )
          }
          if (suspiciousDiagnostics.isNotEmpty()) {
            val combinedMessage =
              suspiciousDiagnostics.joinToString(separator = "\n\n") { render(it) }.padForConsole()
            reportCompat(
              node.sourceGraph,
              MetroDiagnostics.SUSPICIOUS_UNUSED_MULTIBINDING,
              combinedMessage,
            )
          }
        }
        writeDiagnostic(
          "keys-unused",
          "${node.metroGraphOrFail.classIdOrFail.safePathString}.txt",
        ) {
          unused.sorted().joinToString(separator = "\n")
        }
      }

      unusedKeys = unused.associateWith { key ->
        val binding = bindingLookup[key]
        if (binding is IrBinding.BoundInstance && binding.isGraphInput) {
          binding
        } else {
          null
        }
      }
    } else {
      unusedKeys = emptyMap()
    }

    val shardGroups =
      trace("compute shard groups") {
        val maxPerShard = metroContext.options.keysPerGraphShard
        val enableSharding = metroContext.options.enableGraphSharding
        if (enableSharding && topologyResult.adjacency.forward.size > maxPerShard) {
          topologyResult.partitionBySCCs(maxPerShard)
        } else {
          null
        }
      }

    // Flush any remaining collected errors
    flush()

    // Clear out the binding lookup now that we're done
    _bindingLookup = null

    return BindingGraphResult(
      sortedKeys = sortedKeys,
      deferredTypes = deferredTypes,
      reachableKeys = reachableKeys,
      shardGroups = shardGroups,
      unusedKeys = unusedKeys,
      hasErrors = hasErrors,
    )
  }

  fun reportDuplicateBindings(
    key: IrTypeKey,
    bindings: List<IrBinding>,
    bindingStack: IrBindingStack,
  ) {
    realGraph.reportDuplicateBindings(key, bindings, bindingStack)
  }

  private fun checkEmptyMultibindings(onError: (List<GraphError>) -> Unit) {
    val multibindings = buildList {
      realGraph.bindings.forEachValue { binding ->
        if (binding is IrBinding.Multibinding) {
          add(binding)
        }
      }
    }
    // Get all registered multibindings for similarity checking
    val allMultibindings by memoize {
      (multibindings + bindingLookup.getAvailableMultibindings().values).distinctBy { it.typeKey }
    }
    val errors = mutableListOf<GraphError>()
    for (multibinding in multibindings) {
      if (!multibinding.allowEmpty && multibinding.sourceBindings.isEmpty()) {
        val extraNotes = buildList {
          similarMultibindingsNote(multibinding, allMultibindings)?.let(::add)

          val elementType =
            if (multibinding.isMap) {
              multibinding.typeKey.requireMapValueType()
            } else {
              multibinding.typeKey.requireSetElementType()
            }
          functionProviderMigrationHint(elementType)?.let { add(Note.help(it)) }
        }
        val diagnostic = emptyMultibindingDiagnostic(multibinding.typeKey, extraNotes)
        val declarationToReport =
          if (multibinding.declaration?.isFakeOverride == true) {
            multibinding.declaration!!
              .overriddenSymbolsSequence()
              .firstOrNull { !it.owner.isFakeOverride }
              ?.owner
          } else {
            multibinding.declaration
          }
        errors +=
          GraphError(declarationToReport, render(diagnostic), MetroDiagnostics.EMPTY_MULTIBINDING)
      }
    }
    if (errors.isNotEmpty()) {
      onError(errors)
    }
  }

  private fun findSimilarMultibindings(
    multibinding: IrBinding.Multibinding,
    multibindings: List<IrBinding.Multibinding>,
  ): Sequence<IrTypeKey> = sequence {
    if (multibinding.isMap) {
      val keyType = multibinding.typeKey.requireMapKeyType()
      val valueType = multibinding.typeKey.requireMapValueType()
      val similarKeys =
        multibindings
          .filter { it.isMap && it != multibinding && it.typeKey.requireMapKeyType() == keyType }
          .map { it.typeKey }

      yieldAll(similarKeys)

      val similarValues =
        multibindings
          .filter {
            if (!it.isMap) return@filter false
            if (it == multibinding) return@filter false
            val otherValueType = it.typeKey.requireMapValueType()
            if (valueType == otherValueType) return@filter true
            if (valueType.isSubtypeOf(otherValueType, metroContext.irTypeSystemContext))
              return@filter true
            if (otherValueType.isSubtypeOf(valueType, metroContext.irTypeSystemContext))
              return@filter true
            false
          }
          .map { it.typeKey }

      yieldAll(similarValues)
    } else {
      // Set binding
      val elementType = multibinding.typeKey.requireSetElementType()

      val similar =
        multibindings
          .filter {
            if (!it.isSet) return@filter false
            if (it == multibinding) return@filter false
            val otherElementType = it.typeKey.requireSetElementType()
            if (elementType == otherElementType) return@filter true
            if (elementType.isSubtypeOf(otherElementType, metroContext.irTypeSystemContext))
              return@filter true
            if (otherElementType.isSubtypeOf(elementType, metroContext.irTypeSystemContext))
              return@filter true
            false
          }
          .map { it.typeKey }

      yieldAll(similar)
    }
  }

  /** Returns a note listing similar multibinding keys, or null if there are none. */
  private fun similarMultibindingsNote(
    multibinding: IrBinding.Multibinding,
    allMultibindings: List<IrBinding.Multibinding>,
  ): Note? {
    val similarKeys = findSimilarMultibindings(multibinding, allMultibindings).toList().distinct()
    if (similarKeys.isEmpty()) return null
    return Note.note(
      buildText {
        append("similar multibindings: ")
        similarKeys.forEachIndexed { index, key ->
          if (index > 0) append(", ")
          append(key.toText())
        }
      }
    )
  }

  // When function-provider mode is enabled, `() -> T` and `suspend () -> T` are treated as
  // (suspend) provider wrappers for `T` rather than bindable value types. An empty multibinding
  // or a missing binding for a `Function0<T>` / `SuspendFunction0<T>` key often means either
  // (a) contributors weren't migrated from `Provider<T>` / `SuspendProvider<T>`, or
  // (b) the author intended the function itself to be the bound value, which is no longer
  // supported at the top level under this mode. Returns null when the mode is off or the type
  // isn't a zero-arg (suspend) function.
  private fun functionProviderMigrationHint(type: IrType): String? {
    if (!metroContext.options.enableFunctionProviders) return null
    val rawClassId = type.rawTypeOrNull()?.classId
    val isSuspend = rawClassId == Symbols.ClassIds.suspendFunction0
    if (rawClassId != Symbols.ClassIds.function0 && !isSuspend) return null
    val targetType = type.requireSimpleType().arguments[0].typeOrFail.render(short = true)
    val suspendPrefix = if (isSuspend) "suspend " else ""
    val providerName = if (isSuspend) "SuspendProvider" else "Provider"
    return "`$suspendPrefix() -> $targetType` is treated as a provider for `$targetType` when `enableFunctionProviders` is enabled " +
      "(desugared: `$providerName<$targetType>`). As a result, Metro treats them as an intrinsic and unwraps them when " +
      "resolving bindings. If the binding itself was meant to be the literal `$suspendPrefix() -> $targetType` function type, you " +
      "need to migrate it to a named type. For example: " +
      "`fun interface SomeType : $suspendPrefix() -> $targetType`".trimIndent()
  }

  // For missing-binding diagnostics, [key] is the unwrapped inner type (e.g. `T` for a
  // `() -> T` or `suspend () -> T` request under function-provider mode). Scan the graph's
  // root contextual keys to recover whether any entry point was originally such a wrapper,
  // and if so emit the migration hint using the wrapper's raw type so `$targetType` renders
  // correctly.
  private fun functionProviderMigrationHintForMissing(key: IrTypeKey): String? {
    if (!metroContext.options.enableFunctionProviders) return null
    val functionRawType =
      sequenceOf(accessors, injectors, extraKeeps)
        .flatMap { it.keys.asSequence() }
        .firstOrNull { ctx ->
          val rawClassId = ctx.rawType?.rawTypeOrNull()?.classId
          ctx.typeKey == key &&
            with(metroContext.metroSymbols.classIds) { rawClassId.isFunction0Like }
        }
        ?.rawType ?: return null
    return functionProviderMigrationHint(functionRawType)
  }

  private fun missingBindingHints(key: IrTypeKey): MissingBindingHints {
    if (key.type.hasErrorTypes()) {
      return MissingBindingHints(
        notes =
          listOf(
            Note.note(
              "binding '${key.render(short = false, includeQualifier = true)}' is an error type and appears to be missing from the compile classpath"
            )
          )
      )
    }

    val notes = buildList {
      functionProviderMigrationHintForMissing(key)?.let { add(Note.help(it)) }

      if (key in bindingLookup.getParentGraphPrivateKeys()) {
        add(
          Note.note(
            "a binding for '${key.renderForDiagnostic(short = true)}' exists in a parent graph but is marked " +
              "@GraphPrivate and cannot be accessed from this graph"
          )
        )
      }
    }

    var locationNotes = emptyList<Note>()
    val sections = buildList {
      bindingLookup.skippedDirectMapRequests[key]?.let { requestedContextKey ->
        bindingLookup.getAvailableBindings()[key]?.let { directBinding ->
          // Use rawType to render with Provider/Lazy wrapping preserved, since
          // IrContextualTypeKey.render() delegates to WrappedType.Map.render() which
          // renders the canonical map type without value wrapping.
          // Short render here since we already have the full render mentioned earlier in the trace
          val requestedType =
            requestedContextKey.rawType?.render(short = true)
              ?: requestedContextKey.render(short = true, includeQualifier = false)
          val locationDiagnostic = directBinding.renderLocationDiagnostic(underlineTypeKey = true)
          locationNotes = locationDiagnostic.notes
          add(
            DiagnosticSection.Locations(
              header =
                textOf(
                  "A directly-provided '${key.render(short = true, includeQualifier = false)}' binding exists, " +
                    "but direct Map bindings cannot satisfy '$requestedType' requests. " +
                    "Provider-, Lazy-, or SuspendProvider-wrapped map values (e.g., Map<K, Provider<V>>) only work with a Map " +
                    "multibinding created with `@IntoMap` or `@Multibinds`."
                ),
              items = listOf(locationDiagnostic.toLocatedItem()),
            )
          )
        }
      }
    }

    return MissingBindingHints(
      notes = notes + locationNotes,
      sections = sections,
      similarBindings = findSimilarBindings(key).values.map { it.toItem() },
    )
  }

  private fun findSimilarBindings(key: IrTypeKey): Map<IrTypeKey, SimilarBinding> {
    // Use a map to avoid reporting duplicates
    val similarBindings = mutableMapOf<IrTypeKey, SimilarBinding>()

    // Error types we can't do anything with
    if (key.type.hasErrorTypes()) {
      return similarBindings
    }

    // Same type with different qualifier
    if (key.qualifier != null) {
      findBinding(key.copy(qualifier = null), allowLookup = true)?.let {
        similarBindings.putIfAbsent(
          it.typeKey,
          SimilarBinding(it.typeKey, it, "Different qualifier"),
        )
      }
    }

    // Check for nullable/non-nullable equivalent
    val isNullable = key.type.isMarkedNullable()
    val equivalentType =
      if (isNullable) {
        key.type.makeNotNull()
      } else {
        key.type.makeNullable()
      }
    val equivalentKey = key.copy(type = equivalentType)
    findBinding(equivalentKey, allowLookup = true)?.let { similarBinding ->
      val nullabilityDescription = buildString {
        if (isNullable) {
          append("Non-nullable equivalent")
        } else {
          append("Nullable equivalent")
        }

        if (isNullable && similarBinding is IrBinding.ConstructorInjected) {
          append(
            ". Constructor-injected classes cannot implicitly satisfy nullable versions. Explicitly bind this type with `@Binds` separately if you want to use it for nullable bindings"
          )
        }
      }
      similarBindings.putIfAbsent(
        similarBinding.typeKey,
        SimilarBinding(similarBinding.typeKey, similarBinding, nullabilityDescription),
      )
    }

    // Merge graph bindings and cached bindings from BindingLookup
    val allBindings = buildMap {
      realGraph.bindings.forEach { key, value -> put(key, value) }
      // Add cached bindings that aren't already in the graph
      for ((bindingKey, binding) in bindingLookup.getAvailableBindings()) {
        @Suppress("RETURN_VALUE_NOT_USED") putIfAbsent(bindingKey, binding)
      }
      // Add multibindings that have been registered
      for ((bindingKey, binding) in bindingLookup.getAvailableMultibindings()) {
        @Suppress("RETURN_VALUE_NOT_USED") putIfAbsent(bindingKey, binding)
      }
    }

    // Iterate through all bindings to find similar ones
    for ((bindingKey, binding) in allBindings) {
      if (bindingKey.type.hasErrorTypes()) {
        reportCompilerBug("Unexpected error type in all bindings")
      } else if (bindingKey.multibindingKeyData != null) {
        // Multibinding entry, don't look at this at all
        continue
      }

      when {
        bindingKey.type == key.type && key.qualifier != bindingKey.qualifier -> {
          @Suppress("RETURN_VALUE_NOT_USED")
          similarBindings.putIfAbsent(
            bindingKey,
            SimilarBinding(bindingKey, binding, "Different qualifier"),
          )
        }

        binding is IrBinding.Multibinding -> {
          val valueType =
            if (binding.isSet) {
              (bindingKey.type.type as IrSimpleType).arguments[0].typeOrFail
            } else {
              // Map binding
              (bindingKey.type.type as IrSimpleType).arguments[1].typeOrFail
            }
          if (valueType == key.type) {
            @Suppress("RETURN_VALUE_NOT_USED")
            similarBindings.putIfAbsent(
              bindingKey,
              SimilarBinding(bindingKey, binding, "Multibinding"),
            )
          }
        }

        bindingKey.type == key.type -> {
          // Already covered above but here to avoid falling through to the subtype checks
          // below as they would always return true for this
        }

        bindingKey.type.isSubtypeOf(key.type, metroContext.irTypeSystemContext) -> {
          @Suppress("RETURN_VALUE_NOT_USED")
          similarBindings.putIfAbsent(bindingKey, SimilarBinding(bindingKey, binding, "Subtype"))
        }

        !bindingKey.type.isAny() &&
          key.type.type.isSubtypeOf(bindingKey.type, metroContext.irTypeSystemContext) -> {
          @Suppress("RETURN_VALUE_NOT_USED")
          similarBindings.putIfAbsent(bindingKey, SimilarBinding(bindingKey, binding, "Supertype"))
        }
      }
    }

    // Does the class exist but is internal with a @Contributes annotation?
    key.type.rawTypeOrNull()?.let { klass ->
      // Ask contribution data if it's there but not visible
      val contributingClass =
        node.aggregationScopes
          .flatMap { scope ->
            contributionData.findVisibleContributionClassesForScopeInHints(
              scope,
              includeNonFriendInternals = true,
              callingDeclaration = node.sourceGraph,
            )
          }
          .find { contribution ->
            val implementsKey = contribution.implements(klass.classId!!)
            val bindsKey =
              contribution
                .annotationsIn(metroContext.metroSymbols.classIds.allContributesAnnotations)
                .any { annotation ->
                  val result = boundTypeResolver.resolveBoundType(contribution, annotation)
                  result == null || result.typeKey.type.rawTypeOrNull()?.classId == klass.classId
                }
            implementsKey && bindsKey
          }

      if (contributingClass != null) {
        @Suppress("RETURN_VALUE_NOT_USED")
        similarBindings.putIfAbsent(
          key,
          SimilarBinding(
            typeKey = key,
            binding = null,
            description =
              "Contributed by '${contributingClass.kotlinFqName.asString()}' but that class is internal to its module and its module is not a friend module to this one.",
          ),
        )
      }
    }

    return similarBindings.filterNot {
      (it.value.binding as? IrBinding.BindingWithAnnotations)?.annotations?.isIntoMultibinding ==
        true
    }
  }

  // TODO iterate on this more!
  internal fun dumpGraph(name: String, short: Boolean): String {
    if (realGraph.bindings.isEmpty()) return "Empty binding graph"

    return buildString {
      append("Binding Graph: ")
      appendLine(name)
      // Sort by type key for consistent output
      realGraph.bindings
        .asMap()
        .entries
        .sortedBy { it.key.toString() }
        .forEach { (_, binding) ->
          appendLine("─".repeat(50))
          appendBinding(binding, short, isNested = false)
        }
    }
  }

  /**
   * We always want to report the original declaration for overridable nodes, as fake overrides
   * won't necessarily have source that is reportable.
   */
  @Suppress("UNCHECKED_CAST")
  private fun <T : IrDeclaration> T.originalDeclarationIfOverride(): T {
    return when (this) {
      is IrValueParameter -> {
        val index = indexInParameters
        // Need to check if the parent is a fakeOverride function or property setter
        val parent = parent.expectAs<IrFunction>()
        val originalParent = parent.originalDeclarationIfOverride()
        originalParent.parameters[index] as T
      }
      is IrSimpleFunction if isFakeOverride -> {
        overriddenSymbolsSequence().last().owner as T
      }
      is IrProperty if isFakeOverride -> {
        overriddenSymbolsSequence().last().owner as T
      }
      else -> this
    }
  }

  private fun validateBindings(
    bindings: ScatterMap<IrTypeKey, IrBinding>,
    stack: IrBindingStack,
    roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
    adjacency: GraphAdjacency<IrTypeKey>,
  ) {
    val rootsByTypeKey = roots.mapKeys { it.key.typeKey }
    var hasDirectSuspendBinding = false
    var hasDisabledSuspendProviderUse = false
    bindings.forEachValue { binding ->
      if (metroContext.options.enableSuspendProviders) {
        if (binding.isSuspend) {
          hasDirectSuspendBinding = true
        }
      } else {
        // Injected functions use provider-shaped constructor parameters as generated storage. Their
        // source parameters are checked by containsSuspendWrapperUse() below.
        val hasSyntheticInjectedFunctionDependencies =
          binding is IrBinding.ConstructorInjected && binding.type.injectedFunctionOrNull() != null
        val bindingUsesSuspendWrapper =
          binding.containsSuspendWrapperUse() || binding.contextualTypeKey.containsSuspendWrapper()
        val dependencyUsesSuspendWrapper =
          !hasSyntheticInjectedFunctionDependencies &&
            binding.dependencies.any { it.containsSuspendWrapper() }
        val usesSuspendProvider =
          binding.isSuspend || bindingUsesSuspendWrapper || dependencyUsesSuspendWrapper
        if (usesSuspendProvider) {
          hasDisabledSuspendProviderUse = true
        }
      }
      // Once one binding requires runtime-coroutines the answer can't change, so skip the
      // per-binding SuspendLazy scan (injectedFunctionUsesSuspendLazy does symbol lookups).
      if (
        !requiresRuntimeCoroutines &&
          metroContext.options.enableSuspendProviders &&
          binding.typeKey in adjacency.forward
      ) {
        val hasSuspendLazyDependency =
          binding.contextualTypeKey.wrappedType.containsSuspendLazy() ||
            binding.dependencies.any { it.wrappedType.containsSuspendLazy() } ||
            binding.injectedFunctionUsesSuspendLazy() ||
            (binding is IrBinding.AssistedFactory &&
              binding.targetBinding.dependencies.any { it.wrappedType.containsSuspendLazy() })
        if (hasSuspendLazyDependency) {
          requiresRuntimeCoroutines = true
        }
      }
      checkScope(binding, stack, roots, adjacency.forward)
      validateMultibindings(binding, bindings, roots, adjacency.forward)
      validateAssistedInjection(binding, bindings, rootsByTypeKey, adjacency.reverse)
    }
    if (!metroContext.options.enableSuspendProviders) {
      for (accessor in node.accessors) {
        val isSuspendAccessor = accessor.metroFunction.ir.isSuspend
        if (!isSuspendAccessor && !accessor.contextKey.containsSuspendWrapper()) continue
        hasDisabledSuspendProviderUse = true
      }
      if (hasDisabledSuspendProviderUse) reportSuspendProvidersNotEnabled()
    }
    if (metroContext.options.enableSuspendProviders && hasDirectSuspendBinding) {
      validateSuspendBindings(bindings, roots, adjacency)
    }
  }

  private fun reportSuspendProvidersNotEnabled() {
    // FIR handles declarations in this source module. Uses discovered here generally come from
    // binary dependency metadata, so report the consuming graph where the option can be enabled
    // rather than a source-less upstream parameter.
    reportError(
      node.sourceGraph,
      "[${MetroDiagnosticId.SUSPEND_PROVIDERS_NOT_ENABLED.fullId}] $SUSPEND_PROVIDERS_NOT_ENABLED_MESSAGE",
      MetroDiagnosticId.SUSPEND_PROVIDERS_NOT_ENABLED.factory,
    )
  }

  /** Checks source parameter metadata that may have been compiled with a different option value. */
  private fun IrBinding.containsSuspendWrapperUse(): Boolean {
    if (this is IrBinding.ConstructorInjected) {
      val injectedFunction = type.injectedFunctionOrNull()?.owner
      if (injectedFunction != null) {
        return injectedFunction.parameters().nonDispatchParameters.any {
          !it.isAssisted && it.contextualTypeKey.containsSuspendWrapper()
        }
      }
    }
    if (parameters.allParameters.any { it.contextualTypeKey.containsSuspendWrapper() }) return true
    if (this is IrBinding.AssistedFactory) {
      val targetUsesSuspendWrapper =
        targetBinding.parameters.allParameters.any {
          it.contextualTypeKey.containsSuspendWrapper()
        }
      if (targetUsesSuspendWrapper) return true
    }
    return false
  }

  private fun IrContextualTypeKey.containsSuspendWrapper(): Boolean {
    return wrappedType.containsSuspendWrapper()
  }

  /**
   * Validates suspend binding propagation through the dependency graph.
   *
   * Suspension propagates from a binding provided by a `suspend fun` to bindings that depend on it,
   * unless the dependency is deferred behind `suspend () -> T` or `SuspendLazy<T>`.
   */
  private fun validateSuspendBindings(
    bindings: ScatterMap<IrTypeKey, IrBinding>,
    roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
    adjacency: GraphAdjacency<IrTypeKey>,
  ) {
    // Step 1: Compute the set of type keys that transitively require a suspend context.
    val allKeys = mutableSetOf<IrTypeKey>()
    bindings.forEachKey(allKeys::add)
    val suspendSet = SuspendBindingAnalysis(bindings::get).analyze(allKeys)

    // If no suspend bindings, nothing to validate
    if (suspendSet.isEmpty()) {
      transitivelySuspendKeys = emptySet()
      return
    }

    transitivelySuspendKeys = suspendSet

    // Step 2: Multibinding aggregates over suspend elements. Aggregation code (buildSet/buildMap
    // getters, Map*Factory invokes) is non-suspend and can't await element initialization, so the
    // supported consumption of a suspend multibinding is a deferred-value map form:
    // `Map<K, suspend () -> V>` or `Map<K, SuspendProvider<V>>`. Provider-valued set forms are
    // unsupported, as they are for ordinary Provider bindings.
    val suspendMultibindingKeys = mutableSetOf<IrTypeKey>()
    bindings.forEachValue { binding ->
      if (
        binding.typeKey in adjacency.forward && binding.isScoped() && binding.typeKey in suspendSet
      ) {
        requiresRuntimeCoroutines = true
      }
      if (binding !is IrBinding.Multibinding) return@forEachValue
      if (binding.typeKey !in suspendSet) return@forEachValue
      suspendMultibindingKeys += binding.typeKey

      val firstSuspendElement =
        binding.dependencies.firstOrNull { it.typeKey in suspendSet } ?: return@forEachValue

      fun reportUnsupportedConsumption(
        element: IrDeclarationWithName,
        head: IrBindingStack.Entry,
      ) {
        val typeRender = binding.typeKey.render(short = true)
        val trace = buildSuspendTrace(bindings, suspendSet, firstSuspendElement.typeKey, head)
        val message = buildString {
          appendLine(
            if (binding.isSet) {
              "[${MetroDiagnosticId.MULTIBINDING_OVER_SUSPEND_BINDINGS.fullId}] $typeRender aggregates suspend bindings, which is unsupported. Provider-valued set multibindings are not supported. Remove the suspend contribution(s) or provide them eagerly."
            } else {
              val (keyType, valueType) =
                binding.typeKey.type.requireSimpleType().arguments.map {
                  it.typeOrFail.render(short = true)
                }
              "[${MetroDiagnosticId.MULTIBINDING_OVER_SUSPEND_BINDINGS.fullId}] $typeRender aggregates suspend bindings and must be consumed as `Map<$keyType, suspend () -> $valueType>` so each value is initialized only when its provider is invoked."
            }
          )
          appendLine()
          appendLine("Trace:")
          appendBindingStackEntries(node.sourceGraph.kotlinFqName, trace)
        }
        reportError(
          element.originalDeclarationIfOverride(),
          message,
          MetroDiagnosticId.MULTIBINDING_OVER_SUSPEND_BINDINGS.factory,
        )
      }

      // Roots consuming this multibinding
      for ((contextKey, _) in roots) {
        if (contextKey.typeKey != binding.typeKey) continue
        if (!binding.isSet && contextKey.isMapSuspendProvider) continue
        val accessor =
          node.accessors.find { it.contextKey == contextKey }
            ?: node.accessors.find { it.contextKey.typeKey == contextKey.typeKey }
            ?: continue
        val head =
          IrBindingStack.Entry.requestedAt(contextKey, accessor.metroFunction.ir)
            .withAnnotation(NEEDS_SUSPEND_SUPPORT)
        reportUnsupportedConsumption(
          accessor.metroFunction.ir.propertyIfAccessor.expectAs<IrDeclarationWithName>(),
          head,
        )
      }

      // Dependency edges consuming this multibinding
      bindings.forEachValue inner@{ consumer ->
        if (consumer is IrBinding.AssistedFactory) return@inner
        for (dep in consumer.dependencies) {
          if (dep.typeKey != binding.typeKey) continue
          if (!binding.isSet && dep.isMapSuspendProvider) continue
          val parameterDeclaration =
            consumer.parameters.allParameters.find { it.typeKey == dep.typeKey }?.ir
          val element = parameterDeclaration ?: consumer.reportableDeclaration ?: continue
          val head =
            bindingStackEntryForDependency(consumer, dep, dep.typeKey)
              .withAnnotation(NEEDS_SUSPEND_SUPPORT)
          reportUnsupportedConsumption(element, head)
        }
      }
    }

    // Step 3: Validate accessors. Iterate the accessors themselves (not the deduped roots map)
    // so multiple accessors sharing a contextual key are each validated.
    for (accessor in node.accessors) {
      val contextKey = accessor.contextKey
      if (contextKey.typeKey !in suspendSet) continue
      val accessorIsSuspend = accessor.metroFunction.ir.isSuspend

      // Suspend multibindings are reported by step 2 with a more specific message
      if (contextKey.typeKey in suspendMultibindingKeys) continue

      // An included graph can expose the exact wrapper value synchronously. Returning that value
      // does not require flattening its wrapper stack to the canonical binding.
      if (contextKey.isValidSuspendBoundary(bindings)) continue

      // Resolve to the property (if it's a getter) so the diagnostic lands on the
      // property declaration, then walk fake overrides back to the original interface
      // declaration since the accessor IR is typically the implementation graph's override.
      val accessorDeclaration =
        accessor.metroFunction.ir.propertyIfAccessor.expectAs<IrDeclarationWithName>()

      val typeRender = contextKey.typeKey.render(short = true)
      val deferredFormRender = suspendFunctionRender(typeRender)

      // A synchronous wrapper nearest the bound value cannot await a suspend binding, regardless
      // of what outer wrappers surround it or whether the accessor itself is suspend.
      val blockingWrapper = contextKey.lowestSynchronousWrapperName()
      if (blockingWrapper != null) {
        val head =
          IrBindingStack.Entry.requestedAt(contextKey, accessor.metroFunction.ir)
            .withAnnotation(NEEDS_SUSPEND_SUPPORT)
        val trace = buildSuspendTrace(bindings, suspendSet, contextKey.typeKey, head)
        val replacement =
          if (blockingWrapper == "Provider") {
            "`$deferredFormRender`"
          } else {
            "`SuspendLazy<$typeRender>`"
          }
        val diagnosticId = MetroDiagnosticId.suspendBindingWrappedIn(blockingWrapper)
        val message = buildString {
          appendLine(
            "[${diagnosticId.fullId}] Cannot access suspend binding '$typeRender' ${contextKey.blockingWrapperPhrase(blockingWrapper)}. Use $replacement instead."
          )
          appendLine()
          appendLine("Trace:")
          appendBindingStackEntries(node.sourceGraph.kotlinFqName, trace)
        }
        reportError(
          accessorDeclaration.originalDeclarationIfOverride(),
          message,
          diagnosticId.factory,
        )
        continue
      }

      if (!accessorIsSuspend && !contextKey.isSuspendCapableBoundary) {
        val head =
          IrBindingStack.Entry.requestedAt(contextKey, accessor.metroFunction.ir)
            .withAnnotation(NEEDS_SUSPEND_SUPPORT)
        val trace = buildSuspendTrace(bindings, suspendSet, contextKey.typeKey, head)
        val message = buildString {
          appendLine(
            "[${MetroDiagnosticId.SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR.fullId}] $typeRender bindings must be a suspend function or $deferredFormRender because it depends on suspend bindings and requires a suspend context."
          )
          appendLine()
          appendLine("Trace:")
          appendBindingStackEntries(node.sourceGraph.kotlinFqName, trace)
          appendLine()
          appendLine("Either:")
          appendLine(
            "  - Mark this accessor as `suspend fun` so it can await suspend dependencies, or"
          )
          append("  - Make the return type `$deferredFormRender`.")
        }
        reportError(
          accessorDeclaration.originalDeclarationIfOverride(),
          message,
          MetroDiagnosticId.SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR.factory,
        )
      }
    }

    // Step 4: Suspend member injection is unsupported. MembersInjector.injectMembers and injector
    // functions cannot await suspend bindings, and graph-local suspend factories do not run
    // ordinary member injection after construction.
    bindings.forEachValue { binding ->
      if (binding.typeKey !in suspendSet) return@forEachValue
      val (firstSuspendDep, subject) =
        when (binding) {
          is IrBinding.MembersInjected -> {
            val dep =
              binding.dependencies.firstOrNull { dep ->
                dep.typeKey in suspendSet && !dep.stopsSuspendPropagation { key -> bindings[key] }
              } ?: return@forEachValue
            dep to "'${binding.targetClassId.asFqNameString()}' member injection"
          }
          is IrBinding.ConstructorInjected if binding.injectedMembers.isNotEmpty() -> {
            // A member-injecting class must not be suspend at all. Suspend construction routes
            // through nested suspend factories, which do not perform member injection. The
            // Suspend initialization may be required by a member or constructor dependency.
            val anySuspendDep =
              binding.dependencies.firstOrNull { dep ->
                dep.typeKey in suspendSet && !dep.stopsSuspendPropagation { key -> bindings[key] }
              } ?: return@forEachValue
            // The MembersInjected binding reports suspend member dependencies directly. Only the
            // constructor-specific case remains here: construction requires suspension, so its
            // otherwise synchronous member injection would be skipped.
            val memberBinding = bindings[anySuspendDep.typeKey] as? IrBinding.MembersInjected
            if (memberBinding != null) return@forEachValue
            anySuspendDep to "'${binding.type.kotlinFqName}' has @Inject members and"
          }
          else -> return@forEachValue
        }
      val depRender = firstSuspendDep.typeKey.render(short = true)
      val head =
        bindingStackEntryForDependency(binding, firstSuspendDep, firstSuspendDep.typeKey)
          .withAnnotation(NEEDS_SUSPEND_SUPPORT)
      val trace = buildSuspendTrace(bindings, suspendSet, firstSuspendDep.typeKey, head)
      val message = buildString {
        appendLine(
          "[${MetroDiagnosticId.MEMBER_INJECTION_OVER_SUSPEND_BINDING.fullId}] $subject depends on suspend binding '$depRender', but member injection cannot combine with suspend bindings. Defer the dependency as `${suspendFunctionRender(depRender)}` (or `SuspendLazy<$depRender>`) instead."
        )
        appendLine()
        appendLine("Trace:")
        appendBindingStackEntries(node.sourceGraph.kotlinFqName, trace)
      }
      val reportCandidates =
        when (binding) {
          is IrBinding.MembersInjected ->
            sequenceOf(
              binding.parameterFor(firstSuspendDep.typeKey)?.ir?.originalDeclarationIfOverride(),
              node.sourceGraph,
            )
          is IrBinding.ConstructorInjected ->
            binding.injectedMembers
              .asSequence()
              .mapNotNull { bindings[it.typeKey] as? IrBinding.MembersInjected }
              .flatMap { it.parameters.nonDispatchParameters.asSequence() }
              .filter { it.isMember }
              .mapNotNull { it.ir?.originalDeclarationIfOverride() }
              .plus(node.sourceGraph)
          else -> return@forEachValue
        }
      reportError(
        reportCandidates,
        message,
        MetroDiagnosticId.MEMBER_INJECTION_OVER_SUSPEND_BINDING.factory,
      )
    }

    // Step 5: Validate wrapper stacks whose innermost wrapper is Provider or Lazy over a suspend
    // binding.
    bindings.forEachValue { binding ->
      // Assisted factories' dependencies are synthetically Provider-wrapped for cycle detection;
      // their suspend handling is validated in step 6 below.
      if (binding is IrBinding.AssistedFactory) return@forEachValue
      for (dep in binding.dependencies) {
        if (dep.typeKey !in suspendSet) continue
        // Suspend multibindings are reported by step 2 with a more specific message
        if (dep.typeKey in suspendMultibindingKeys) continue
        if (dep.isValidSuspendBoundary(bindings)) continue

        // Prefer landing on the specific parameter that wraps the suspend binding rather
        // than the enclosing function/property declaration.
        val parameterDeclaration =
          binding.parameters.allParameters.firstOrNull { it.contextualTypeKey == dep }?.ir
            ?: binding.parameters.allParameters.find { it.typeKey == dep.typeKey }?.ir

        val blockingWrapper = dep.lowestSynchronousWrapperName()
        if (blockingWrapper != null) {
          val depRender = dep.typeKey.render(short = true)
          val replacement =
            if (blockingWrapper == "Provider") {
              "`${suspendFunctionRender(depRender)}`"
            } else {
              "`SuspendLazy<$depRender>`"
            }
          val diagnosticId = MetroDiagnosticId.suspendBindingWrappedIn(blockingWrapper)
          val message = buildString {
            appendLine(
              "[${diagnosticId.fullId}] Cannot depend on suspend binding '$depRender' ${dep.blockingWrapperPhrase(blockingWrapper)}. Use $replacement instead."
            )
            appendLine()
            appendLine("Trace:")
            val head =
              bindingStackEntryForDependency(binding, dep, dep.typeKey)
                .withAnnotation(NEEDS_SUSPEND_SUPPORT)
            val trace = buildSuspendTrace(bindings, suspendSet, dep.typeKey, head)
            appendBindingStackEntries(node.sourceGraph.kotlinFqName, trace)
          }
          val element = parameterDeclaration ?: binding.reportableDeclaration ?: continue
          reportError(element.originalDeclarationIfOverride(), message, diagnosticId.factory)
        }
      }
    }

    // Step 6: An assisted factory whose target consumes suspend bindings (unwrapped) must declare
    // its SAM as `suspend` so the generated impl can await them. Provider/Lazy-wrapped suspend
    // deps on the target are also validated here because the target binding is not in the graph
    // and step 4 never sees it.
    bindings.forEachValue { binding ->
      if (binding !is IrBinding.AssistedFactory) return@forEachValue
      val target = binding.targetBinding
      for (param in target.parameters.nonDispatchParameters) {
        if (param.isAssisted) continue
        val ctxKey = param.contextualTypeKey
        if (ctxKey.typeKey !in suspendSet) continue
        if (ctxKey.isValidSuspendBoundary(bindings)) continue
        val blockingWrapper = ctxKey.lowestSynchronousWrapperName() ?: continue
        val depRender = ctxKey.typeKey.render(short = true)
        val replacement =
          if (blockingWrapper == "Provider") {
            "`${suspendFunctionRender(depRender)}`"
          } else {
            "`SuspendLazy<$depRender>`"
          }
        val element = param.ir ?: target.reportableDeclaration
        val diagnosticId = MetroDiagnosticId.suspendBindingWrappedIn(blockingWrapper)
        val message = buildString {
          appendLine(
            "[${diagnosticId.fullId}] Cannot depend on suspend binding '$depRender' ${ctxKey.blockingWrapperPhrase(blockingWrapper)}. Use $replacement instead."
          )
          appendLine()
          appendLine("Trace:")
          val head =
            bindingStackEntryForDependency(target, ctxKey, ctxKey.typeKey)
              .withAnnotation(NEEDS_SUSPEND_SUPPORT)
          val trace = buildSuspendTrace(bindings, suspendSet, ctxKey.typeKey, head)
          appendBindingStackEntries(node.sourceGraph.kotlinFqName, trace)
        }
        reportError(
          element.originalDeclarationIfOverride(),
          message,
          diagnosticId.factory,
        )
      }

      if (binding.function.isSuspend) return@forEachValue
      val blockingDep =
        target.parameters.nonDispatchParameters.firstOrNull { param ->
          !param.isAssisted &&
            param.contextualTypeKey.propagatesSuspend(
              { it in suspendSet },
              { key -> bindings[key] },
            )
        } ?: return@forEachValue
      val head =
        bindingStackEntryForDependency(target, blockingDep.contextualTypeKey, blockingDep.typeKey)
          .withAnnotation(NEEDS_SUSPEND_SUPPORT)
      val trace = buildSuspendTrace(bindings, suspendSet, blockingDep.typeKey, head)
      val message = buildString {
        appendLine(
          "[${MetroDiagnosticId.ASSISTED_FACTORY_SUSPEND_REQUIRED.fullId}] '${binding.type.kotlinFqName}' creates '${target.type.kotlinFqName}', which depends on suspend bindings. Declare '${binding.function.name}' as a suspend function so it can await them."
        )
        appendLine()
        appendLine("Trace:")
        appendBindingStackEntries(node.sourceGraph.kotlinFqName, trace)
      }
      reportError(
        binding.function.originalDeclarationIfOverride(),
        message,
        MetroDiagnosticId.ASSISTED_FACTORY_SUSPEND_REQUIRED.factory,
      )
    }
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

  /**
   * Builds a list of [IrBindingStack.Entry] tracing dep edges from [start] to a directly-suspend
   * source binding. Each step uses [bindingStackEntryForDependency] (which produces an `injectedAt`
   * entry) and the trace ends with a `providedAt` entry naming the suspend function. The
   * caller-supplied [head] is placed at the top of the trace. Each non-suspend step is annotated
   * with [NEEDS_SUSPEND_SUPPORT] so the user can see exactly which declarations stand in the way.
   */
  private fun buildSuspendTrace(
    bindings: ScatterMap<IrTypeKey, IrBinding>,
    suspendSet: Set<IrTypeKey>,
    start: IrTypeKey,
    head: IrBindingStack.Entry,
  ): List<IrBindingStack.Entry> {
    val result = mutableListOf(head)
    val visited = mutableSetOf<IrTypeKey>()
    var currentKey: IrTypeKey? = start
    while (currentKey != null && visited.add(currentKey)) {
      val current = bindings[currentKey] ?: break
      if (current.isSuspend) {
        // For a suspend source, emit a `providedAt` entry naming the function and stop. No
        // annotation: the function is already suspend, it isn't blocking the chain.
        val fn = (current as? IrBinding.Provided)?.providerFactory?.function
        if (fn != null) {
          result += IrBindingStack.Entry.providedAt(current.contextualTypeKey, fn)
        }
        break
      }
      val nextDep =
        current.dependencies.firstOrNull { dep ->
          dep.typeKey in suspendSet && !dep.stopsSuspendPropagation { key -> bindings[key] }
        } ?: break
      result +=
        bindingStackEntryForDependency(current, nextDep, nextDep.typeKey)
          .withAnnotation(NEEDS_SUSPEND_SUPPORT)
      currentKey = nextDep.typeKey
    }
    return result
  }

  private companion object {
    private const val NEEDS_SUSPEND_SUPPORT = "❌ needs suspend support"
  }

  /** Returns the synchronous wrapper nearest the bound value, if there is one. */
  private fun IrContextualTypeKey.lowestSynchronousWrapperName(): String? {
    if (wrappedType.usesSuspendProvider() != false) return null
    return when (wrappedType.innermostWrapper()) {
      is WrappedType.Provider -> "Provider"
      is WrappedType.Lazy -> "Lazy"
      else -> reportCompilerBug("Expected an innermost synchronous wrapper for $this")
    }
  }

  private fun IrContextualTypeKey.blockingWrapperPhrase(wrapper: String): String {
    val hasOuterWrapper =
      when (val type = wrappedType) {
        is WrappedType.Canonical,
        is WrappedType.Map -> false
        is WrappedType.Provider ->
          type.innerType !is WrappedType.Canonical && type.innerType !is WrappedType.Map
        is WrappedType.SuspendProvider ->
          type.innerType !is WrappedType.Canonical && type.innerType !is WrappedType.Map
        is WrappedType.Lazy ->
          type.innerType !is WrappedType.Canonical && type.innerType !is WrappedType.Map
        is WrappedType.SuspendLazy ->
          type.innerType !is WrappedType.Canonical && type.innerType !is WrappedType.Map
      }
    return if (hasOuterWrapper) {
      "because the wrapper nearest it is $wrapper"
    } else {
      "via $wrapper"
    }
  }

  private fun IrContextualTypeKey.isValidSuspendBoundary(
    bindings: ScatterMap<IrTypeKey, IrBinding>
  ): Boolean {
    if (isSuspendCapableBoundary) return true
    return (bindings[typeKey] as? IrBinding.GraphDependency)?.canPassThrough(this) == true
  }

  private fun IrBinding.injectedFunctionUsesSuspendLazy(): Boolean {
    val injectedClass = (this as? IrBinding.ConstructorInjected)?.type ?: return false
    val injectedFunction = injectedClass.injectedFunctionOrNull()?.owner ?: return false
    return injectedFunction.parameters().nonDispatchParameters.any {
      !it.isAssisted && it.contextualTypeKey.wrappedType.containsSuspendLazy()
    }
  }

  private fun validateRuntimeCoroutinesAvailability() {
    if (!requiresRuntimeCoroutines) return
    if (coroutinesRuntimeAvailability.isAvailable) return
    val tag = "[${MetroDiagnosticId.MISSING_RUNTIME_COROUTINES.fullId}]"
    val trigger = describeRuntimeCoroutinesTrigger()
    val message =
      if (trigger == null) {
        "$tag $MISSING_RUNTIME_COROUTINES_MESSAGE"
      } else {
        "$tag $trigger $MISSING_RUNTIME_COROUTINES_MESSAGE"
      }
    reportError(node.sourceGraph, message, MetroDiagnosticId.MISSING_RUNTIME_COROUTINES.factory)
  }

  /**
   * Names why this graph needs the optional runtime-coroutines artifact so the report points at the
   * cause instead of only saying what to add. Prefers a scoped suspend binding, then a
   * `SuspendLazy` request. Candidate keys are chosen by their sorted rendered form so the message
   * is stable.
   */
  private fun describeRuntimeCoroutinesTrigger(): String? {
    val scopedSuspendKeys = mutableListOf<IrTypeKey>()
    val suspendLazyKeys = mutableListOf<IrTypeKey>()
    realGraph.bindings.forEachValue { binding ->
      if (binding.isScoped() && binding.typeKey in transitivelySuspendKeys) {
        scopedSuspendKeys += binding.typeKey
      }
      val requestsSuspendLazy =
        binding.contextualTypeKey.wrappedType.containsSuspendLazy() ||
          binding.dependencies.any { it.wrappedType.containsSuspendLazy() } ||
          binding.injectedFunctionUsesSuspendLazy()
      if (requestsSuspendLazy) {
        suspendLazyKeys += binding.typeKey
      }
    }
    for (accessor in node.accessors) {
      if (accessor.contextKey.wrappedType.containsSuspendLazy()) {
        suspendLazyKeys += accessor.contextKey.typeKey
      }
    }
    if (scopedSuspendKeys.isNotEmpty()) {
      val key = scopedSuspendKeys.map { it.render(short = true) }.sorted().first()
      return "The scoped suspend binding `$key` caches its awaited value, which needs the optional runtime-coroutines artifact."
    }
    if (suspendLazyKeys.isNotEmpty()) {
      val key = suspendLazyKeys.map { it.render(short = true) }.sorted().first()
      return "`$key` requests a `SuspendLazy` value, which needs the optional runtime-coroutines artifact."
    }
    return null
  }

  private fun reportError(
    element: IrDeclarationWithName,
    message: String,
    factory: KtDiagnosticFactory1<String>,
  ) {
    hasErrors = true
    metroContext.reportCompat(element, factory, message.trimEnd())
  }

  private fun reportError(
    candidates: Sequence<IrDeclaration?>,
    message: String,
    factory: KtDiagnosticFactory1<String>,
  ) {
    hasErrors = true
    metroContext.reportCompat(candidates, factory, message.trimEnd())
  }

  // Check scoping compatibility
  private fun checkScope(
    binding: IrBinding,
    stack: IrBindingStack,
    roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
    adjacency: Map<IrTypeKey, Set<IrTypeKey>>,
  ) {
    val bindingScope = binding.scope ?: return
    // Our binding doesn't have a scope... so we don't care about scopes
    // Our binding does have a scope... and it's compatible with our node yay
    if (bindingScope in node.scopes) return

    // Error if there are mismatched scopes

    // Does bindingScope have the same toString? Annoying! Let's disambiguate
    val bindingScopeStr =
      "$bindingScope".takeIf { it !in node.scopes.map { "$it" } }
        ?: bindingScope.render(short = false)

    val nodeScopesStrs =
      node.scopes.map { "$it".takeIf { it != "$bindingScope" } ?: it.render(short = false) }

    val isUnscoped = node.scopes.isEmpty()
    val declarationToReport = node.sourceGraph.sourceGraphIfMetroGraph
    val stack = buildStackToRoot(binding.typeKey, roots, adjacency, stack)
    stack.push(
      IrBindingStack.Entry.simpleTypeRef(
        binding.contextualTypeKey,
        usage = "(scoped to '$bindingScopeStr')",
      )
    )
    val title = buildText {
      append(node.sourceGraph.kotlinFqName.asString(), Style.EMPHASIS)
      if (isUnscoped) {
        // Unscoped graph but scoped binding
        append(" (unscoped) may not reference scoped bindings")
      } else {
        // Scope mismatch
        append(
          " (scopes ${nodeScopesStrs.joinToString { "'$it'" }}) may not reference bindings from different scopes"
        )
      }
    }

    val notes = buildList {
      if (node.sourceGraph.origin == Origins.GeneratedGraphExtension) {
        val sourceGraphFqName = node.sourceGraph.sourceGraphIfMetroGraph.kotlinFqName
        val receivingGraphFqName =
          // Find the actual parent/receiving graph
          node.parentGraph?.sourceGraph?.sourceGraphIfMetroGraph?.kotlinFqName
            ?: declarationToReport.sourceGraphIfMetroGraph.kotlinFqName

        // Only show the hint if the source and receiving graphs are actually different
        if (sourceGraphFqName != receivingGraphFqName) {
          add(
            Note.note(
              "${node.sourceGraph.name} is contributed by '${sourceGraphFqName}' to '${receivingGraphFqName}'"
            )
          )
        }
      }
    }

    val diagnostic =
      MetroDiagnostic(
        id = MetroDiagnosticId.INCOMPATIBLY_SCOPED_BINDINGS,
        severity = MetroSeverity.ERROR,
        title = title,
        sections = listOfNotNull(stack.toTraceSection()),
        notes = notes,
      )
    collectDiagnostic(diagnostic, declarationToReport)
  }

  private fun buildStackToRoot(
    typeKey: IrTypeKey,
    roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
    adjacency: Map<IrTypeKey, Set<IrTypeKey>>,
    stack: IrBindingStack = newBindingStack(),
  ): IrBindingStack {
    val backTrace = buildRouteToRoot(typeKey, roots, adjacency)
    for (entry in backTrace) {
      stack.push(entry)
    }
    return stack
  }

  private fun validateMultibindings(
    binding: IrBinding,
    bindings: ScatterMap<IrTypeKey, IrBinding>,
    roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
    adjacency: Map<IrTypeKey, Set<IrTypeKey>>,
  ) {
    if (binding !is IrBinding.Multibinding) return
    if (!binding.isMap) return
    val keysWithDupes =
      binding.sourceBindings
        .mapNotNull { bindings[it] }
        .filterIsInstance<IrBinding.BindingWithAnnotations>()
        .groupBy { it.annotations.mapKey }
        .filterValues { it.size > 1 }

    for ((mapKey, dupes) in keysWithDupes) {
      if (mapKey == null) {
        reportCompilerBug("Map key should not be null for map multibindings")
      }

      val stack = buildStackToRoot(binding.typeKey, roots, adjacency)

      val locationDiagnostics = dupes.map { dupe ->
        dupe.renderLocationDiagnostic(
          shortLocation = MetroOptions.SystemProperties.SHORTEN_LOCATIONS,
          underlineTypeKey = false,
        )
      }
      val locationItems = locationDiagnostics.map { it.toLocatedItem() }

      val diagnostic =
        duplicateMapKeysDiagnostic(
          typeKey = binding.typeKey,
          mapKeyRender = mapKey.render(short = false),
          locations = locationItems,
          trace = stack.toTraceSection(),
          extraNotes = locationDiagnostics.flatMap { it.notes }.distinct(),
        )
      report(diagnostic, stack)
    }
  }

  // TODO can this check move to FIR injection sites?
  private fun validateAssistedInjection(
    binding: IrBinding,
    bindings: ScatterMap<IrTypeKey, IrBinding>,
    roots: Map<IrTypeKey, IrBindingStack.Entry>,
    reverseAdjacency: Map<IrTypeKey, Set<IrTypeKey>>,
  ) {
    if (binding !is IrBinding.ConstructorInjected || !binding.isAssisted) return

    fun reportInvalidBinding(declaration: IrDeclarationWithName?) {
      // Look up the assisted factory as a hint
      val assistedFactory =
        bindings
          .asMap()
          .values
          .find { it is IrBinding.AssistedFactory && it.targetBinding.typeKey == binding.typeKey }
          ?.typeKey
          // Check in the class itself for @AssistedFactory
          ?: binding.typeKey.type.rawTypeOrNull()?.let { rawType ->
            rawType.nestedClasses
              .firstOrNull { nestedClass ->
                nestedClass.isAnnotatedWithAny(
                  metroContext.metroSymbols.classIds.assistedFactoryAnnotations
                )
              }
              ?.let { IrTypeKey(it.defaultType) }
          }
      // Report an error for anything that isn't an assisted binding depending on this
      val notes = buildList {
        add(Note.help("inject a corresponding @AssistedFactory type instead"))
        if (assistedFactory != null) {
          add(
            Note.note(
              buildText {
                append("it looks like the @AssistedFactory for ")
                append(binding.typeKey.toText())
                append(" is ")
                append(assistedFactory.toText())
              }
            )
          )
        }
      }
      val diagnostic =
        MetroDiagnostic(
          id = MetroDiagnosticId.INVALID_BINDING,
          severity = MetroSeverity.ERROR,
          title =
            buildText {
              append(binding.typeKey.toText())
              append(" uses assisted injection and cannot be injected directly into ")
              append("${declaration?.fqNameWhenAvailable}", Style.EMPHASIS)
            },
          notes = notes,
        )
      collectDiagnostic(diagnostic, declaration ?: node.sourceGraph)
    }

    reverseAdjacency[binding.typeKey]?.let { dependents ->
      for (dependentKey in dependents) {
        val dependentBinding = bindings[dependentKey] ?: continue
        if (dependentBinding !is IrBinding.AssistedFactory) {
          reportInvalidBinding(
            dependentBinding.parameters.allParameters
              .find { it.typeKey == binding.typeKey }
              ?.ir
              ?.takeIf {
                val location = it.locationOrNull() ?: return@takeIf false
                location.line != 0 || location.column != 0
              } ?: dependentBinding.reportableDeclaration
          )
        }
      }
    }
    roots[binding.typeKey]?.let { reportInvalidBinding(it.declaration) }
  }

  /**
   * Builds a route from this binding back to one of the root bindings. Useful for error messaging
   * to show a trace back to an entry point.
   */
  private fun buildRouteToRoot(
    key: IrTypeKey,
    roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
    adjacency: Map<IrTypeKey, Set<IrTypeKey>>,
  ): List<IrBindingStack.Entry> {
    // No need to walk through the tree without roots
    if (roots.isEmpty()) return emptyList()

    // Build who depends on what
    val dependents = mutableMapOf<IrTypeKey, MutableSet<IrTypeKey>>()
    for ((key, deps) in adjacency) {
      for (dep in deps) {
        dependents.getAndAdd(dep, key)
      }
    }

    // Walk backwards from this binding to find a root
    val visited = mutableSetOf<IrTypeKey>()

    fun walkToRoot(current: IrTypeKey, path: List<IrTypeKey>): List<IrTypeKey>? {
      if (current in visited) return null // Cycle

      // Is this a root?
      if (roots.any { it.key.typeKey == current }) {
        return path + current
      }

      visited.add(current)

      // Try walking through each dependent
      for (dependent in dependents[current].orEmpty()) {
        walkToRoot(dependent, path + current)?.let {
          return it
        }
      }

      visited.remove(current)
      return null
    }

    val path = walkToRoot(key, emptyList()) ?: return emptyList()

    // Convert to stack entries - just create a simple stack and build it up
    val result = mutableListOf<IrBindingStack.Entry>()

    for (i in path.indices.reversed()) {
      val typeKey = path[i]

      if (i == path.lastIndex) {
        // This is the root
        val rootEntry = roots.entries.first { it.key.typeKey == typeKey }.value
        result.add(0, rootEntry)
      } else {
        // Create an entry for this step
        val callingBinding = realGraph.bindings.getValue(path[i + 1])
        val contextKey = callingBinding.dependencies.first { it.typeKey == typeKey }
        val entry = bindingStackEntryForDependency(callingBinding, contextKey, contextKey.typeKey)
        result.add(0, entry)
      }
    }

    // Reverse the route as these will push onto the top of the stack
    return result.asReversed()
  }

  private fun Appendable.appendBinding(binding: IrBinding, short: Boolean, isNested: Boolean) {
    appendLine("Type: ${binding.typeKey.renderForDiagnostic(short)}")
    appendLine("├─ Binding: ${binding::class.simpleName}")
    appendLine("├─ Contextual Type: ${binding.contextualTypeKey.render(short)}")

    binding.scope?.let { scope -> appendLine("├─ Scope: $scope") }

    if (binding is IrBinding.Alias) {
      appendLine("├─ Aliased type: ${binding.aliasedType.renderForDiagnostic(short)}")
    }

    if (binding.parameters.allParameters.isNotEmpty()) {
      appendLine("├─ Dependencies:")
      binding.parameters.allParameters.forEach { param ->
        appendLine("│  ├─ ${param.typeKey.renderForDiagnostic(short)}")
        appendLine("│  │  └─ Parameter: ${param.name} (${param.contextualTypeKey.render(short)})")
      }
    }

    if (!isNested && binding is IrBinding.Multibinding && binding.sourceBindings.isNotEmpty()) {
      appendLine("├─ Source bindings:")
      binding.sourceBindings.forEach { sourceBindingKey ->
        val sourceBinding = requireBinding(sourceBindingKey)
        val nested = buildString { appendBinding(sourceBinding, short, isNested = true) }
        append("│  ├─ ")
        appendLine(nested.lines().first())
        appendLine(nested.lines().drop(1).joinToString("\n").prependIndent("│  │  "))
      }
    }

    binding.reportableDeclaration?.locationOrNull()?.let { location ->
      appendLine("└─ Location: ${location.render(short)}")
    }
  }

  data class SimilarBinding(
    val typeKey: IrTypeKey,
    val binding: IrBinding?,
    val description: String,
  ) {
    fun toItem(): SimilarBindingItem {
      val fullDescription = buildString {
        append(description)
        binding?.let {
          append(". Type: ")
          append(binding.diagnosticTypeName)
          if (binding is IrBinding.Provided) {
            binding.isFromGeneratedContributionImpl()?.let { origin ->
              append(
                ". This is a generated contribution provider for ${origin.asFqNameString()}. If that class is the " +
                  "binding you're looking for, annotate the contributing class with `@ExposeImplBinding` to expose" +
                  " its impl type to the graph"
              )
            }
          }
        }
      }
      return SimilarBindingItem(
        key = typeKey.toText(),
        description = fullDescription,
        location = binding?.reportableDeclaration?.renderSourceLocation(short = true),
      )
    }
  }
}

private fun IrBinding.Provided.isFromGeneratedContributionImpl(): ClassId? {
  // The contribution provider generator stamps the nested contribution interface's `@Origin`
  // with `context = "contribution_provider"`. Detecting via the annotation context (rather
  // than an IR `origin` marker) means this also works for precompiled library sources.
  val nestedContribClass = providerFactory.function.parentClassOrNull ?: return null
  val originAnno = nestedContribClass.getAnnotation(Symbols.ClassIds.metroOrigin.asSingleFqName())
  val originContext = originAnno?.originContextOrNull()
  val isContribProvider = originContext == Symbols.StringNames.CONTRIBUTION_PROVIDER_ORIGIN_CONTEXT
  return if (isContribProvider) {
    originAnno.originOrNull()
  } else {
    null
  }
}
