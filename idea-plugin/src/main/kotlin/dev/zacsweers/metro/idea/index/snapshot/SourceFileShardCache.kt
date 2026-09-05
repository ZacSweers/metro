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
import com.intellij.psi.util.ParameterizedCachedValue
import com.intellij.psi.util.ParameterizedCachedValueProvider
import dev.zacsweers.metro.idea.MetroCompilerSettingsTracker
import dev.zacsweers.metro.idea.index.FileShard
import dev.zacsweers.metro.idea.index.FileShardBuilder
import dev.zacsweers.metro.idea.metroIdeState
import dev.zacsweers.metro.idea.tracing.IdeTraceWorkItem
import org.jetbrains.kotlin.psi.KtFile

/** Keeps completed file analysis reusable when the coordinator retries a canceled source pass. */
internal class SourceFileShardCache {
  private val owner = Any()

  /** A forced revision invalidates each file once. PSI and project dependencies remain active. */
  fun read(file: KtFile, forcedRevision: Long?, trace: IdeTraceWorkItem? = null): ReadResult {
    val state = stateFor(file)
    if (forcedRevision != null) state.invalidate(ForceEpoch(owner, forcedRevision))
    val before = state.completedBuilds
    var shard = cachedShard(file, state, trace)
    if (shard === FileShard.EMPTY && state.producedWhileDisabled && file.textLength > 0) {
      if (file.metroIdeState().isEnabled) {
        // Stub loading can briefly report a disabled module. Store the retry in the same cache so
        // later lookups keep the corrected value.
        state.invalidate(Any())
        shard = cachedShard(file, state, trace)
      }
    }
    return ReadResult(shard, state.completedBuilds != before)
  }

  /** The current diagnostic item is a compute argument; the retained provider owns file state. */
  private fun cachedShard(
    file: KtFile,
    cacheState: FileCacheState,
    trace: IdeTraceWorkItem?,
  ): FileShard =
    CachedValuesManager.getManager(file.project)
      .getParameterizedCachedValue(
        file,
        SHARD_KEY,
        ShardProvider(file, cacheState),
        false,
        trace,
      )

  /** Trace arguments affect observations only. Every argument produces the same cached shard. */
  private class ShardProvider(
    private val file: KtFile,
    private val cacheState: FileCacheState,
  ) : ParameterizedCachedValueProvider<FileShard, IdeTraceWorkItem?> {
    override fun compute(trace: IdeTraceWorkItem?): CachedValueProvider.Result<FileShard> {
      // Shards use their owning module's options. Explicit dependency files cover inherited graph
      // members and factory includes even when those files contain no Metro annotations
      // themselves.
      val moduleState = file.metroIdeState()
      val builder =
        if (moduleState.isEnabled) FileShardBuilder(file.project, moduleState.options) else null
      val shard = builder?.buildShard(file, trace) ?: FileShard.EMPTY
      cacheState.completedBuilds++
      cacheState.producedWhileDisabled = !moduleState.isEnabled
      // Register dependency PSI with the platform cache. The shard and service store virtual
      // files so they do not keep those PSI trees alive.
      // The PSI CachedValuesManager helper added the containing file for nonphysical elements.
      // Preserve that dependency when supplying our own parameterized provider.
      val containingFile = if (file.isPhysical) null else file.containingFile
      return CachedValueProvider.Result.create(
        shard,
        file,
        cacheState.tracker,
        file.project.service<MetroCompilerSettingsTracker>(),
        ProjectRootModificationTracker.getInstance(file.project),
        *listOfNotNull(containingFile).toTypedArray(),
        *(builder?.psiDependencies ?: emptySet()).toTypedArray(),
      )
    }
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
    val SHARD_KEY =
      Key.create<ParameterizedCachedValue<FileShard, IdeTraceWorkItem?>>("metro.shard.cache.value")
    val STATE_KEY = Key.create<FileCacheState>("metro.shard.cache.state")
  }
}
