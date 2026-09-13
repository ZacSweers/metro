// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import dev.zacsweers.metro.compiler.graph.explanation.BindingCandidateStatus
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanation
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationCandidate
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationContext
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationOutcome
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationPhase
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationRequest
import dev.zacsweers.metro.compiler.graph.explanation.BindingReason

internal fun graph(
  name: String = "AppGraph",
  parent: String? = null,
  type: String? = null,
  block: GraphFixture.() -> Unit = {},
): GraphMetadata = GraphFixture(name, parent, type).apply(block).build()

internal class GraphFixture(
  private val name: String,
  private val parent: String?,
  private val type: String?,
) {
  var scopes: List<String> = emptyList()
  var bindingExplanations: List<BindingExplanation> = emptyList()
  var extensions: ExtensionsMetadata? = null
  private val bindings = mutableListOf<BindingMetadata>()
  private val accessors = mutableListOf<AccessorMetadata>()
  private val injectors = mutableListOf<InjectorMetadata>()
  private val includedGraphKeys = mutableListOf<String>()

  fun binding(
    key: String,
    vararg dependencies: String,
    kind: String = "ConstructorInjected",
    scoped: Boolean = false,
    declaration: String? = null,
  ) {
    binding(
      bindingMetadata(key, kind, declaration, dependencies.map { DependencyMetadata(it, false) })
        .copy(isScoped = scoped)
    )
  }

  fun binding(metadata: BindingMetadata) {
    bindings.add(metadata)
  }

  fun input(key: String) {
    binding(bindingMetadata(key, kind = "BoundInstance").copy(isGraphInput = true))
  }

  fun instance(key: String = name) {
    binding(bindingMetadata(key, kind = "BoundInstance").copy(isGraphInput = false))
  }

  fun alias(key: String, target: String) {
    binding(
      bindingMetadata(key, kind = "Alias", dependencies = listOf(DependencyMetadata(target, false)))
        .copy(aliasTarget = target)
    )
  }

  fun extension(key: String, type: String? = null, factory: Boolean = false) {
    val kind =
      if (factory) {
        "GraphExtensionFactory"
      } else {
        "GraphExtension"
      }
    binding(bindingMetadata(key, kind = kind).copy(extensionType = type))
  }

  fun inherits(key: String, from: GraphMetadata, ownerKey: String = from.graph) {
    graphDependency(key, ownerKey, from.graphType ?: from.graph, fromParent = true)
  }

  fun graphDependency(
    key: String,
    ownerKey: String,
    ownerGraph: String? = ownerKey,
    fromParent: Boolean = false,
    declaration: String? = null,
  ) {
    binding(
      bindingMetadata(
          key,
          kind = "GraphDependency",
          declaration = declaration,
          dependencies = listOf(DependencyMetadata(ownerKey, false)),
        )
        .copy(graphDependency = GraphDependencyMetadata(ownerKey, ownerGraph, fromParent))
    )
  }

  fun includes(key: String) {
    input(key)
    includedGraphKeys.add(key)
  }

  fun accessor(
    key: String,
    name: String? = null,
    isProperty: Boolean = false,
    isDeferrable: Boolean = false,
  ) {
    accessor(
      AccessorMetadata(key, name = name, isProperty = isProperty, isDeferrable = isDeferrable)
    )
  }

  fun accessor(metadata: AccessorMetadata) {
    accessors.add(metadata)
  }

  fun injector(key: String, name: String? = null) {
    injectors.add(InjectorMetadata(key, name = name))
  }

  fun build(): GraphMetadata {
    val roots =
      if (accessors.isEmpty() && injectors.isEmpty()) {
        null
      } else {
        RootsMetadata(accessors.toList(), injectors.toList())
      }
    return GraphMetadata(
      graph = name,
      parentGraph = parent,
      graphType = type,
      scopes = scopes,
      aggregationScopes = emptyList(),
      bindings = bindings.toList(),
      roots = roots,
      extensions = extensions,
      includedGraphKeys = includedGraphKeys.toList(),
      bindingExplanations = bindingExplanations,
    )
  }
}

internal val GraphMetadata.regionId: String
  get() = "graph:$graph"

internal fun GraphMetadata.inReport(requestedGraph: GraphMetadata): GraphReference =
  GraphReference(this, requestedGraph)

/** References nodes owned by this graph in the requested graph's report. */
internal class GraphReference(
  private val graph: GraphMetadata,
  private val requestedGraph: GraphMetadata,
) {
  val regionId: String
    get() = graph.regionId

  fun bindingId(key: String): String = nodeId(key)

  fun accessorId(key: String, index: Int = 0): String =
    nodeId("root:${graph.graph}:accessor:$index:$key")

  private fun nodeId(localId: String): String {
    if (graph === requestedGraph) {
      return localId
    }
    return "region:${graph.graph}:$localId"
  }
}

internal fun bindingMetadata(
  key: String,
  kind: String = "ConstructorInjected",
  declaration: String? = null,
  dependencies: List<DependencyMetadata> = emptyList(),
): BindingMetadata =
  BindingMetadata(
    key = key,
    bindingKind = kind,
    isScoped = false,
    nameHint = key,
    dependencies = dependencies,
    declaration = declaration,
  )

internal fun selectedLookup(requestedKey: String, selectedKey: String): BindingExplanation =
  BindingExplanation(
    context = BindingExplanationContext("AppGraph", "AppGraph"),
    phase = BindingExplanationPhase.LOOKUP,
    outcome = BindingExplanationOutcome.SELECTED,
    candidates =
      listOf(
        BindingExplanationCandidate(
          selectedKey,
          selectedKey,
          BindingCandidateStatus.SELECTED,
          BindingReason.SELECTED_MULTIBINDING,
        )
      ),
    request = BindingExplanationRequest(requestedKey),
  )

internal fun analysisReport(metadata: GraphMetadata): FullAnalysisReport {
  val analyzer = GraphAnalyzer(BindingGraph.from(metadata))
  val analysis =
    GraphAnalysis(
      graphName = metadata.graph,
      statistics = analyzer.computeStatistics(),
      longestPath = analyzer.findLongestPaths(),
      dominator = analyzer.computeDominators(),
      centrality = analyzer.computeBetweennessCentrality(),
      fanAnalysis = analyzer.computeFanAnalysis(10),
      pathsToRoot = analyzer.computePathsToRoot(),
    )
  return FullAnalysisReport(":test", listOf(analysis))
}
