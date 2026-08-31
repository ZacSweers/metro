// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.graph.MergeContribution
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement

/** A site that consumes a binding for [key]: an injected parameter/property or graph accessor. */
internal class ConsumerEntry(
  val pointer: SmartPsiElementPointer<out KtElement>,
  /** The consumed key with its `Provider`/`Lazy`/`Map` wrapper structure preserved. */
  val contextKey: KaContextualTypeKey,
  /** Whether the declared type is an interface or abstract class (drives implementation inlays). */
  val isAbstractType: Boolean = false,
  /** For `Set`/`Map` multibinding sites, the id collecting contributed elements. */
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
  /** Class that declares this injected member, when the site is not a constructor parameter. */
  val memberOwnerClassId: ClassId? = null,
  /**
   * Direct injected-member target for a graph injector request, retained without extra analysis.
   */
  val injectedMemberPointer: SmartPsiElementPointer<out KtElement>? = null,
  /** How a graph declaration requests this key. Null for ordinary dependency sites. */
  val graphRequestKind: GraphRequestKind? = null,
  /** Whether a graph accessor is declared as a suspend function. */
  val isSuspend: Boolean = false,
  /**
   * Whether absence is allowed: a native `@OptionalBinding`/`@OptionalDependency` site, or a
   * defaulted parameter under `DEFAULT` optional-binding behavior. An unresolved optional site is
   * not an error.
   */
  val isOptional: Boolean = false,
  /** Exact implicit interface contribution that must survive in the owning graph path. */
  val graphContribution: GraphReference? = null,
) {
  val sourceIdentity: BindingIndex.SourcePointerIdentity? = sourcePointerIdentity(pointer)
  val injectedMemberSourceIdentity: BindingIndex.SourcePointerIdentity? =
    injectedMemberPointer?.let(::sourcePointerIdentity)

  val key: KaTypeKey
    get() = contextKey.typeKey

  /** Reuses one extracted contributed member for an exact candidate graph owner. */
  fun withGraphOwner(graphId: GraphDeclarationId, contribution: ContributionEntry): ConsumerEntry {
    return ConsumerEntry(
      pointer = pointer,
      contextKey = contextKey,
      isAbstractType = isAbstractType,
      multibindingId = multibindingId,
      typeClassId = typeClassId,
      originClassId = originClassId ?: contribution.classId,
      contributionScopes = contribution.scopeKeys,
      containerId = graphId.classId ?: containerId,
      includedContainerKey = includedContainerKey,
      graphId = graphId,
      memberOwnerClassId = memberOwnerClassId,
      injectedMemberPointer = injectedMemberPointer,
      graphRequestKind = graphRequestKind,
      isSuspend = isSuspend,
      isOptional = isOptional,
      graphContribution = contribution.declarationId,
    )
  }

  enum class GraphRequestKind {
    ACCESSOR,
    MEMBERS_INJECTOR,
  }
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
) {
  val sourceIdentity: BindingIndex.SourcePointerIdentity? = sourcePointerIdentity(pointer)
}

/** A `@DependencyGraph`/`@GraphExtension`-annotated class and its aggregation metadata. */
internal class KaGraphDeclaration(
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
  /** Member-injected classes explicitly requested by this graph's injector functions. */
  val injectedMemberOwnerIds: Set<ClassId> = emptySet(),
  /** Whether the owning module accepts Anvil's `rank` spelling for contribution priority. */
  val daggerAnvilInteropEnabled: Boolean = false,
  /** Extension or extension factory declarations created by this graph's accessors. */
  val extensionCreations: Set<GraphReference> = emptySet(),
  /** Whether this graph's compilation classpath contains the optional coroutine runtime. */
  val runtimeCoroutinesAvailable: Boolean = false,
  /**
   * The scope annotations this graph declares: explicit scope annotations on the class plus the
   * implicit `@SingleIn(X::class)` conveyed by each aggregation scope in the graph annotation
   * (`@DependencyGraph(AppScope::class)` implies `@SingleIn(AppScope::class)`). Scoped bindings are
   * only members of graphs whose declared scopes include theirs.
   */
  val scopingAnnotations: Set<KaAnnotationSnapshot> = emptySet(),
  /** Full written supertype keys, preserving concrete generic arguments. */
  val supertypeKeys: Set<KaTypeKey> = emptySet(),
  /** Exact declarations backing [supertypeKeys]. Contributed interfaces are kept separately. */
  val supertypeDeclarations: Set<GraphReference> = emptySet(),
  /** Written accessors that return an extension factory rather than the child graph itself. */
  val extensionFactories: List<GraphExtensionFactoryAccessor> = emptyList(),
  /** Scope-matched candidates; visibility and removal are selected for each concrete graph path. */
  val contributedInterfaces: List<GraphInterfaceContribution> = emptyList(),
  /** Concrete members from the written graph hierarchy that satisfy inherited abstract requests. */
  val defaultImplementations: List<GraphDefaultImplementation> = emptyList(),
) {
  val sourceIdentity: BindingIndex.SourcePointerIdentity? = sourcePointerIdentity(pointer)
  val declarationId: GraphDeclarationId = GraphDeclarationId(classId, pointer.virtualFile)
  val selfReferences: Set<GraphReference> =
    selfIds.mapTo(mutableSetOf()) { GraphReference(it, pointer.virtualFile) }

  val name: String?
    get() = classId?.shortClassName?.asString()

  fun withContributedInterfaces(interfaces: List<GraphInterfaceContribution>): KaGraphDeclaration {
    if (interfaces.isEmpty()) return this
    return KaGraphDeclaration(
      pointer = pointer,
      scopeKeys = scopeKeys,
      classId = classId,
      excludes = excludes,
      bindingContainers = bindingContainers,
      includedBindingContainers = includedBindingContainers,
      includedDependencies = includedDependencies,
      isExtension = isExtension,
      selfIds = selfIds,
      supertypeIds = supertypeIds,
      injectedMemberOwnerIds = injectedMemberOwnerIds,
      daggerAnvilInteropEnabled = daggerAnvilInteropEnabled,
      extensionCreations = extensionCreations,
      runtimeCoroutinesAvailable = runtimeCoroutinesAvailable,
      scopingAnnotations = scopingAnnotations,
      supertypeKeys = supertypeKeys,
      supertypeDeclarations = supertypeDeclarations,
      extensionFactories = extensionFactories,
      contributedInterfaces = interfaces,
      defaultImplementations = defaultImplementations,
    )
  }
}

/** The graph's own type as a key, used for graph instance and parent dependency nodes. */
internal fun KaGraphDeclaration.graphTypeKey(): KaTypeKey? {
  val classId = classId ?: return null
  val snapshot =
    KaTypeSnapshot(classId.asFqNameString(), classId.shortClassName.asString(), classId)
  return KaTypeKey(snapshot)
}

/** A `@BindingContainer` declaration and the containers it transitively includes. */
internal class BindingContainerEntry(
  val pointer: SmartPsiElementPointer<KtClassOrObject>,
  val classId: ClassId,
  val includes: Set<ClassId>,
) {
  val sourceIdentity: BindingIndex.SourcePointerIdentity? = sourcePointerIdentity(pointer)
  /** Separate modules can declare containers with the same fully qualified class name. */
  val declarationId: GraphReference = GraphReference(classId, pointer.virtualFile)
}

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

/** The compiler identity shared by equivalent dynamic-graph calls in one source file. */
internal data class DynamicGraphId(
  val requestedTypeClassId: ClassId,
  val containerKeys: Set<KaTypeKey>,
  val callerFile: VirtualFile,
)

/** One distinct `createDynamicGraph*` context and the bindings supplied by its containers. */
internal class DynamicGraphCall(
  val pointer: SmartPsiElementPointer<KtCallExpression>,
  val id: DynamicGraphId,
  val targetGraph: GraphReference,
  val bindingKeys: Set<KaTypeKey>,
  val containerInputs: List<KaBinding.BoundInstance>,
  val isFactory: Boolean,
) {
  val sourceIdentity: BindingIndex.SourcePointerIdentity? = sourcePointerIdentity(pointer)
  val containerKeys: Set<KaTypeKey>
    get() = id.containerKeys
}

/** One concrete assisted-factory declaration, including its exact source or binary file. */
internal data class SourceAssistedFactoryIdentity(
  val key: KaTypeKey,
  val originClassId: ClassId?,
  val virtualFile: VirtualFile,
)

/** A resolved graph or nested factory class, qualified by its source or binary declaration file. */
internal data class GraphReference(
  val classId: ClassId,
  val file: VirtualFile?,
)

/** Session-free callable shape used to invalidate source/library overlays after member edits. */
internal data class GraphCallableSignature(
  val callableId: CallableId?,
  val receiverType: KaTypeSnapshot?,
  val parameterTypes: List<KaTypeSnapshot>,
  val returnType: KaTypeSnapshot,
  val isProperty: Boolean,
  val isSuspend: Boolean,
)

/**
 * An exact callable declaration; override matching uses its pointer, not its name or return type.
 */
internal class GraphCallableReference(
  val pointer: SmartPsiElementPointer<out KtElement>,
  val signature: GraphCallableSignature,
) {
  val sourceIdentity: BindingIndex.SourcePointerIdentity? = sourcePointerIdentity(pointer)
}

/** A concrete graph member and the real declarations that it overrides. */
internal class GraphDefaultImplementation(
  val declaration: GraphCallableReference,
  val overriddenDeclarations: List<GraphCallableReference>,
  val isOptional: Boolean,
)

/** A real graph accessor whose result is an extension factory. */
internal class GraphExtensionFactoryAccessor(
  val pointer: SmartPsiElementPointer<out KtElement>,
  val factoryKey: KaTypeKey,
  val extensionKey: KaTypeKey,
  val extension: GraphReference,
) {
  val sourceIdentity: BindingIndex.SourcePointerIdentity? = sourcePointerIdentity(pointer)
}

/** One extracted interface surface, materialized for an exact candidate owning graph. */
internal class GraphInterfaceContribution(
  val contribution: ContributionEntry,
  val supertypeKeys: Set<KaTypeKey>,
  val supertypeDeclarations: Set<GraphReference>,
  val extensionCreations: Set<GraphReference>,
  val extensionFactories: List<GraphExtensionFactoryAccessor>,
  val bindings: List<KaBinding>,
  val consumers: List<ConsumerEntry>,
  val injectedMemberOwnerIds: Set<ClassId>,
  val defaultImplementations: List<GraphDefaultImplementation> = emptyList(),
)

/** The effective interface surface of one graph in a concrete parent path and root module. */
internal class GraphComposition(
  val supertypeKeys: Set<KaTypeKey>,
  val supertypeDeclarations: Set<GraphReference>,
  val extensionCreations: Set<GraphReference>,
  val extensionFactories: List<GraphExtensionFactoryAccessor>,
  val contributions: List<ContributionEntry>,
  val accessors: List<ConsumerEntry>,
  val injectedMemberOwnerIds: Set<ClassId>,
)

/** A concrete graph path, ordered from the graph itself through its ancestors. */
internal data class GraphPath(
  val segments: List<GraphDeclarationId>,
  val dynamicGraphId: DynamicGraphId? = null,
)

/**
 * The aggregated view a single graph (plus its parent chain, for extensions) has of the project:
 * the inputs to per-graph binding membership.
 */
internal class GraphContext(
  /** The graph itself followed by its parent chain, nearest first. */
  val chain: List<KaGraphDeclaration>,
  val scopes: Set<ClassId>,
  /** Declared scope annotations across the chain, gating scoped-binding membership. */
  val scopingAnnotations: Set<KaAnnotationSnapshot>,
  val excludes: Set<ClassId>,
  /** Concrete binding-container inputs inherited across the graph chain. */
  val includedBindingContainers: Set<KaTypeKey>,
  /** Concrete graph-dependency inputs inherited across the graph chain. */
  val includedDependencies: Set<KaTypeKey>,
  /** Owners injected directly by any graph along this graph's parent path. */
  val injectedMemberOwnerIds: Set<ClassId>,
  /** Whether this graph accepts Anvil's `rank` spelling for contribution priority. */
  val daggerAnvilInteropEnabled: Boolean,
  /** Exact declarations in this graph path, used for graph-owned consumers. */
  val graphIds: Set<GraphDeclarationId>,
  val graphClassIds: Set<ClassId>,
  /** The dynamic call-site variant inherited by this graph and its extension children. */
  val dynamicGraph: DynamicGraphCall? = null,
) {
  val graph: KaGraphDeclaration
    get() = chain.first()

  /** The root graph whose compilation creates this concrete graph or extension instance. */
  val rootGraph: KaGraphDeclaration
    get() = chain.last()

  /** The source element whose compilation owns this graph context. */
  val contextPointer: SmartPsiElementPointer<out KtElement>
    get() = dynamicGraph?.pointer ?: graph.pointer

  /** Stable declaration identity for this exact parent path. */
  val path: GraphPath = GraphPath(chain.map { it.declarationId }, dynamicGraph?.id)
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

/** A file visibility snapshot that remains unchanged for the lifetime of an index. */
internal interface FrozenDeclarationResolutionScope : DeclarationResolutionScope {
  fun contains(file: VirtualFile?): Boolean
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
  val kind: Kind = Kind.OTHER,
  override val replaces: Set<ClassId> = emptySet(),
  /** Excluding this child graph also excludes its contributed nested factory. */
  val graphExtension: GraphReference? = null,
) : MergeContribution {
  val sourceIdentity: BindingIndex.SourcePointerIdentity? = sourcePointerIdentity(pointer)

  override val mergeId: ClassId?
    get() = classId

  val declarationId: GraphReference? = classId?.let { GraphReference(it, pointer.virtualFile) }

  enum class Kind {
    OTHER,
    BINDING_CONTAINER,
    GRAPH_INTERFACE,
  }
}
