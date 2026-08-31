// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import androidx.collection.MutableScatterMap
import androidx.collection.ScatterMap
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import java.util.Collections
import java.util.IdentityHashMap
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule

/** Identifies one complete index update. Indexes published together share this token. */
internal class IndexGenerationToken private constructor() {
  val isEmpty: Boolean
    get() = this === EMPTY

  companion object {
    val EMPTY = IndexGenerationToken()

    fun create(): IndexGenerationToken = IndexGenerationToken()
  }
}

/** Collects declarations and captured PSI data for an immutable [BindingIndex]. */
internal class BindingIndexBuilder(
  val generationToken: IndexGenerationToken = IndexGenerationToken.create()
) {
  val bindings = mutableListOf<KaBinding>()
  val consumers = mutableListOf<ConsumerEntry>()
  val graphs = mutableListOf<KaGraphDeclaration>()
  val contributions = mutableListOf<ContributionEntry>()
  val assistedSites = mutableListOf<AssistedSite>()
  val bindingContainers = mutableListOf<BindingContainerEntry>()
  val incompleteAssistedFactories =
    linkedMapOf<KaModule, Map<SourceAssistedFactoryIdentity, String>>()
  val dynamicGraphs = mutableListOf<DynamicGraphCall>()

  /** Module ownership and visibility captured under a read action before building the index. */
  var resolutionInputs: BindingIndexResolutionInputs? = null

  /** Source ranges read with [resolutionInputs]. */
  var capturedBindingSourceIdentities: Map<KaBinding, BindingIndex.SourcePointerIdentity>? = null

  fun build(): BindingIndex = BindingIndex.fromBuilder(freeze())

  internal fun freeze(): FrozenBindingIndexData {
    val frozenBindings = bindings.toList()
    val frozenConsumers = consumers.toList()
    val frozenGraphs = graphs.toList()
    val frozenContributions = contributions.toList()
    val frozenAssistedSites = assistedSites.toList()
    val frozenBindingContainers = bindingContainers.toList()
    val frozenIncompleteAssistedFactories =
      buildMap(incompleteAssistedFactories.size) {
        for ((module, boundaries) in incompleteAssistedFactories) {
          put(module, boundaries.toMap())
        }
      }
    val frozenDynamicGraphs = dynamicGraphs.toList()
    val frozenResolutionInputs =
      checkNotNull(resolutionInputs) { "Resolution inputs must be captured before finalization" }
    // Copy once here so lookups can share the map safely after the builder changes.
    val frozenBindingSourceIdentities =
      Collections.unmodifiableMap(
        IdentityHashMap(
          checkNotNull(capturedBindingSourceIdentities) {
            "Source identities must be captured before finalization"
          }
        )
      )
    val lookups =
      BindingIndexLookups.build(
        frozenBindings,
        frozenConsumers,
        frozenGraphs,
        frozenContributions,
        frozenAssistedSites,
        frozenBindingContainers,
        frozenDynamicGraphs,
        frozenBindingSourceIdentities,
      )
    return FrozenBindingIndexData(
      generationToken,
      frozenBindings,
      frozenConsumers,
      frozenGraphs,
      frozenContributions,
      frozenAssistedSites,
      frozenBindingContainers,
      frozenIncompleteAssistedFactories,
      frozenDynamicGraphs,
      frozenResolutionInputs,
      lookups,
    )
  }
}

internal class FrozenBindingIndexData(
  val generationToken: IndexGenerationToken,
  val bindings: List<KaBinding>,
  val consumers: List<ConsumerEntry>,
  val graphs: List<KaGraphDeclaration>,
  val contributions: List<ContributionEntry>,
  val assistedSites: List<AssistedSite>,
  val bindingContainers: List<BindingContainerEntry>,
  val incompleteAssistedFactories: Map<KaModule, Map<SourceAssistedFactoryIdentity, String>>,
  val dynamicGraphs: List<DynamicGraphCall>,
  val resolutionInputs: BindingIndexResolutionInputs,
  val lookups: BindingIndexLookups,
)

@JvmInline internal value class ModuleViewId(val value: Int)

@JvmInline internal value class FileOrdinal(val value: Int)

/** Immutable file ordinals shared by all module visibility arrays in one index. */
internal class FileOrdinalTable
private constructor(private val ordinals: Map<VirtualFile, FileOrdinal>) {
  val size: Int
    get() = ordinals.size

  operator fun get(file: VirtualFile?): FileOrdinal? = file?.let(ordinals::get)

  fun getValue(file: VirtualFile): FileOrdinal = ordinals.getValue(file)

  companion object {
    val EMPTY = FileOrdinalTable(emptyMap())

    /** Copies [ordinals] so later changes to the input map cannot affect module visibility. */
    fun freeze(ordinals: Map<VirtualFile, FileOrdinal>): FileOrdinalTable {
      return FileOrdinalTable(ordinals.toMap())
    }
  }
}

/** The files a module can see, captured for one index. */
internal class BindingIndexModuleView(
  val id: ModuleViewId,
  val module: KaModule,
  visibleFileOrdinals: BooleanArray,
  internal val fileOrdinalTable: FileOrdinalTable,
  val daggerAnvilInteropEnabled: Boolean,
) {
  private val visibleFileOrdinals = visibleFileOrdinals.copyOf()

  init {
    require(this.visibleFileOrdinals.size == fileOrdinalTable.size) {
      "Module visibility must cover the captured file-ordinal table"
    }
  }

  val resolutionScope: DeclarationResolutionScope =
    FrozenFileResolutionScope(this.visibleFileOrdinals, fileOrdinalTable)

  /** Lets tests verify that modules have separate visibility arrays. */
  @TestOnly
  internal fun sharesVisibilityArrayWith(other: BindingIndexModuleView): Boolean {
    return visibleFileOrdinals === other.visibleFileOrdinals
  }
}

/** Module ownership, visibility, and source ranges captured under a read action. */
internal class BindingIndexResolutionInputs(
  internal val fileOrdinalTable: FileOrdinalTable,
  moduleByFile: Map<VirtualFile, ModuleViewId>,
  moduleViews: Map<ModuleViewId, BindingIndexModuleView>,
  pointerSourceIdentities: Map<SmartPsiElementPointer<*>, BindingIndex.SourcePointerIdentity> =
    emptyMap(),
) {
  private val moduleByFile = moduleByFile.toMap()
  private val moduleViews = moduleViews.toMap()
  private val pointerSourceIdentities =
    Collections.unmodifiableMap(IdentityHashMap(pointerSourceIdentities))

  init {
    require(this.moduleViews.values.all { it.fileOrdinalTable === fileOrdinalTable }) {
      "Module views must share the captured file-ordinal table"
    }
  }

  fun moduleViewFor(file: VirtualFile?): BindingIndexModuleView? {
    val id = file?.let(moduleByFile::get) ?: return null
    return moduleViews[id]
  }

  fun fileOrdinal(file: VirtualFile?): FileOrdinal? = fileOrdinalTable[file]

  fun sourceIdentity(pointer: SmartPsiElementPointer<*>): BindingIndex.SourcePointerIdentity? =
    pointerSourceIdentities[pointer]
}

private class FrozenFileResolutionScope(
  private val visibleFileOrdinals: BooleanArray,
  private val fileOrdinalTable: FileOrdinalTable,
) : FrozenDeclarationResolutionScope {

  override fun contains(element: PsiElement): Boolean {
    return contains(element.containingFile?.virtualFile)
  }

  override fun contains(file: VirtualFile?): Boolean {
    val ordinal = fileOrdinalTable[file]?.value ?: return false
    return ordinal in visibleFileOrdinals.indices && visibleFileOrdinals[ordinal]
  }
}

internal fun sourcePointerIdentity(
  pointer: SmartPsiElementPointer<*>
): BindingIndex.SourcePointerIdentity? {
  val file = pointer.virtualFile ?: return null
  val range = pointer.psiRange ?: return null
  return BindingIndex.SourcePointerIdentity(file, range.startOffset, range.endOffset)
}

internal fun <K : Any, V> MutableScatterMap<K, MutableList<V>>.freezeListLookup():
  ScatterMap<K, List<V>> {
  val frozen = MutableScatterMap<K, List<V>>(size)
  forEach { key, values -> frozen[key] = values.toList() }
  return frozen
}
