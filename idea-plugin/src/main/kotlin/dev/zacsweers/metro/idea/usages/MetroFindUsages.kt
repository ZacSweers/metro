// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.usages

import com.intellij.find.findUsages.CustomUsageSearcher
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.usages.PsiElementUsageTarget
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProviderEx
import com.intellij.usages.impl.rules.UsageWithType
import com.intellij.util.Processor
import dev.zacsweers.metro.idea.GraphContextPinService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.index.retryCancelledIndexBuild
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.matchingContextEntry
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedDeclaration

private val INJECTED_AT_USAGE_TYPE = UsageType { "Injected at" }
private val PROVIDED_BY_USAGE_TYPE = UsageType { "Provided by" }

internal enum class MetroUsageRelationship(val usageType: UsageType) {
  INJECTED_AT(INJECTED_AT_USAGE_TYPE),
  PROVIDED_BY(PROVIDED_BY_USAGE_TYPE),
}

internal data class MetroUsageRelation(
  val declaration: KtDeclaration,
  val relationship: MetroUsageRelationship,
)

/** Adds Metro's resolved binding relationships to Kotlin's ordinary Find Usages results. */
class MetroUsageSearcher : CustomUsageSearcher() {
  override fun processElementUsages(
    element: PsiElement,
    processor: Processor<in Usage>,
    options: FindUsagesOptions,
  ) {
    val project = element.project
    if (DumbService.isDumb(project)) return

    val usages = runBlockingCancellable { collectMetroUsages(element, options) }
    for (usage in usages) {
      if (!processor.process(usage)) return
    }
  }
}

/** Classifies both Metro usages and ordinary Kotlin usages merged into the same result row. */
class MetroUsageTypeProvider : UsageTypeProviderEx {
  override fun getUsageType(element: PsiElement): UsageType? = null

  override fun getUsageType(element: PsiElement, targets: Array<out UsageTarget>): UsageType? {
    val project = element.project
    if (DumbService.isDumb(project)) return null

    val usageDeclaration = element.metroSourceDeclaration() ?: return null
    val pinnedPath = project.service<GraphContextPinService>().pinnedPath
    val usageTypeCache = project.service<MetroUsageTypeCache>()
    for (usageTarget in targets) {
      val targetElement = (usageTarget as? PsiElementUsageTarget)?.element ?: continue
      if (targetElement.project !== project) continue
      val target = targetElement.metroSourceDeclaration() ?: continue
      val relationship = usageTypeCache.relationship(target, usageDeclaration, pinnedPath)
      if (relationship != null) return relationship.usageType
    }
    return null
  }
}

private data class CollectedMetroUsages(
  val cacheEntry: MetroUsageCacheEntry?,
  val usages: List<Usage>,
) {
  companion object {
    val EMPTY = CollectedMetroUsages(cacheEntry = null, usages = emptyList())
  }
}

internal data class MetroUsageCacheEntry(
  val target: UsageDeclarationIdentity,
  val pinnedPath: GraphPath?,
  val relationships: Map<UsageDeclarationIdentity, MetroUsageRelationship>,
)

@Service(Service.Level.PROJECT)
internal class MetroUsageTypeCache {
  private val entries =
    object :
      LinkedHashMap<MetroUsageCacheKey, Map<UsageDeclarationIdentity, MetroUsageRelationship>>(
        16,
        0.75f,
        true,
      ) {
      override fun removeEldestEntry(
        eldest:
          MutableMap.MutableEntry<
            MetroUsageCacheKey,
            Map<UsageDeclarationIdentity, MetroUsageRelationship>,
          >?
      ): Boolean = size > MAX_USAGE_TARGETS
    }

  fun replace(entry: MetroUsageCacheEntry) {
    synchronized(entries) {
      entries.keys.removeIf { it.target == entry.target }
      entries[MetroUsageCacheKey(entry.target, entry.pinnedPath)] = entry.relationships
    }
  }

  fun relationship(
    target: KtDeclaration,
    usage: KtDeclaration,
    pinnedPath: GraphPath?,
  ): MetroUsageRelationship? {
    val targetIdentity = target.usageIdentity() ?: return null
    val usageIdentity = usage.usageIdentity() ?: return null
    return synchronized(entries) {
      entries[MetroUsageCacheKey(targetIdentity, pinnedPath)]?.get(usageIdentity)
    }
  }

  private companion object {
    const val MAX_USAGE_TARGETS = 32
  }
}

private data class MetroUsageCacheKey(
  val target: UsageDeclarationIdentity,
  val pinnedPath: GraphPath?,
)

private class MetroUsage(
  declaration: KtDeclaration,
  private val relationship: MetroUsageRelationship,
) : UsageInfo2UsageAdapter(UsageInfo(declaration.usageAnchor())), UsageWithType {
  override fun getUsageType(): UsageType = relationship.usageType
}

internal fun BindingIndex.metroUsageRelations(
  target: KtDeclaration,
  pinnedPath: GraphPath?,
): List<MetroUsageRelation> {
  return withResolutionSession { session ->
    val result = linkedMapOf<MetroUsageRelationKey, MetroUsageRelation>()

    val targetBindings = bindingEntriesAt(target)
    if (targetBindings.isNotEmpty()) {
      val targetBindingSet = targetBindings.toSet()
      for (consumer in session.consumersFor(targetBindings)) {
        ProgressManager.checkCanceled()
        if (pinnedPath != null) {
          val pinnedBindings =
            session.resolveConsumer(consumer).perContext.matchingContextEntry(pinnedPath)?.value
              ?: continue
          if (pinnedBindings.none { it in targetBindingSet }) continue
        }
        val declaration = consumer.pointer.element?.metroSourceDeclaration() ?: continue
        addUsageRelation(result, declaration, MetroUsageRelationship.INJECTED_AT)
      }
    }

    for (consumer in consumerEntriesAt(target)) {
      ProgressManager.checkCanceled()
      val resolution = session.resolveConsumer(consumer)
      val bindings =
        if (pinnedPath == null) {
          resolution.candidateBindings
        } else {
          resolution.perContext.matchingContextEntry(pinnedPath)?.value.orEmpty()
        }
      for (binding in distinctBindingDeclarations(bindings)) {
        val declaration = binding.pointer.element?.metroSourceDeclaration() ?: continue
        addUsageRelation(result, declaration, MetroUsageRelationship.PROVIDED_BY)
      }
    }

    result.values.toList()
  }
}

private fun BindingIndex.hasMetroUsageTarget(target: KtDeclaration): Boolean {
  return bindingEntriesAt(target).isNotEmpty() || consumerEntriesAt(target).isNotEmpty()
}

internal suspend fun collectMetroUsages(
  element: PsiElement,
  options: FindUsagesOptions,
): List<Usage> {
  val project = element.project
  if (DumbService.isDumb(project)) return emptyList()
  val result = retryCancelledIndexBuild {
    smartReadAction(project) { collectMetroUsagesInReadAction(element, options) }
  }
  result.cacheEntry?.let(project.service<MetroUsageTypeCache>()::replace)
  return result.usages
}

private fun collectMetroUsagesInReadAction(
  element: PsiElement,
  options: FindUsagesOptions,
): CollectedMetroUsages {
  ProgressManager.checkCanceled()
  val target = element.metroSourceDeclaration() ?: return CollectedMetroUsages.EMPTY
  val project = target.project
  val pinnedPath = project.service<GraphContextPinService>().pinnedPath
  val resolutionService = project.service<MetroResolutionService>()
  val cachedIndexes = resolutionService.cachedUsageIndexes(target)
  val indexes =
    if (cachedIndexes.any { it.hasMetroUsageTarget(target) }) {
      cachedIndexes
    } else {
      if (!target.hasPotentialMetroContext()) {
        return CollectedMetroUsages(
          cacheEntry = target.metroUsageCacheEntry(pinnedPath, emptyList()),
          usages = emptyList(),
        )
      }
      resolutionService.usageIndexes(target)
    }

  val seen = mutableSetOf<MetroUsageRelationKey>()
  val relations = mutableListOf<MetroUsageRelation>()
  for (index in indexes) {
    for (relation in index.metroUsageRelations(target, pinnedPath)) {
      ProgressManager.checkCanceled()
      val declaration = relation.declaration
      if (!declaration.isValid || !declaration.canNavigate()) continue
      if (!options.searchScope.containsElement(declaration)) continue
      if (!seen.add(relation.key())) continue
      relations += relation
    }
  }
  return CollectedMetroUsages(
    cacheEntry = target.metroUsageCacheEntry(pinnedPath, relations),
    usages = relations.map { MetroUsage(it.declaration, it.relationship) },
  )
}

private data class MetroUsageRelationKey(
  val declarationIdentity: Any,
  val relationship: MetroUsageRelationship,
)

private fun MetroUsageRelation.key(): MetroUsageRelationKey {
  val identity = declaration.usageIdentity() ?: declaration
  return MetroUsageRelationKey(identity, relationship)
}

internal data class UsageDeclarationIdentity(
  val file: VirtualFile,
  val startOffset: Int,
  val endOffset: Int,
)

private fun KtDeclaration.usageIdentity(): UsageDeclarationIdentity? {
  val range = textRange ?: return null
  val virtualFile = containingFile?.virtualFile ?: return null
  return UsageDeclarationIdentity(virtualFile, range.startOffset, range.endOffset)
}

private fun KtDeclaration.metroUsageCacheEntry(
  pinnedPath: GraphPath?,
  relations: List<MetroUsageRelation>,
): MetroUsageCacheEntry? {
  val targetIdentity = usageIdentity() ?: return null
  val relationships = linkedMapOf<UsageDeclarationIdentity, MetroUsageRelationship>()
  for (relation in relations) {
    val identity = relation.declaration.usageIdentity() ?: continue
    relationships.putIfAbsent(identity, relation.relationship)
  }
  return MetroUsageCacheEntry(targetIdentity, pinnedPath, relationships)
}

private fun BindingIndex.addUsageRelation(
  result: MutableMap<MetroUsageRelationKey, MetroUsageRelation>,
  declaration: KtDeclaration,
  relationship: MetroUsageRelationship,
) {
  val identity = sourceIdentity(declaration) ?: declaration
  val key = MetroUsageRelationKey(identity, relationship)
  result.putIfAbsent(key, MetroUsageRelation(declaration, relationship))
}

private fun SearchScope.containsElement(element: PsiElement): Boolean {
  val virtualFile = element.containingFile?.virtualFile ?: return false
  if (!contains(virtualFile)) return false
  if (this !is LocalSearchScope) return true
  return scope.any { scopeElement ->
    scopeElement === element || PsiTreeUtil.isAncestor(scopeElement, element, false)
  }
}

private fun KtDeclaration.usageAnchor(): PsiElement {
  return (this as? KtNamedDeclaration)?.nameIdentifier ?: this
}
