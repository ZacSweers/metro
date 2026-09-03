// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import dev.zacsweers.metro.idea.index.FileShard
import dev.zacsweers.metro.idea.index.SourceClassDependencies

/** An immutable source view. Incremental passes copy it with only the changed shards replaced. */
internal class SourceSnapshot(
  val inputs: IndexInputs,
  val moduleFingerprints: Map<Module, IndexOptionsFingerprint>,
  val shortNames: Set<String>,
  val shards: PartitionedFileMap<FileShard>,
  /** Reused across ordinary replacements so declaration and duplicate ordering stays stable. */
  val shardOrder: List<VirtualFile>,
  /** Dependency file to the shard files that must rebuild when it changes. */
  val dependencyOwners: PartitionedFileMap<Set<VirtualFile>>,
  /** Maps shared declaration files to the shards that reference them. */
  val sharedDeclarationOwners: PartitionedFileMap<Set<VirtualFile>>,
  /** Reused while effective binary lookup inputs and source-module ownership remain unchanged. */
  val librarySummary: FinalizedSourceLibrarySummary?,
  /** Includes unannotated source classes reached through dependency requests. */
  val classBindingDependencies: SourceClassDependencies = SourceClassDependencies.EMPTY,
) {
  fun dependencyOwnersFor(file: VirtualFile): Set<VirtualFile> {
    val shardOwners = dependencyOwners[file].orEmpty()
    val classOwners = classBindingDependencies.owners[file].orEmpty()
    if (classOwners.isEmpty()) return shardOwners
    if (shardOwners.isEmpty()) return classOwners
    return shardOwners + classOwners
  }

  fun withInputs(newInputs: IndexInputs): SourceSnapshot =
    SourceSnapshot(
      newInputs,
      moduleFingerprints,
      shortNames,
      shards,
      shardOrder,
      dependencyOwners,
      sharedDeclarationOwners,
      librarySummary,
      classBindingDependencies,
    )

  fun withLibrarySummary(summary: FinalizedSourceLibrarySummary): SourceSnapshot {
    if (librarySummary === summary) return this
    return SourceSnapshot(
      inputs,
      moduleFingerprints,
      shortNames,
      shards,
      shardOrder,
      dependencyOwners,
      sharedDeclarationOwners,
      summary,
      classBindingDependencies,
    )
  }

  fun withClassBindingDependencies(dependencies: SourceClassDependencies): SourceSnapshot =
    SourceSnapshot(
      inputs,
      moduleFingerprints,
      shortNames,
      shards,
      shardOrder,
      dependencyOwners,
      sharedDeclarationOwners,
      librarySummary,
      dependencies,
    )
}

/** Collects changed shards and dependency owners, then builds a snapshot sharing unchanged data. */
internal class SourceSnapshotTransaction(private val previous: SourceSnapshot? = null) {
  private val shardChanges = linkedMapOf<VirtualFile, FileShard?>()
  private val ownerChanges = linkedMapOf<VirtualFile, MutableSet<VirtualFile>?>()
  private val sharedOwnerChanges = linkedMapOf<VirtualFile, MutableSet<VirtualFile>?>()

  fun containsShard(file: VirtualFile): Boolean = currentShard(file) != null

  fun applyShard(file: VirtualFile, shard: FileShard) {
    removeShard(file)
    if (shard === FileShard.EMPTY) return

    shardChanges[file] = shard
    for (dependencyFile in shard.dependencyFiles) {
      mutableOwners(dependencyFile).add(file)
    }
    for (sharedDeclarationFile in shard.sharedDeclarationFiles) {
      mutableSharedOwners(sharedDeclarationFile).add(file)
    }
  }

  fun removeShard(file: VirtualFile) {
    val existing = currentShard(file) ?: return
    shardChanges[file] = null
    for (dependencyFile in existing.dependencyFiles) {
      val owners = mutableOwners(dependencyFile)
      owners.remove(file)
      if (owners.isEmpty()) {
        ownerChanges[dependencyFile] = null
      }
    }
    for (sharedDeclarationFile in existing.sharedDeclarationFiles) {
      val owners = mutableSharedOwners(sharedDeclarationFile)
      owners.remove(file)
      if (owners.isEmpty()) {
        sharedOwnerChanges[sharedDeclarationFile] = null
      }
    }
  }

  /**
   * Preserves surviving file order and appends new files. Reuses the library summary when lookup
   * inputs and source-module ownership are unchanged.
   */
  fun snapshot(
    inputs: IndexInputs,
    moduleFingerprints: Map<Module, IndexOptionsFingerprint>,
    shortNames: Set<String>,
    sourceModulesMayHaveChanged: Boolean = false,
  ): SourceSnapshot {
    val previousShards = previous?.shards ?: PartitionedFileMap.empty()
    val previousOwners = previous?.dependencyOwners ?: PartitionedFileMap.empty()
    val previousSharedOwners = previous?.sharedDeclarationOwners ?: PartitionedFileMap.empty()
    val ownerUpdates = linkedMapOf<VirtualFile, Set<VirtualFile>?>()
    for ((file, owners) in ownerChanges) {
      ownerUpdates[file] = owners?.toSet()
    }
    val sharedOwnerUpdates = linkedMapOf<VirtualFile, Set<VirtualFile>?>()
    for ((file, owners) in sharedOwnerChanges) {
      sharedOwnerUpdates[file] = owners?.toSet()
    }
    val shards = previousShards.withChanges(shardChanges)
    val owners = previousOwners.withChanges(ownerUpdates)
    val sharedOwners = previousSharedOwners.withChanges(sharedOwnerUpdates)

    val existingOrder = previous?.shardOrder.orEmpty()
    val membershipChanged = shardChanges.any { (file, updated) ->
      val existed = previous?.shards?.get(file) != null
      existed != (updated != null)
    }
    val order =
      if (previous != null && !membershipChanged) {
        existingOrder
      } else {
        buildList {
          for (file in existingOrder) {
            if (file in shards) add(file)
          }
          for ((file, shard) in shardChanges) {
            if (shard != null && previous?.shards?.get(file) == null) add(file)
          }
        }
      }
    val previousSummary = previous?.librarySummary
    // File identity and declaration signatures survive moves between modules. The summary holds
    // captured module visibility, so a structural change also invalidates these lookup inputs.
    val libraryInputsChanged =
      sourceModulesMayHaveChanged ||
        previous == null ||
        !previous.classBindingDependencies.isCurrent() ||
        shardChanges.any { (file, updated) ->
          sourceLibraryInputsChanged(previous.shards[file], updated)
        }
    val librarySummary = if (!libraryInputsChanged) previousSummary else null
    return SourceSnapshot(
      inputs,
      moduleFingerprints,
      shortNames,
      shards,
      order,
      owners,
      sharedOwners,
      librarySummary,
      previous?.classBindingDependencies ?: SourceClassDependencies.EMPTY,
    )
  }

  /** Returns null for staged removals and uses the previous snapshot for unchanged files. */
  private fun currentShard(file: VirtualFile): FileShard? {
    if (shardChanges.containsKey(file)) return shardChanges[file]
    return previous?.shards?.get(file)
  }

  private fun mutableOwners(file: VirtualFile): MutableSet<VirtualFile> {
    if (ownerChanges.containsKey(file)) {
      val existing = ownerChanges[file]
      if (existing != null) return existing
      return linkedSetOf<VirtualFile>().also { ownerChanges[file] = it }
    }
    val existing = previous?.dependencyOwners?.get(file).orEmpty()
    return LinkedHashSet(existing).also { ownerChanges[file] = it }
  }

  private fun mutableSharedOwners(file: VirtualFile): MutableSet<VirtualFile> {
    if (sharedOwnerChanges.containsKey(file)) {
      val existing = sharedOwnerChanges[file]
      if (existing != null) return existing
      return linkedSetOf<VirtualFile>().also { sharedOwnerChanges[file] = it }
    }
    val existing = previous?.sharedDeclarationOwners?.get(file).orEmpty()
    return LinkedHashSet(existing).also { sharedOwnerChanges[file] = it }
  }
}

/** Stores immutable hash buckets so updates copy only the buckets containing changed entries. */
internal class PartitionedFileMap<V : Any>
private constructor(private val buckets: Array<Map<VirtualFile, V>?>) {

  operator fun contains(file: VirtualFile): Boolean {
    return buckets[bucketIndex(file)]?.containsKey(file) == true
  }

  operator fun get(file: VirtualFile): V? = buckets[bucketIndex(file)]?.get(file)

  /** Applies replacements and null removals while sharing unchanged buckets. */
  fun withChanges(changes: Map<VirtualFile, V?>): PartitionedFileMap<V> {
    if (changes.isEmpty()) return this

    val changedBuckets = mutableMapOf<Int, LinkedHashMap<VirtualFile, V>>()
    for ((file, value) in changes) {
      val index = bucketIndex(file)
      val bucket = changedBuckets.getOrPut(index) { LinkedHashMap(buckets[index].orEmpty()) }
      if (value == null) {
        bucket.remove(file)
      } else {
        bucket[file] = value
      }
    }
    val updatedBuckets = buckets.copyOf()
    for ((index, bucket) in changedBuckets) {
      updatedBuckets[index] = if (bucket.isEmpty()) null else bucket
    }
    return PartitionedFileMap(updatedBuckets)
  }

  /** Mixes high hash bits into the bucket selection. [BUCKET_COUNT] must be a power of two. */
  private fun bucketIndex(file: VirtualFile): Int {
    val hash = file.hashCode()
    return (hash xor (hash ushr 16)) and (BUCKET_COUNT - 1)
  }

  companion object {
    const val BUCKET_COUNT = 128

    fun <V : Any> empty(): PartitionedFileMap<V> =
      PartitionedFileMap(arrayOfNulls<Map<VirtualFile, V>>(BUCKET_COUNT))
  }
}
