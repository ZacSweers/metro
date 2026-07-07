// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import androidx.collection.ScatterMap
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.diagnostics.DiagnosticBatch
import dev.zacsweers.metro.compiler.diagnostics.DiagnosticHeadlines
import dev.zacsweers.metro.compiler.diagnostics.DiagnosticSection
import dev.zacsweers.metro.compiler.diagnostics.LocatedItem
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.compiler.diagnostics.Note
import dev.zacsweers.metro.compiler.diagnostics.SimilarBindingItem
import dev.zacsweers.metro.compiler.diagnostics.Style
import dev.zacsweers.metro.compiler.diagnostics.buildText
import dev.zacsweers.metro.compiler.diagnostics.textOf
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.filterToSet
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.getValue
import dev.zacsweers.metro.compiler.graph.ErrorReporter
import dev.zacsweers.metro.compiler.graph.GraphAdjacency
import dev.zacsweers.metro.compiler.graph.MissingBindingHints
import dev.zacsweers.metro.compiler.graph.MutableBindingGraph
import dev.zacsweers.metro.compiler.graph.partitionBySCCs
import dev.zacsweers.metro.compiler.graph.toText
import dev.zacsweers.metro.compiler.graph.toTraceSection
import dev.zacsweers.metro.compiler.ir.IrBoundTypeResolver
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrContributionData
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.getAnnotation
import dev.zacsweers.metro.compiler.ir.hasErrorTypes
import dev.zacsweers.metro.compiler.ir.implements
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.locationOrNull
import dev.zacsweers.metro.compiler.ir.originContextOrNull
import dev.zacsweers.metro.compiler.ir.originOrNull
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.padForConsole
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

  /** Returns true if the binding for [typeKey] transitively requires a suspend context. */
  internal fun isTransitivelySuspend(typeKey: IrTypeKey): Boolean =
    typeKey in transitivelySuspendKeys

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
      // For missing bindings the anchor is the interesting injection/request site — carry its
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
      // Rare non-declaration anchor — report immediately (no batch passes to share with).
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
      if (value == null) {
        field?.clear()
      }
      field = value
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
    )

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
    accessors[key] = entry
  }

  fun addInjector(key: IrContextualTypeKey, entry: IrBindingStack.Entry) {
    injectors[key] = entry
  }

  fun addBinding(key: IrTypeKey, binding: IrBinding, bindingStack: IrBindingStack) {
    realGraph.tryPut(binding, bindingStack, key)
  }

  fun keep(key: IrContextualTypeKey, entry: IrBindingStack.Entry) {
    extraKeeps[key] = entry
  }

  /**
   * Tracks a context key as requested by a child graph. This ensures the binding is kept during
   * seal() and informs BindingPropertyCollector about child usage.
   */
  fun reserveContextKey(contextKey: IrContextualTypeKey) {
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
                  LocatedItem(
                    location = example.location,
                    code = example.description,
                    preferSourceSnippet = true,
                    includeLeadingAnnotations = false,
                    span = example.span,
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
        val notes = buildList {
          add(
            Note.help(
              "annotate its declaration with `@Multibinds(allowEmpty = true)` if it can legitimately be empty"
            )
          )
          similarMultibindingsNote(multibinding, allMultibindings)?.let(::add)

          val elementType =
            if (multibinding.isMap) {
              multibinding.typeKey.requireMapValueType()
            } else {
              multibinding.typeKey.requireSetElementType()
            }
          functionProviderMigrationHint(elementType)?.let { add(Note.help(it)) }
        }
        val diagnostic =
          MetroDiagnostic(
            id = MetroDiagnosticId.EMPTY_MULTIBINDING,
            severity = MetroSeverity.ERROR,
            title =
              buildText {
                append("Multibinding ")
                append(multibinding.typeKey.toText())
                append(" was unexpectedly empty")
              },
            notes = notes,
          )
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
          add(
            DiagnosticSection.Locations(
              header =
                textOf(
                  "A directly-provided '${key.render(short = true, includeQualifier = false)}' binding exists, " +
                    "but direct Map bindings cannot satisfy '$requestedType' requests. " +
                    "Provider/Lazy-wrapped map values (e.g., Map<K, Provider<V>>) only work with a Map " +
                    "multibinding created with `@IntoMap` or `@Multibinds`."
                ),
              items =
                listOf(
                  LocatedItem(
                    location = locationDiagnostic.location,
                    code = locationDiagnostic.description,
                    span = locationDiagnostic.span,
                  )
                ),
            )
          )
        }
      }
    }

    return MissingBindingHints(
      notes = notes,
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
    bindings.forEachValue { binding ->
      checkScope(binding, stack, roots, adjacency.forward)
      validateMultibindings(binding, bindings, roots, adjacency.forward)
      validateAssistedInjection(binding, bindings, rootsByTypeKey, adjacency.reverse)
    }
    validateSuspendBindings(bindings, roots, adjacency)
  }

  /**
   * Validates suspend binding propagation through the dependency graph.
   *
   * Suspension is contagious: if binding B is provided by a `suspend fun`, then any binding A that
   * depends on B also requires a suspend context, unless A defers B behind a suspend wrapper like
   * `suspend () -> B` or `SuspendLazy<B>`.
   */
  private fun validateSuspendBindings(
    bindings: ScatterMap<IrTypeKey, IrBinding>,
    roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
    adjacency: GraphAdjacency<IrTypeKey>,
  ) {
    // Step 1: Compute the set of type keys that transitively require a suspend context
    val suspendSet = mutableSetOf<IrTypeKey>()

    // Seed with directly suspend bindings
    bindings.forEachValue { binding ->
      if (binding.isSuspend) {
        suspendSet.add(binding.typeKey)
      }
    }

    // If no suspend bindings, nothing to validate
    if (suspendSet.isEmpty()) {
      transitivelySuspendKeys = emptySet()
      return
    }

    // Propagate suspend requirement through the dependency graph
    // Walk the reverse adjacency: for each binding that depends on a suspend binding,
    // if the dependency is NOT wrapped in SuspendProvider, the binding also requires suspend
    var changed = true
    while (changed) {
      changed = false
      bindings.forEachValue { binding ->
        if (binding.typeKey in suspendSet) return@forEachValue // Already marked
        // Assisted factories defer all suspension into their SAM invocation. Holding/creating
        // the factory itself never suspends, so target dep suspend-ness doesn't propagate to the
        // factory binding. Validated separately below.
        if (binding is IrBinding.AssistedFactory) return@forEachValue

        for (dep in binding.dependencies) {
          if (
            dep.typeKey in suspendSet &&
              !dep.isWrappedInSuspendProvider &&
              !dep.isWrappedInSuspendLazy &&
              // Deferred-value maps (Map<K, suspend () -> V>) resolve synchronously; each value
              // defers its own suspension
              !dep.isMapSuspendProvider
          ) {
            suspendSet.add(binding.typeKey)
            changed = true
            break
          }
        }
      }
    }

    transitivelySuspendKeys = suspendSet.toSet()

    // Step 2: Multibinding aggregates over suspend elements. Aggregation code (buildSet/buildMap
    // getters, Map*Factory invokes) is non-suspend and can't await element resolutions, so the
    // only supported consumption of a suspend multibinding is the deferred-value map form
    // `Map<K, suspend () -> V>`. Sets have no deferred-value form and always error.
    val suspendMultibindingKeys = mutableSetOf<IrTypeKey>()
    bindings.forEachValue { binding ->
      if (binding !is IrBinding.Multibinding) return@forEachValue
      if (binding.typeKey !in suspendSet) return@forEachValue
      suspendMultibindingKeys += binding.typeKey

      val firstSuspendElement =
        binding.dependencies.firstOrNull { it.typeKey in suspendSet } ?: return@forEachValue

      fun reportUnsupportedConsumption(
        consumptionKey: IrContextualTypeKey,
        element: IrDeclarationWithName,
        head: IrBindingStack.Entry,
      ) {
        val typeRender = binding.typeKey.render(short = true)
        val trace = buildSuspendTrace(bindings, suspendSet, firstSuspendElement.typeKey, head)
        val message = buildString {
          appendLine(
            if (binding.isSet) {
              "[Metro/MultibindingOverSuspendBindings] $typeRender aggregates suspend bindings, which is not supported. Set aggregation cannot defer suspend element resolution. Remove the suspend contribution(s) or provide them eagerly."
            } else {
              val (keyType, valueType) =
                binding.typeKey.type.requireSimpleType().arguments.map {
                  it.typeOrFail.render(short = true)
                }
              "[Metro/MultibindingOverSuspendBindings] $typeRender aggregates suspend bindings and must be consumed as `Map<$keyType, suspend () -> $valueType>` so each value defers its resolution."
            }
          )
          appendLine()
          appendLine("Trace:")
          appendBindingStackEntries(node.sourceGraph.kotlinFqName, trace)
        }
        reportError(element.originalDeclarationIfOverride(), message)
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
          contextKey,
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
          val element =
            parameterDeclaration as? IrDeclarationWithName
              ?: consumer.reportableDeclaration
              ?: continue
          val head =
            bindingStackEntryForDependency(consumer, dep, dep.typeKey)
              .withAnnotation(NEEDS_SUSPEND_SUPPORT)
          reportUnsupportedConsumption(dep, element, head)
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

      // Resolve to the property (if it's a getter) so the diagnostic lands on the
      // property declaration, then walk fake overrides back to the original interface
      // declaration since the accessor IR is typically the implementation graph's override.
      val accessorDeclaration =
        accessor.metroFunction.ir.propertyIfAccessor.expectAs<IrDeclarationWithName>()

      val typeRender = contextKey.typeKey.render(short = true)
      val deferredFormRender = suspendFunctionRender(typeRender)

      // Provider<T>/Lazy<T> roots over a suspend binding can never await the work regardless of
      // the accessor's suspend-ness. Suspend-flavored wrappers are the fix.
      if (contextKey.isWrappedInProvider || contextKey.isWrappedInLazy) {
        val head =
          IrBindingStack.Entry.requestedAt(contextKey, accessor.metroFunction.ir)
            .withAnnotation(NEEDS_SUSPEND_SUPPORT)
        val trace = buildSuspendTrace(bindings, suspendSet, contextKey.typeKey, head)
        val wrapper = if (contextKey.isWrappedInProvider) "Provider" else "Lazy"
        val replacement =
          if (contextKey.isWrappedInProvider) {
            "`$deferredFormRender`"
          } else {
            "`SuspendLazy<$typeRender>`"
          }
        val message = buildString {
          appendLine(
            "[Metro/SuspendBindingWrappedIn$wrapper] Cannot access suspend binding '$typeRender' via $wrapper. Use $replacement instead."
          )
          appendLine()
          appendLine("Trace:")
          appendBindingStackEntries(node.sourceGraph.kotlinFqName, trace)
        }
        reportError(accessorDeclaration.originalDeclarationIfOverride(), message)
        continue
      }

      if (!accessorIsSuspend && !contextKey.defersSuspendAtAccess) {
        val head =
          IrBindingStack.Entry.requestedAt(contextKey, accessor.metroFunction.ir)
            .withAnnotation(NEEDS_SUSPEND_SUPPORT)
        val trace = buildSuspendTrace(bindings, suspendSet, contextKey.typeKey, head)
        val message = buildString {
          appendLine(
            "[Metro/SuspendBindingFromNonSuspendAccessor] $typeRender bindings must be a suspend function or $deferredFormRender because it depends on suspend bindings and requires a suspend context."
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
        reportError(accessorDeclaration.originalDeclarationIfOverride(), message)
      }
    }

    // Step 3b: Member injection has no suspend form. MembersInjector.injectMembers and injector
    // functions can't await suspend bindings, and constructor-injected classes with injected
    // members don't route through suspend factories. Error rather than silently mis-generate.
    bindings.forEachValue { binding ->
      if (binding.typeKey !in suspendSet) return@forEachValue
      val (firstSuspendDep, subject) =
        when (binding) {
          is IrBinding.MembersInjected -> {
            val dep =
              binding.dependencies.firstOrNull { dep ->
                dep.typeKey in suspendSet && !dep.defersSuspendAtAccess
              } ?: return@forEachValue
            dep to "'${binding.targetClassId.asFqNameString()}' member injection"
          }
          is IrBinding.ConstructorInjected if binding.injectedMembers.isNotEmpty() -> {
            // A member-injecting class must not be suspend at all. Suspend construction routes
            // through nested suspend factories, which do not perform member injection. The
            // suspend-ness may come from a member OR a constructor dependency.
            val anySuspendDep =
              binding.dependencies.firstOrNull { dep ->
                dep.typeKey in suspendSet && !dep.defersSuspendAtAccess
              } ?: return@forEachValue
            // Resolve through the MembersInjected binding to name the actual member's type
            // rather than the synthetic MembersInjector<T> key.
            val memberBinding = bindings[anySuspendDep.typeKey] as? IrBinding.MembersInjected
            val dep =
              memberBinding?.dependencies?.firstOrNull {
                it.typeKey in suspendSet && !it.defersSuspendAtAccess
              } ?: anySuspendDep
            dep to "'${binding.type.kotlinFqName}' has @Inject members and"
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
          "[Metro/MemberInjectionOverSuspendBinding] $subject depends on suspend binding '$depRender', but member injection cannot combine with suspend bindings. Defer the dependency as `${suspendFunctionRender(depRender)}` (or `SuspendLazy<$depRender>`) instead."
        )
        appendLine()
        appendLine("Trace:")
        appendBindingStackEntries(node.sourceGraph.kotlinFqName, trace)
      }
      val element =
        (binding as? IrBinding.MembersInjected)?.parameterFor(firstSuspendDep.typeKey)?.ir
          as? IrDeclarationWithName ?: binding.reportableDeclaration ?: return@forEachValue
      reportError(element.originalDeclarationIfOverride(), message)
    }

    // Step 4: Validate wrapping conflicts where Provider<T>/Lazy<T> wraps a suspend binding
    bindings.forEachValue { binding ->
      // Assisted factories' dependencies are synthetically Provider-wrapped for cycle detection;
      // their suspend handling is validated in step 5 below.
      if (binding is IrBinding.AssistedFactory) return@forEachValue
      for (dep in binding.dependencies) {
        if (dep.typeKey !in suspendSet) continue
        // Suspend multibindings are reported by step 2 with a more specific message
        if (dep.typeKey in suspendMultibindingKeys) continue

        // Prefer landing on the specific parameter that wraps the suspend binding rather
        // than the enclosing function/property declaration.
        val parameterDeclaration =
          binding.parameters.allParameters.find { it.typeKey == dep.typeKey }?.ir

        if (dep.isWrappedInProvider) {
          val depRender = dep.typeKey.render(short = true)
          val message = buildString {
            append(
              "[Metro/SuspendBindingWrappedInProvider] Cannot depend on suspend binding '$depRender' via Provider. Use `${suspendFunctionRender(depRender)}` instead."
            )
          }
          val element = parameterDeclaration ?: binding.reportableDeclaration ?: continue
          reportError(element.originalDeclarationIfOverride(), message)
        }

        if (dep.isWrappedInLazy) {
          val depRender = dep.typeKey.render(short = true)
          val message = buildString {
            append(
              "[Metro/SuspendBindingWrappedInLazy] Cannot depend on suspend binding '$depRender' via Lazy. Use `SuspendLazy<$depRender>` instead."
            )
          }
          val element = parameterDeclaration ?: binding.reportableDeclaration ?: continue
          reportError(element.originalDeclarationIfOverride(), message)
        }
      }
    }

    // Step 5: An assisted factory whose target consumes suspend bindings (unwrapped) must declare
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
        if (!ctxKey.isWrappedInProvider && !ctxKey.isWrappedInLazy) continue
        val depRender = ctxKey.typeKey.render(short = true)
        val wrapper = if (ctxKey.isWrappedInProvider) "Provider" else "Lazy"
        val replacement =
          if (ctxKey.isWrappedInProvider) {
            "`${suspendFunctionRender(depRender)}`"
          } else {
            "`SuspendLazy<$depRender>`"
          }
        val element = param.ir as? IrDeclarationWithName ?: target.reportableDeclaration ?: continue
        reportError(
          element.originalDeclarationIfOverride(),
          "[Metro/SuspendBindingWrappedIn$wrapper] Cannot depend on suspend binding '$depRender' via $wrapper. Use $replacement instead.",
        )
      }
      if (binding.function.isSuspend) return@forEachValue
      val blockingDep =
        target.parameters.nonDispatchParameters.firstOrNull { param ->
          !param.isAssisted &&
            param.contextualTypeKey.typeKey in suspendSet &&
            !param.contextualTypeKey.defersSuspendAtAccess
        } ?: return@forEachValue
      val head =
        bindingStackEntryForDependency(target, blockingDep.contextualTypeKey, blockingDep.typeKey)
          .withAnnotation(NEEDS_SUSPEND_SUPPORT)
      val trace = buildSuspendTrace(bindings, suspendSet, blockingDep.typeKey, head)
      val message = buildString {
        appendLine(
          "[Metro/AssistedFactorySuspendRequired] '${binding.type.kotlinFqName}' creates '${target.type.kotlinFqName}', which depends on suspend bindings. Declare '${binding.function.name}' as a suspend function so it can await them."
        )
        appendLine()
        appendLine("Trace:")
        appendBindingStackEntries(node.sourceGraph.kotlinFqName, trace)
      }
      reportError(binding.function.originalDeclarationIfOverride(), message)
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
          dep.typeKey in suspendSet &&
            !dep.isWrappedInSuspendProvider &&
            !dep.isWrappedInSuspendLazy &&
            !dep.isMapSuspendProvider
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

  private fun reportError(
    element: IrDeclarationWithName,
    message: String,
    factory: KtDiagnosticFactory1<String> = MetroDiagnostics.METRO_ERROR,
  ) {
    hasErrors = true
    metroContext.reportCompat(element, factory, message)
  }

  // Check scoping compatibility
  private fun checkScope(
    binding: IrBinding,
    stack: IrBindingStack,
    roots: Map<IrContextualTypeKey, IrBindingStack.Entry>,
    adjacency: Map<IrTypeKey, Set<IrTypeKey>>,
  ) {
    val bindingScope = binding.scope
    // Our binding doesn't have a scope... so we don't care about scopes
    if (bindingScope == null) return
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

      val locationItems = dupes.map { dupe ->
        val locationDiagnostic =
          dupe.renderLocationDiagnostic(
            shortLocation = MetroOptions.SystemProperties.SHORTEN_LOCATIONS,
            underlineTypeKey = false,
          )
        LocatedItem(
          location = locationDiagnostic.location,
          code = locationDiagnostic.description,
          span = locationDiagnostic.span,
        )
      }

      val diagnostic =
        MetroDiagnostic(
          id = MetroDiagnosticId.DUPLICATE_MAP_KEYS,
          severity = MetroSeverity.ERROR,
          title =
            buildText {
              append(DiagnosticHeadlines.DUPLICATE_MAP_KEYS_PREFIX)
              append(binding.typeKey.toText())
            },
          sections =
            buildList {
              add(
                DiagnosticSection.Locations(
                  header =
                    textOf(
                      "The following bindings contribute the same map key '${mapKey.render(short = false)}'"
                    ),
                  items = locationItems,
                )
              )
              stack.toTraceSection()?.let(::add)
            },
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
