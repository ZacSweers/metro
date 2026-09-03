// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.progress.ProgressManager
import dev.zacsweers.metro.idea.index.LibraryGraphDeclarations
import dev.zacsweers.metro.idea.model.KaBinding

/**
 * Adds cached binary declarations while merging their factory inputs with current source owners.
 */
internal fun SourceAggregate.withLibraryGraphs(library: LibraryGraphDeclarations): SourceAggregate {
  if (library.isEmpty) return this
  val mergedConsumers = (consumers + library.consumers).toMutableList()
  val mergedBindings: List<KaBinding>
  val mergedInputs =
    if (library.factoryInputs.isEmpty()) {
      mergedBindings = bindings + library.bindings
      factoryInputs
    } else {
      val output = mutableListOf<KaBinding>()
      val merger = FactoryInputMerger(output, factoryInputs)
      merger.addBindings(bindings, factoryInputs.isNotEmpty())
      merger.addBindings(library.bindings, hasFactoryInputs = true)
      merger.addInputs(library.factoryInputs)
      val inputs = merger.finish(mergedConsumers)
      mergedBindings = output
      inputs
    }
  return copy(
    bindings = mergedBindings,
    consumers = mergedConsumers,
    graphs = graphs + library.graphs,
    factoryInputs = mergedInputs,
  )
}

/** Source contributions join new binary children before binary contributions join every graph. */
internal fun combineGraphInterfaceOverlays(
  sourceChildren: GraphInterfaceOverlay,
  binaryInterfaces: GraphInterfaceOverlay,
): GraphInterfaceOverlay {
  if (sourceChildren.isEmpty) return binaryInterfaces
  if (binaryInterfaces.isEmpty) return sourceChildren
  val interfaces = sourceChildren.interfacesByGraph.toMutableMap()
  for ((graphId, additions) in binaryInterfaces.interfacesByGraph) {
    ProgressManager.checkCanceled()
    interfaces[graphId] = interfaces[graphId].orEmpty() + additions
  }
  return GraphInterfaceOverlay(
    interfaces,
    sourceChildren.bindings + binaryInterfaces.bindings,
    sourceChildren.consumers + binaryInterfaces.consumers,
  )
}
