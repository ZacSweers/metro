// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.components.service
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import dev.zacsweers.metro.idea.MetroCompilerSettingsTracker
import dev.zacsweers.metro.idea.index.FileShard
import dev.zacsweers.metro.idea.index.FileShardBuilder
import dev.zacsweers.metro.idea.metroIdeState
import org.jetbrains.kotlin.psi.KtFile

/** Keeps completed file analysis reusable when the coordinator retries a canceled source pass. */
internal class SourceFileShardCache {
  private val owner = Any()

  /** A forced revision invalidates each file once. PSI and project dependencies remain active. */
  fun read(file: KtFile, forcedRevision: Long?): ReadResult {
    val state = stateFor(file)
    if (forcedRevision != null) state.invalidate(ForceEpoch(owner, forcedRevision))
    val before = state.completedBuilds
    var shard = cachedShard(file, state)
    if (shard === FileShard.EMPTY && state.producedWhileDisabled && file.textLength > 0) {
      if (file.metroIdeState().isEnabled) {
        // Stub loading can briefly report a disabled module. Store the retry in the same cache so
        // later lookups keep the corrected value.
        state.invalidate(Any())
        shard = cachedShard(file, state)
      }
    }
    return ReadResult(shard, state.completedBuilds != before)
  }

  private fun cachedShard(file: KtFile, cacheState: FileCacheState): FileShard =
    CachedValuesManager.getCachedValue(file) {
      // Shards use their owning module's options. Explicit dependency files cover inherited graph
      // members and factory includes even when those files contain no Metro annotations
      // themselves.
      val moduleState = file.metroIdeState()
      val builder =
        if (moduleState.isEnabled) FileShardBuilder(file.project, moduleState.options) else null
      val shard = builder?.buildShard(file) ?: FileShard.EMPTY
      cacheState.completedBuilds++
      cacheState.producedWhileDisabled = !moduleState.isEnabled
      // Register dependency PSI with the platform cache. The shard and service store virtual
      // files so they do not keep those PSI trees alive.
      CachedValueProvider.Result.create(
        shard,
        file,
        cacheState.tracker,
        file.project.service<MetroCompilerSettingsTracker>(),
        ProjectRootModificationTracker.getInstance(file.project),
        *(builder?.psiDependencies ?: emptySet()).toTypedArray(),
      )
    }

  /** Reports actual cache computation independently of the number of files visited. */
  data class ReadResult(val shard: FileShard, val rebuilt: Boolean)

  private data class ForceEpoch(val owner: Any, val revision: Long)

  /** Stored with the PSI cache so replacement coordinators cannot reuse another owner's epoch. */
  private class FileCacheState {
    val tracker = SimpleModificationTracker()
    var completedBuilds = 0L
    var producedWhileDisabled = false
    private var lastForcedEpoch: Any? = null

    fun invalidate(epoch: Any) {
      if (epoch == lastForcedEpoch) return
      tracker.incModificationCount()
      lastForcedEpoch = epoch
    }
  }

  private fun stateFor(file: KtFile): FileCacheState {
    file.getUserData(STATE_KEY)?.let {
      return it
    }
    return (file as UserDataHolderEx).putUserDataIfAbsent(STATE_KEY, FileCacheState())
  }

  private companion object {
    val STATE_KEY = Key.create<FileCacheState>("metro.shard.cache.state")
  }
}
