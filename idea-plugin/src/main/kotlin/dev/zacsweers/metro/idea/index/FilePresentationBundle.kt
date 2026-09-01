// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import dev.zacsweers.metro.idea.model.AssistedSite
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingResolutionSession
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ConsumerResolution
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.IndexGenerationToken
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.matchingContextEntry
import dev.zacsweers.metro.idea.model.selectConsumerEntry
import java.util.IdentityHashMap
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtPropertyAccessor

internal data class FilePresentationKey(
  val generationToken: IndexGenerationToken,
  val file: VirtualFile,
)

/**
 * Resolved editor data for one file and index generation.
 *
 * Source ranges can be refreshed after edits while the binding results stay unchanged. This lets
 * manual refresh mode keep its graph data without attaching decorations to outdated offsets.
 */
internal class FilePresentationBundle
private constructor(
  val generationToken: IndexGenerationToken,
  val file: VirtualFile,
  private val semantics: FilePresentationSemantics,
  private val anchorInputs: List<DeclarationAnchorInput>,
  private val anchors: DeclarationAnchorMap,
) {
  internal constructor(
    generationToken: IndexGenerationToken,
    file: VirtualFile,
    declarations: Map<BindingIndex.SourcePointerIdentity, FileDeclarationPresentation>,
    anchorInputs: List<DeclarationAnchorInput>,
    index: BindingIndex,
  ) : this(
    generationToken,
    file,
    FilePresentationSemantics(declarations, index),
    anchorInputs.toList(),
    DeclarationAnchorMap.EMPTY,
  )

  /** Ignores outdated offsets until a background anchor rebuild catches up with the file. */
  fun declaration(element: KtElement): FileDeclarationPresentation? {
    val elementFile = element.containingFile as? KtFile ?: return null
    if (elementFile.virtualFile != file) return null
    if (!anchorsAreCurrent(elementFile.modificationStamp)) return null
    val range = element.textRange
    val identity = BindingIndex.SourcePointerIdentity(file, range.startOffset, range.endOffset)
    return anchors.byCurrentSourceIdentity[identity]
  }

  internal fun anchorsAreCurrent(modificationStamp: Long): Boolean {
    return anchors.modificationStamp == modificationStamp
  }

  /** Checks whether an anchor rebuild preserved the original binding results. */
  internal fun sharesSemanticData(other: FilePresentationBundle): Boolean {
    return semantics === other.semantics
  }

  /**
   * Updates source ranges from smart pointers under the caller's read action. Declarations whose
   * recorded identity no longer matches and ambiguous pointer matches are omitted.
   */
  internal fun rebuildAnchors(ktFile: KtFile): FilePresentationBundle {
    if (ktFile.virtualFile != file) return this
    val modificationStamp = ktFile.modificationStamp
    if (anchorsAreCurrent(modificationStamp)) return this

    val resolved = mutableListOf<ResolvedDeclarationAnchor>()
    for (input in anchorInputs) {
      ProgressManager.checkCanceled()
      val element = input.pointer.element as? KtElement ?: continue
      if (!element.isValid || element.containingFile !== ktFile) continue
      val signature = DeclarationAnchorSignature.capture(element)
      if (signature != input.signature) continue
      val presentation = semantics.declarations[input.sourceIdentity] ?: continue
      val range = element.textRange
      val currentIdentity =
        BindingIndex.SourcePointerIdentity(file, range.startOffset, range.endOffset)
      resolved +=
        ResolvedDeclarationAnchor(
          currentIdentity,
          presentation,
        )
    }

    val currentDeclarations =
      linkedMapOf<BindingIndex.SourcePointerIdentity, FileDeclarationPresentation>()
    for ((identity, candidates) in resolved.groupBy(ResolvedDeclarationAnchor::currentIdentity)) {
      ProgressManager.checkCanceled()
      val candidate = candidates.singleOrNull() ?: continue
      currentDeclarations[identity] = candidate.presentation
    }
    return FilePresentationBundle(
      generationToken,
      file,
      semantics,
      anchorInputs,
      DeclarationAnchorMap(modificationStamp, currentDeclarations.toMap()),
    )
  }

  fun distinctBindingDeclarations(entries: Iterable<KaBinding>): List<KaBinding> {
    return semantics.index.distinctBindingDeclarations(entries.toList())
  }

  /** Includes contextual dependencies so generic aliases with different targets remain distinct. */
  fun bindingResolutionIdentities(entries: Iterable<KaBinding>): Set<Any> {
    return semantics.index.bindingResolutionIdentities(entries.toList())
  }
}

/** Binding results reused when edits move declarations. */
private class FilePresentationSemantics(
  declarations: Map<BindingIndex.SourcePointerIdentity, FileDeclarationPresentation>,
  val index: BindingIndex,
) {
  val declarations = declarations.toMap()
}

/** Current source ranges for one file modification stamp. */
private class DeclarationAnchorMap(
  val modificationStamp: Long,
  val byCurrentSourceIdentity: Map<BindingIndex.SourcePointerIdentity, FileDeclarationPresentation>,
) {
  companion object {
    val EMPTY = DeclarationAnchorMap(Long.MIN_VALUE, emptyMap())
  }
}

/** Pointer and declaration details captured when the index generation was built. */
internal data class DeclarationAnchorInput(
  val sourceIdentity: BindingIndex.SourcePointerIdentity,
  val pointer: SmartPsiElementPointer<out PsiElement>,
  val signature: DeclarationAnchorSignature,
)

/** Rejects pointer matches whose declaration kind, name, or enclosing declarations changed. */
internal data class DeclarationAnchorSignature(
  val elementClassName: String,
  val role: String?,
  val ownerChain: List<NamedDeclarationAnchorIdentity>,
) {
  companion object {
    fun capture(element: KtElement): DeclarationAnchorSignature {
      val role =
        when (element) {
          is KtPropertyAccessor -> if (element.isGetter) "getter" else "setter"
          else -> null
        }
      val declaration =
        when (element) {
          is KtConstructor<*> -> element.getContainingClassOrObject()
          is KtPropertyAccessor -> element.property
          is KtNamedDeclaration -> element
          else -> PsiTreeUtil.getParentOfType(element, KtNamedDeclaration::class.java, false)
        }
      val ownerChain = mutableListOf<NamedDeclarationAnchorIdentity>()
      var current = declaration
      while (current != null) {
        ownerChain += NamedDeclarationAnchorIdentity(current.javaClass.name, current.name)
        current = PsiTreeUtil.getParentOfType(current, KtNamedDeclaration::class.java, true)
      }
      ownerChain.reverse()
      return DeclarationAnchorSignature(element.javaClass.name, role, ownerChain)
    }
  }
}

/** A declaration's kind and name in the path used to validate anchors. */
internal data class NamedDeclarationAnchorIdentity(
  val elementClassName: String,
  val name: String?,
)

/** A pointer match accepted for the current file version. */
private class ResolvedDeclarationAnchor(
  val currentIdentity: BindingIndex.SourcePointerIdentity,
  val presentation: FileDeclarationPresentation,
)

internal class FileDeclarationPresentation(
  val sourceIdentity: BindingIndex.SourcePointerIdentity,
  val bindingEntries: List<KaBinding>,
  val reverseUsage: ReverseUsagePresentation?,
  val consumerEntries: List<ConsumerEntry>,
  val consumerResolutions: Map<ConsumerEntry, ConsumerResolution>,
  val inlayConsumer: ConsumerEntry?,
  val graph: GraphPresentation?,
  val assistedSite: AssistedSite?,
)

/** Filters resolved consumers when the user pins a graph, preserving their original order. */
internal class ReverseUsagePresentation(candidates: List<ReverseUsageCandidate>) {
  private val candidates = candidates.toList()

  fun consumersFor(path: GraphPath?): List<ConsumerEntry> {
    if (path == null) return candidates.map(ReverseUsageCandidate::consumer)
    return candidates.mapNotNull { candidate ->
      val resolves = candidate.resolvesByContext.matchingContextEntry(path)?.value ?: true
      candidate.consumer.takeIf { resolves }
    }
  }
}

/** A false answer keeps this consumer excluded when that graph context is pinned. */
internal class ReverseUsageCandidate(
  val consumer: ConsumerEntry,
  resolvesByContext: Map<GraphContext, Boolean>,
) {
  val resolvesByContext = resolvesByContext.toMap()
}

internal class GraphPresentation(
  val graph: KaGraphDeclaration,
  val contexts: List<GraphContextPresentation>,
)

internal class GraphContextPresentation(
  val context: GraphContext,
  val contributions: List<ContributionEntry>,
  val inheritedContributions: List<ContributionEntry>,
)

/** Resolves a file's editor features with one session so they reuse graph queries. */
internal class FilePresentationBundleBuilder(
  private val index: BindingIndex,
  private val session: BindingResolutionSession,
  private val file: VirtualFile,
  private val declarationAnchorSignatures:
    Map<BindingIndex.SourcePointerIdentity, DeclarationAnchorSignature>,
) {
  private val declarations = linkedMapOf<BindingIndex.SourcePointerIdentity, MutableDeclaration>()
  private val resolutions = IdentityHashMap<ConsumerEntry, ConsumerResolution>()

  fun build(): FilePresentationBundle {
    for (binding in index.bindingEntriesInFile(file)) {
      ProgressManager.checkCanceled()
      val sourceIdentity = index.sourceIdentityFor(binding) ?: continue
      declaration(sourceIdentity, binding.pointer).bindings += binding
    }
    for (consumer in index.consumerEntriesInFile(file)) {
      ProgressManager.checkCanceled()
      val sourceIdentity = consumer.sourceIdentity ?: continue
      declaration(sourceIdentity, consumer.pointer).consumers += consumer
    }
    for (graph in index.graphEntriesInFile(file)) {
      ProgressManager.checkCanceled()
      val sourceIdentity = graph.sourceIdentity ?: continue
      declaration(sourceIdentity, graph.pointer).graph = graph
    }
    for (site in index.assistedSitesInFile(file)) {
      ProgressManager.checkCanceled()
      val sourceIdentity = site.sourceIdentity ?: continue
      declaration(sourceIdentity, site.pointer).assistedSite = site
    }

    val frozen = linkedMapOf<BindingIndex.SourcePointerIdentity, FileDeclarationPresentation>()
    val anchorInputs = mutableListOf<DeclarationAnchorInput>()
    for ((sourceIdentity, declaration) in declarations) {
      ProgressManager.checkCanceled()
      val consumerResolutions =
        declaration.consumers.associateWithTo(linkedMapOf()) { consumer -> resolution(consumer) }
      val bindingEntries = declaration.bindings.toList()
      val reverseUsage = bindingEntries.takeIf { it.isNotEmpty() }?.let(::reverseUsage)
      val graphPresentation = declaration.graph?.let(::graphPresentation)
      frozen[sourceIdentity] =
        FileDeclarationPresentation(
          sourceIdentity = sourceIdentity,
          bindingEntries = bindingEntries,
          reverseUsage = reverseUsage,
          consumerEntries = declaration.consumers.toList(),
          consumerResolutions = consumerResolutions.toMap(),
          inlayConsumer =
            selectConsumerEntry(declaration.consumers) { consumer ->
              consumerResolutions.getValue(consumer).uniformBindings?.let {
                index.bindingResolutionIdentities(it)
              }
            },
          graph = graphPresentation,
          assistedSite = declaration.assistedSite,
        )
      declarationAnchorSignatures[sourceIdentity]?.let { signature ->
        anchorInputs += DeclarationAnchorInput(sourceIdentity, declaration.anchor, signature)
      }
    }
    ProgressManager.checkCanceled()
    return FilePresentationBundle(
      generationToken = index.generationToken,
      file = file,
      declarations = frozen,
      anchorInputs = anchorInputs,
      index = index,
    )
  }

  private fun declaration(
    sourceIdentity: BindingIndex.SourcePointerIdentity,
    pointer: SmartPsiElementPointer<out PsiElement>,
  ): MutableDeclaration {
    return declarations.getOrPut(sourceIdentity) { MutableDeclaration(pointer) }
  }

  private fun resolution(consumer: ConsumerEntry): ConsumerResolution {
    resolutions[consumer]?.let {
      return it
    }
    val resolved = session.resolveConsumer(consumer)
    val frozenPerContext =
      resolved.perContext.entries.associateTo(linkedMapOf()) { (context, bindings) ->
        context to bindings.toList()
      }
    val frozen =
      ConsumerResolution(
        global = resolved.global.toList(),
        perContext = frozenPerContext,
        hasGraphs = index.graphs.isNotEmpty(),
        index = index,
      )
    resolutions[consumer] = frozen
    return frozen
  }

  private fun reverseUsage(bindingEntries: List<KaBinding>): ReverseUsagePresentation {
    val bindingSet = bindingEntries.toSet()
    val candidates =
      session.consumersFor(bindingEntries).map { consumer ->
        ProgressManager.checkCanceled()
        val resolvesByContext =
          resolution(consumer).perContext.mapValues { (_, bindings) ->
            bindings.any(bindingSet::contains)
          }
        ReverseUsageCandidate(consumer, resolvesByContext)
      }
    return ReverseUsagePresentation(candidates)
  }

  private fun graphPresentation(graph: KaGraphDeclaration): GraphPresentation {
    val contexts =
      session.contextsFor(graph).mapNotNull { context ->
        ProgressManager.checkCanceled()
        val queryContext = session.queryContext(context) ?: return@mapNotNull null
        GraphContextPresentation(
          context = context,
          contributions = session.contributionsFor(queryContext).toList(),
          inheritedContributions = session.inheritedContributionsFor(queryContext).toList(),
        )
      }
    return GraphPresentation(graph, contexts)
  }

  private class MutableDeclaration(
    val anchor: SmartPsiElementPointer<out PsiElement>,
    val bindings: MutableList<KaBinding> = mutableListOf(),
    val consumers: MutableList<ConsumerEntry> = mutableListOf(),
    var graph: KaGraphDeclaration? = null,
    var assistedSite: AssistedSite? = null,
  )
}
