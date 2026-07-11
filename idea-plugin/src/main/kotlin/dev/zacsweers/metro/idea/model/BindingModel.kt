// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement

/** A site that consumes a binding for [key]: an injected parameter/property or graph accessor. */
internal class ConsumerEntry(
  val pointer: SmartPsiElementPointer<out KtElement>,
  /** The consumed key with its `Provider`/`Lazy`/`Map` wrapper structure preserved. */
  val contextKey: KaContextualTypeKey,
  /** Whether the declared type is an interface or abstract class (drives implementation inlays). */
  val isAbstractType: Boolean = false,
  /** For `Set`/`Map` aggregate sites, the multibinding id collecting contributed elements. */
  val multibindingId: String? = null,
  /** The consumed type's class, when it is a class type. Used to resolve library inject classes. */
  val typeClassId: ClassId? = null,
  /** The contributed/injected class this consumer belongs to, for excludes/replaces matching. */
  val originClassId: ClassId? = null,
  /** Scopes that make the owning contributed declaration live. Empty for non-contributed sites. */
  val contributionScopes: Set<ClassId> = emptySet(),
  /** Binding container or graph class whose membership gates this consumer. */
  val containerId: ClassId? = null,
  /** Concrete binding-container factory input whose membership gates this consumer. */
  val includedContainerKey: KaTypeKey? = null,
  /** Exact owning graph declaration for graph accessor consumers. */
  val graphId: GraphDeclarationId? = null,
  /**
   * Whether absence is allowed: a native `@OptionalBinding`/`@OptionalDependency` site, or a
   * defaulted parameter under `DEFAULT` optional-binding behavior. An unresolved optional site is
   * not an error.
   */
  val isOptional: Boolean = false,
) {
  val key: KaTypeKey
    get() = contextKey.typeKey
}

/**
 * A parameter supplied at runtime rather than injected from the graph: `@Assisted` parameters and
 * Circuit-provided types (`Screen`, `Navigator`, etc.) on `@CircuitInject` declarations.
 */
internal class AssistedSite(
  val pointer: SmartPsiElementPointer<out KtElement>,
  /** Short description of what supplies the value, such as `@Assisted` or `Circuit`. */
  val supplier: String,
  /**
   * True when nothing in the source marks the parameter as assisted, like Circuit-provided types,
   * as opposed to an explicit `@Assisted` annotation. Implicit sites get an `assisted` inlay;
   * explicit ones don't need a second marker.
   */
  val isImplicit: Boolean,
)

/** A `@DependencyGraph`/`@GraphExtension`-annotated class and its aggregation metadata. */
internal class KaGraphNode(
  val pointer: SmartPsiElementPointer<KtClassOrObject>,
  val scopeKeys: Set<ClassId>,
  val classId: ClassId? = null,
  /** Contribution classes excluded via the graph annotation's `excludes`. */
  val excludes: Set<ClassId> = emptySet(),
  /** Binding containers wired via the graph annotation's `bindingContainers`. */
  val bindingContainers: Set<ClassId> = emptySet(),
  /** Concrete binding containers wired via factory `@Includes` parameters. */
  val includedBindingContainers: Set<KaTypeKey> = emptySet(),
  /** Graph dependencies wired via factory `@Includes` parameters. */
  val includedDependencies: Set<KaTypeKey> = emptySet(),
  /** True for `@GraphExtension` declarations, which inherit their parent graphs' bindings. */
  val isExtension: Boolean = false,
  /** This graph's class plus nested factory classes, used for parent/extension matching. */
  val selfIds: Set<ClassId> = emptySet(),
  /** Supertype classes whose members merge into this graph, gating their provider membership. */
  val supertypeIds: Set<ClassId> = emptySet(),
  /** Extension or extension factory ids created by this graph's accessors. */
  val extensionCreationIds: Set<ClassId> = emptySet(),
  /**
   * The scope annotations this graph declares: explicit scope annotations on the class plus the
   * implicit `@SingleIn(X::class)` conveyed by each aggregation scope in the graph annotation
   * (`@DependencyGraph(AppScope::class)` implies `@SingleIn(AppScope::class)`). Scoped bindings are
   * only members of graphs whose declared scopes include theirs.
   */
  val scopingAnnotations: Set<KaAnnotationSnapshot> = emptySet(),
) {
  val declarationId: GraphDeclarationId = GraphDeclarationId(classId, pointer.virtualFile)

  val name: String?
    get() = classId?.shortClassName?.asString()
}

/** A `@BindingContainer`-annotated class and the containers it transitively includes. */
internal class BindingContainerEntry(val classId: ClassId, val includes: Set<ClassId>)

/**
 * Stable identity for one graph declaration across index rebuilds.
 *
 * The IDE index spans the whole project, where unrelated modules or separate KMP platform
 * compilations can each declare the same graph FQN. Pairing [classId] with [file] keeps their
 * accessors and validation roots isolated.
 */
internal data class GraphDeclarationId(
  val classId: ClassId?,
  val file: VirtualFile?,
)

/** A concrete graph path, ordered from the graph itself through its ancestors. */
internal data class GraphPath(val segments: List<GraphDeclarationId>)

/**
 * The aggregated view a single graph (plus its parent chain, for extensions) has of the project:
 * the inputs to per-graph binding membership.
 */
internal class GraphContext(
  /** The graph itself followed by its parent chain, nearest first. */
  val chain: List<KaGraphNode>,
  val scopes: Set<ClassId>,
  /** Declared scope annotations across the chain, gating scoped-binding membership. */
  val scopingAnnotations: Set<KaAnnotationSnapshot>,
  val excludes: Set<ClassId>,
  /** Concrete binding-container inputs inherited across the graph chain. */
  val includedBindingContainers: Set<KaTypeKey>,
  /** Concrete graph-dependency inputs inherited across the graph chain. */
  val includedDependencies: Set<KaTypeKey>,
  /** Exact declarations in this graph path, used for graph-owned consumers. */
  val graphIds: Set<GraphDeclarationId>,
  val graphClassIds: Set<ClassId>,
) {
  val graph: KaGraphNode
    get() = chain.first()

  /** The root graph whose compilation creates this concrete graph or extension instance. */
  val rootGraph: KaGraphNode
    get() = chain.last()

  /** Stable declaration identity for this exact parent path. */
  val path: GraphPath = GraphPath(chain.map { it.declarationId })
}

/**
 * A concrete graph-analysis view for a single use-site module.
 *
 * The declaration shards remain project-wide and reusable, while query contexts apply the same kind
 * of graph/use-site filtering the shared compiler graph can eventually consume.
 */
internal class GraphQueryContext(
  val graphContext: GraphContext,
  /** The concrete graph's compilation module, used for every graph-scoped lookup. */
  val graphModule: KaModule,
  /** The Analysis API's formal view of declarations resolvable from [graphModule]. */
  val resolutionScope: DeclarationResolutionScope,
  /**
   * Transitively expanded class-literal containers visible from [graphModule]. Concrete factory
   * inputs remain keyed by type in [GraphContext.includedBindingContainers].
   */
  val containers: Set<ClassId>,
)

/** A session-free containment view over an Analysis API module resolution scope. */
internal fun interface DeclarationResolutionScope {
  fun contains(element: PsiElement): Boolean
}

/** Modules from which declarations synthesized from a non-public contribution hint are visible. */
internal class HintAvailability(modules: Set<KaModule>) {
  private val modules = modules.toSet()

  fun isVisibleFrom(module: KaModule): Boolean = module in modules
}

/**
 * A declaration contributing to aggregation scopes: a `@Contributes*`-annotated class or a
 * `@CircuitInject`-annotated declaration whose generated factory is contributed.
 */
internal class ContributionEntry(
  val pointer: SmartPsiElementPointer<out KtElement>,
  val scopeKeys: Set<ClassId>,
  val classId: ClassId? = null,
  val hintAvailability: HintAvailability? = null,
)
