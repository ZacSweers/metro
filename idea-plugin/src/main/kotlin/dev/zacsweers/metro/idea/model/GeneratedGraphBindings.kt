// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import com.intellij.openapi.progress.ProgressManager

/** Lazily builds graph-owned bindings for one operation's concrete graph path. */
internal class GeneratedGraphBindings(
  private val index: BindingIndex,
  private val plan: BindingIndex.GraphQueryPlan,
) {
  private val context = plan.structure.queryContext.graphContext
  private var graphBindings: Map<KaTypeKey, KaBinding>? = null
  private var directExtensions: List<KaBinding.GraphExtension>? = null

  /** A graph can supply its own unqualified instance. */
  fun instance(typeKey: KaTypeKey): KaBinding.GraphInstance? {
    if (typeKey.qualifier != null) return null
    val graph = context.chain.firstOrNull { it.graphTypeKey() == typeKey } ?: return null
    return KaBinding.GraphInstance(graph.pointer, typeKey)
  }

  fun forKey(typeKey: KaTypeKey): KaBinding? = graphBindings()[typeKey]

  /**
   * Direct child creations are kept even when no ordinary accessor requests their keys. The exact
   * child reference distinguishes them from accessors returning a factory and from factory SAMs.
   */
  fun directExtensions(): List<KaBinding.GraphExtension> {
    directExtensions?.let {
      return it
    }
    val ownerKey = context.graph.graphTypeKey()
    if (ownerKey == null) {
      directExtensions = emptyList()
      return emptyList()
    }

    val composition = index.graphComposition(plan)
    val activeGraphIds = context.graphIds
    val bindings = mutableListOf<KaBinding.GraphExtension>()
    for (extension in index.extensionsOf(plan)) {
      ProgressManager.checkCanceled()
      if (extension.declarationId in activeGraphIds) continue
      val classId = extension.classId ?: continue
      val reference = GraphReference(classId, extension.pointer.virtualFile)
      if (reference !in composition.extensionCreations) continue
      val extensionKey = extension.graphTypeKey() ?: continue
      bindings += KaBinding.GraphExtension(extension.pointer, extensionKey, ownerKey)
    }
    directExtensions = bindings
    return bindings
  }

  private fun graphBindings(): Map<KaTypeKey, KaBinding> {
    graphBindings?.let {
      return it
    }
    val chain = context.chain
    val compositions = LinkedHashMap<KaGraphDeclaration, GraphComposition>(chain.size)
    val bindings = LinkedHashMap<KaTypeKey, KaBinding>()
    for (owner in chain) {
      ProgressManager.checkCanceled()
      val ownerKey = owner.graphTypeKey() ?: continue
      val composition = index.graphComposition(plan, owner)
      compositions[owner] = composition
      val consumedKey = ownerKey.canonicalContextKey()
      for (supertypeKey in composition.supertypeKeys) {
        if (supertypeKey == ownerKey) continue
        bindings.putIfAbsent(
          supertypeKey,
          KaBinding.Alias(
            pointer = owner.pointer,
            typeKey = supertypeKey,
            consumedKey = consumedKey,
            containerId = owner.classId,
            ownerGraphId = owner.declarationId,
          ),
        )
      }
    }

    // Resolve aliases for every ancestor before creating separate factories. In particular, a
    // child's inherited factory accessor must still return a parent that implements that factory.
    for ((owner, composition) in compositions) {
      ProgressManager.checkCanceled()
      val ownerKey = owner.graphTypeKey() ?: continue
      val implementedClasses = composition.supertypeKeys.mapNotNullTo(HashSet()) { it.type.classId }
      for (accessor in composition.extensionFactories) {
        val factoryClassId = accessor.factoryKey.type.classId
        if (factoryClassId != null && factoryClassId in implementedClasses) continue
        bindings.putIfAbsent(
          accessor.factoryKey,
          KaBinding.GraphExtension(
            pointer = accessor.pointer,
            typeKey = accessor.factoryKey,
            ownerKey = ownerKey,
            isFactory = true,
          ),
        )
      }
    }

    for (binding in directExtensions()) {
      bindings.putIfAbsent(binding.typeKey, binding)
    }
    graphBindings = bindings
    return bindings
  }
}
