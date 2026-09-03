// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import dev.zacsweers.metro.idea.index.FactoryInputEntry
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaTypeKey
import java.util.Collections

/** Merges graph-owned input instances and shares each included type's member surface once. */
internal class FactoryInputMerger(
  private val bindings: MutableList<KaBinding>,
  existingInputs: List<FactoryInputEntry> = emptyList(),
) {
  private val inputs = existingInputs.associateByTo(linkedMapOf()) { it.id }
  private val pendingInputs = mutableListOf<FactoryInputEntry>()
  private var canonicalBindings: CanonicalFactoryInputBindings? = null

  /** Bindings keep their encounter order while equivalent factory parameters combine owners. */
  fun addBindings(incoming: List<KaBinding>, hasFactoryInputs: Boolean) {
    if (!hasFactoryInputs) {
      bindings += incoming
      return
    }
    for (binding in incoming) {
      val isOwnedFactoryInput =
        binding is KaBinding.BoundInstance &&
          binding.ownerGraphId != null &&
          (binding.isGraphInput || binding.isBindingContainerInput)
      if (!isOwnedFactoryInput) {
        bindings += binding
        continue
      }
      val instances =
        canonicalBindings ?: CanonicalFactoryInputBindings(bindings).also { canonicalBindings = it }
      instances.add(binding)
    }
  }

  fun addInputs(incoming: List<FactoryInputEntry>) {
    for (input in incoming) {
      if (inputs.putIfAbsent(input.id, input) == null) pendingInputs += input
    }
  }

  /** Completes one merge, appending only member surfaces absent from the initial input set. */
  fun finish(consumers: MutableList<ConsumerEntry>): List<FactoryInputEntry> {
    canonicalBindings?.finish()
    for (input in pendingInputs) {
      val sharedBindings = input.bindings
      if (sharedBindings.firstOrNull() is KaBinding.BoundInstance) {
        bindings.addAll(sharedBindings.subList(1, sharedBindings.size))
      } else {
        bindings += sharedBindings
      }
      consumers += input.consumers
    }
    return inputs.values.toList()
  }
}

/** Keeps one factory instance per source parameter while retaining every exact graph owner. */
private class CanonicalFactoryInputBindings(private val bindings: MutableList<KaBinding>) {
  private val groups = LinkedHashMap<FactoryInputBindingIdentity, FactoryInputBindingGroup>()

  fun add(binding: KaBinding.BoundInstance) {
    val file = binding.pointer.virtualFile
    val range = binding.pointer.psiRange
    if (file == null || range == null) {
      bindings += binding
      return
    }

    val identity =
      FactoryInputBindingIdentity(
        binding.typeKey,
        file,
        range.startOffset,
        range.endOffset,
        binding.isGraphInput,
        binding.isBindingContainerInput,
      )
    val existing = groups[identity]
    if (existing == null) {
      groups[identity] = FactoryInputBindingGroup(bindings.size, binding)
      bindings += binding
      return
    }

    val ownerGraphId = binding.ownerGraphId
    if (ownerGraphId != null && ownerGraphId != existing.binding.ownerGraphId) {
      val owners =
        existing.additionalOwners
          ?: LinkedHashSet(existing.binding.additionalOwnerGraphIds).also {
            existing.additionalOwners = it
          }
      owners += ownerGraphId
    }
    if (binding.additionalOwnerGraphIds.isNotEmpty()) {
      val owners =
        existing.additionalOwners
          ?: LinkedHashSet(existing.binding.additionalOwnerGraphIds).also {
            existing.additionalOwners = it
          }
      owners += binding.additionalOwnerGraphIds
      existing.binding.ownerGraphId?.let(owners::remove)
    }
  }

  fun finish() {
    for (group in groups.values) {
      ProgressManager.checkCanceled()
      val owners = group.additionalOwners
      if (owners.isNullOrEmpty()) continue

      val binding = group.binding
      bindings[group.index] =
        KaBinding.BoundInstance(
          pointer = binding.pointer,
          typeKey = binding.typeKey,
          containerId = binding.containerId,
          isGraphInput = binding.isGraphInput,
          isBindingContainerInput = binding.isBindingContainerInput,
          isGraphPrivate = binding.isGraphPrivate,
          ownerGraphId = binding.ownerGraphId,
          additionalOwnerGraphIds = Collections.unmodifiableSet(LinkedHashSet(owners)),
        )
    }
  }
}

private data class FactoryInputBindingIdentity(
  val key: KaTypeKey,
  val file: VirtualFile,
  val startOffset: Int,
  val endOffset: Int,
  val isGraphInput: Boolean,
  val isBindingContainerInput: Boolean,
)

private class FactoryInputBindingGroup(
  val index: Int,
  val binding: KaBinding.BoundInstance,
  var additionalOwners: MutableSet<GraphDeclarationId>? = null,
)
