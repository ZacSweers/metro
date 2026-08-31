// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.idea.model.AssistedSite
import dev.zacsweers.metro.idea.model.BindingContainerEntry
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.DynamicGraphCall
import dev.zacsweers.metro.idea.model.GraphDefaultImplementation
import dev.zacsweers.metro.idea.model.GraphExtensionFactoryAccessor
import dev.zacsweers.metro.idea.model.GraphInterfaceContribution
import dev.zacsweers.metro.idea.model.GraphReference
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtElement

/** Key plus display metadata for a consuming site. */
internal class ConsumedSite(
  val contextKey: KaContextualTypeKey,
  val isAbstractType: Boolean,
  /** For `Set`/`Map` multibinding sites, the id collecting contributed elements. */
  val multibindingId: String? = null,
  /** The consumed type's class, when it is a class type. */
  val typeClassId: ClassId? = null,
) {
  val key: KaTypeKey
    get() = contextKey.typeKey
}

/** Extraction-time data for a single binding, mapped to a [KaBinding] subtype by [toKaBinding]. */
internal class BindingData(
  val key: KaTypeKey,
  val kind: Kind,
  val scope: KaAnnotationSnapshot?,
  val implementationName: String?,
  /** For alias bindings, the key of the source/impl binding this delegates to. */
  val consumedKey: KaContextualTypeKey? = null,
  /** For multibinding contributions, the multibinding id. See [KaBinding]. */
  val multibindingId: String? = null,
  /** See [KaBinding.originClassId]. */
  val originClassId: ClassId? = null,
  /** See [KaBinding.replaces]. */
  val replaces: Set<ClassId> = emptySet(),
  /** See [KaBinding.contributionScopes]. */
  val contributionScopes: Set<ClassId> = emptySet(),
  /** See [KaBinding.priority]. */
  val priority: Int = Int.MIN_VALUE,
  /** See [KaBinding.priorityFromAnvilRank]. */
  val priorityFromAnvilRank: Boolean = false,
  /** See [KaBinding.dependencies]. */
  dependencies: List<KaContextualTypeKey> = emptyList(),
  /** See [KaBinding.ConstructorInjected.constructorDependencies]. */
  val constructorDependencies: List<KaContextualTypeKey> = emptyList(),
  /** See [KaBinding.isSuspend]. */
  val isSuspend: Boolean = false,
  /** See [KaBinding.ConstructorInjected.memberDependencies]. */
  val memberDependencies: List<KaContextualTypeKey> = emptyList(),
  /** See [KaBinding.ConstructorInjected.isAssisted]. */
  val isAssisted: Boolean = false,
  /** See [KaBinding.memberInjectionOwnerIds]. */
  val memberInjectionOwnerIds: Set<ClassId> = emptySet(),
  /** See [KaBinding.mapKeyValue]. */
  val mapKeyValue: String? = null,
  /** See [KaBinding.Alias.isClassContribution]. */
  val isClassContribution: Boolean = false,
  /** See [KaBinding.Multibinding.allowEmpty]. */
  val allowEmpty: Boolean = false,
  /** See [KaBinding.isGraphPrivate]. */
  val isGraphPrivate: Boolean = false,
) {
  /** All dependencies used for graph traversal. */
  val dependencies: List<KaContextualTypeKey> =
    if (kind == Kind.CONSTRUCTOR_INJECTED) {
      constructorDependencies + memberDependencies
    } else {
      dependencies
    }

  /** The [KaBinding] subtype this data maps to. */
  enum class Kind {
    CONSTRUCTOR_INJECTED,
    PROVIDED,
    ALIAS,
    MULTIBINDING,
    BOUND_INSTANCE,
    CUSTOM_WRAPPER,
  }
}

/** Bindings synthesized from one concrete factory `@Includes` parameter type. */
internal class FactoryInputEntry(
  val key: KaTypeKey,
  val kind: Kind,
  declarationFile: VirtualFile?,
  val bindings: List<KaBinding>,
  val consumers: List<ConsumerEntry>,
) {
  val id = Id(key, kind, declarationFile)

  internal data class Id(val key: KaTypeKey, val kind: Kind, val declarationFile: VirtualFile?)

  enum class Kind {
    BINDING_CONTAINER,
    GRAPH_DEPENDENCY,
  }
}

/** A callable's existing extraction data, ready to receive a concrete graph owner after merging. */
internal class GraphInterfaceBinding(
  val pointer: SmartPsiElementPointer<out KtElement>,
  val data: BindingData,
)

/** One plain contributed interface and its reusable, session-free member surface. */
internal class GraphInterfaceSurface(
  val contribution: ContributionEntry,
  val supertypeKeys: Set<KaTypeKey>,
  val supertypeDeclarations: Set<GraphReference>,
  val bindings: List<GraphInterfaceBinding>,
  val consumers: List<ConsumerEntry>,
  val extensionCreations: Set<GraphReference>,
  val extensionFactories: List<GraphExtensionFactoryAccessor>,
  val injectedMemberOwnerIds: Set<ClassId>,
  val defaultImplementations: List<GraphDefaultImplementation> = emptyList(),
) {
  fun forGraph(graph: KaGraphDeclaration): GraphInterfaceContribution {
    val graphBindings = bindings.map { binding ->
      binding.data.toKaBinding(
        pointer = binding.pointer,
        containerId = graph.classId,
        ownerGraphId = graph.declarationId,
        originClassId = contribution.classId,
        contributionScopes = contribution.scopeKeys,
      )
    }
    return GraphInterfaceContribution(
      contribution = contribution,
      supertypeKeys = supertypeKeys,
      supertypeDeclarations = supertypeDeclarations,
      extensionCreations = extensionCreations,
      extensionFactories = extensionFactories,
      bindings = graphBindings,
      consumers = consumers.map { it.withGraphOwner(graph.declarationId, contribution) },
      injectedMemberOwnerIds = injectedMemberOwnerIds,
      defaultImplementations = defaultImplementations,
    )
  }
}

/** The Metro declarations extracted from a single file, cached against that file's PSI. */
internal class FileShard(
  val bindings: List<KaBinding>,
  val consumers: List<ConsumerEntry>,
  val graphs: List<KaGraphDeclaration>,
  val contributions: List<ContributionEntry>,
  val assistedSites: List<AssistedSite>,
  val bindingContainers: List<BindingContainerEntry>,
  val factoryInputs: List<FactoryInputEntry>,
  val dynamicGraphs: List<DynamicGraphCall>,
  /** Referenced declaration files whose changes require this shard to be rebuilt. */
  val dependencyFiles: Set<VirtualFile>,
  /**
   * Files containing referenced constants or type aliases. Fingerprint changes rebuild this shard.
   */
  val sharedDeclarationFiles: Set<VirtualFile>,
  val graphInterfaces: List<GraphInterfaceSurface> = emptyList(),
) {
  companion object {
    val EMPTY =
      FileShard(
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptySet(),
        emptySet(),
      )
  }
}
