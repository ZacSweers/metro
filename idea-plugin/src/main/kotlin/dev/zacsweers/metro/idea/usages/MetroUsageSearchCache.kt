// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.usages

import com.intellij.openapi.util.Key
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.SearchSession
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.IndexGenerationToken

/** Captures the target and inputs used by one Find Usages collection. */
internal data class MetroUsageSearchKey(
  val target: UsageDeclarationIdentity,
  val searchScope: SearchScope,
  val pinnedPath: GraphPath?,
  val indexGenerations: List<IndexGenerationToken>,
  val psiModificationCount: Long,
)

/** Keeps the handler's collection available for the custom searcher in the same search. */
private data class CachedMetroUsages(
  val key: MetroUsageSearchKey,
  val result: CollectedMetroUsages,
)

private val METRO_USAGES = Key.create<CachedMetroUsages>("metro.findUsages.collection")

/** Reuses a completed collection while its target, scope, and graph data are unchanged. */
internal fun SearchSession.cachedMetroUsages(key: MetroUsageSearchKey): CollectedMetroUsages? {
  val cached = getUserData(METRO_USAGES) ?: return null
  return cached.result.takeIf { cached.key == key }
}

/** The platform releases this data with the Find Usages search session. */
internal fun SearchSession.cacheMetroUsages(
  key: MetroUsageSearchKey,
  result: CollectedMetroUsages,
) {
  putUserData(METRO_USAGES, CachedMetroUsages(key, result))
}
