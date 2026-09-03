// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.progress.ProgressManager
import dev.zacsweers.metro.idea.model.ClassBindingIdentity
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import java.util.Collections
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule

/** Captured limits and stopped requests shared by source and binary discovery passes. */
internal class ClassBindingExpansionBudgetState(
  val writtenDepth: Int,
  val writtenNodes: Int,
  val writtenClassKeys: Set<KaTypeKey>,
  val materialized: Set<ClassBindingIdentity>,
  val derivedOrdinals: Map<ClassBindingIdentity, Int>,
  val derivedCount: Int,
  val incompleteBindings: Map<KaModule, Map<ClassBindingIdentity, String>>,
)

/** Bounds derived types so index construction can finish while the user edits a graph. */
internal class ClassBindingExpansionBudget(
  existing: Set<ClassBindingIdentity>,
  private val declarationCount: Int,
  previous: ClassBindingExpansionBudgetState?,
) {
  private var writtenDepth = previous?.writtenDepth ?: 1
  private var writtenNodes = previous?.writtenNodes ?: 1
  private val writtenClassKeys = previous?.writtenClassKeys?.toMutableSet() ?: hashSetOf()
  private val materialized = previous?.materialized?.toMutableSet() ?: existing.toMutableSet()
  private val derivedOrdinals = previous?.derivedOrdinals?.toMutableMap() ?: hashMapOf()
  private var derivedCount = previous?.derivedCount ?: 0
  private val incomplete = linkedMapOf<KaModule, MutableMap<ClassBindingIdentity, String>>()
  private val complexities = HashMap<KaTypeSnapshot, TypeComplexity>()

  init {
    materialized += existing
    previous?.incompleteBindings?.forEach { (module, values) ->
      incomplete[module] = LinkedHashMap(values)
    }
  }

  fun isConcrete(type: KaTypeSnapshot): Boolean = complexity(type).concrete

  fun includeWrittenKey(key: KaTypeKey, isClass: Boolean) {
    val size = complexity(key.type)
    if (!size.concrete) return
    writtenDepth = maxOf(writtenDepth, size.depth)
    writtenNodes = maxOf(writtenNodes, size.nodes)
    if (isClass) writtenClassKeys += key
  }

  fun allowExpansion(
    binding: KaBinding,
    module: KaModule,
    direct: Boolean,
  ): Boolean {
    val identity = binding.classBindingIdentity() ?: return true
    if (direct) includeWrittenKey(binding.typeKey, isClass = true)
    val size = complexity(binding.typeKey.type)
    val isWritten = binding.typeKey in writtenClassKeys
    val newDerived = materialized.add(identity) && !direct && !isWritten
    if (newDerived) derivedOrdinals[identity] = ++derivedCount
    val depthLimit = writtenDepth + EXTRA_TYPE_DEPTH
    val nodeLimit = writtenNodes + EXTRA_TYPE_NODES
    val countLimit =
      maxOf(
        MIN_DERIVED_BINDINGS,
        ALLOWANCE_PER_INPUT * (writtenClassKeys.size + declarationCount),
      )
    val boundary =
      when {
        direct || isWritten -> null
        size.depth > depthLimit -> "derived type-depth budget ($depthLimit levels)"
        size.nodes > nodeLimit -> "derived type-size budget ($nodeLimit nodes)"
        (derivedOrdinals[identity] ?: 0) > countLimit ->
          "derived binding budget ($countLimit bindings)"
        else -> null
      }
    if (boundary == null) {
      incomplete[module]?.remove(identity)
      return true
    }
    val name = binding.originClassId?.asFqNameString() ?: identity.virtualFile.name
    incomplete.getOrPut(module) { linkedMapOf() }[identity] =
      "Dependency analysis for $name exceeded the IDE $boundary."
    return false
  }

  fun snapshot(): ClassBindingExpansionBudgetState {
    val markers = linkedMapOf<KaModule, Map<ClassBindingIdentity, String>>()
    for ((module, values) in incomplete) {
      ProgressManager.checkCanceled()
      if (values.isNotEmpty()) markers[module] = Collections.unmodifiableMap(LinkedHashMap(values))
    }
    ProgressManager.checkCanceled()
    return ClassBindingExpansionBudgetState(
      writtenDepth,
      writtenNodes,
      writtenClassKeys.toSet(),
      materialized.toSet(),
      derivedOrdinals.toMap(),
      derivedCount,
      Collections.unmodifiableMap(markers),
    )
  }

  /** Calculate once per canonical type; neither long chains nor repeated keys copy ancestry. */
  private fun complexity(type: KaTypeSnapshot): TypeComplexity =
    complexities.getOrPut(type) {
      val pending = ArrayDeque<Pair<KaTypeSnapshot, Int>>()
      pending += type to 1
      var nodes = 0
      var depth = 0
      var concrete = true
      while (pending.isNotEmpty()) {
        ProgressManager.checkCanceled()
        val (current, currentDepth) = pending.removeLast()
        nodes++
        depth = maxOf(depth, currentDepth)
        if (current.classId == null) concrete = false
        for (argument in current.typeArguments) {
          val argumentType = argument.type
          if (argumentType == null) {
            // A star is a complete projection and still counts toward the expansion limit.
            nodes++
            depth = maxOf(depth, currentDepth + 1)
          } else {
            pending += argumentType to currentDepth + 1
          }
        }
      }
      TypeComplexity(nodes, depth, concrete)
    }

  private data class TypeComplexity(val nodes: Int, val depth: Int, val concrete: Boolean)

  private companion object {
    const val EXTRA_TYPE_DEPTH = 32
    const val EXTRA_TYPE_NODES = 256
    const val MIN_DERIVED_BINDINGS = 16_384
    const val ALLOWANCE_PER_INPUT = 8
  }
}

/** Includes the declaration file so same-named classes in separate modules stay distinct. */
internal fun KaBinding.classBindingIdentity(): ClassBindingIdentity? {
  val file = pointer.virtualFile ?: return null
  return ClassBindingIdentity(typeKey, originClassId, file)
}

/** Counts authored binding outputs when setting limits for derived class requests. */
internal fun KaBinding.writtenClassBudgetKey(): KaTypeKey? {
  return when (this) {
    is KaBinding.ConstructorInjected,
    is KaBinding.AssistedFactory -> null
    else -> typeKey
  }
}
