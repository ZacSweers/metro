// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.util.treeView.AbstractTreeStructure
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.ide.util.treeView.PresentableNodeDescriptor
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.SimpleTextAttributes
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.idea.GraphContextPinService
import dev.zacsweers.metro.idea.MetroIcons
import dev.zacsweers.metro.idea.graph.KaGraphDiagnostic
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingResolutionSession
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.isAtOrBelow
import dev.zacsweers.metro.idea.presentableName
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.Icon
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal data class GraphContextOption(val text: String, val path: GraphPath)

/** A row in the Metro tool window tree. Display data is precomputed under a read action. */
internal sealed class MetroTreeNode(val parent: MetroTreeNode?) {
  abstract val text: String
  open val grayText: String? = null
  open val icon: Icon? = null
  open val pointer: SmartPsiElementPointer<*>? = null

  /** Stable identity within [parent], preserving tree expansion across refreshes. */
  protected abstract val identity: Any

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || javaClass != other.javaClass) return false
    other as MetroTreeNode
    return identity == other.identity && parent == other.parent
  }

  override fun hashCode(): Int = 31 * identity.hashCode() + (parent?.hashCode() ?: 0)

  override fun toString(): String = text

  class Root : MetroTreeNode(null) {
    override val text: String = ""
    override val identity: Any = Unit
  }

  class Graph(
    parent: MetroTreeNode,
    val context: GraphContext,
    override val text: String,
    override val grayText: String?,
  ) : MetroTreeNode(parent) {
    val graph: KaGraphDeclaration
      get() = context.graph

    override val icon: Icon = MetroIcons.GRAPH
    override val pointer: SmartPsiElementPointer<*> = context.contextPointer
    override val identity: Any = context.path
  }

  class Category(
    parent: MetroTreeNode,
    title: String,
    override val icon: Icon,
    /** Sorted member bindings; rows build from these on each children request. */
    val bindings: List<KaBinding>,
    val ambiguousQualifiers: Set<Name>,
    /** True for the Multibindings category, whose children group by multibinding id. */
    val grouped: Boolean,
    hint: String? = null,
  ) : MetroTreeNode(parent) {
    override val text: String = title
    override val grayText: String = buildString {
      append(if (grouped) bindings.distinctBy { it.multibindingId }.size else bindings.size)
      hint?.let {
        append(" · ")
        append(it)
      }
    }

    override val identity: Any =
      CategoryIdentity(
        title = title,
        grouped = grouped,
        ambiguousQualifiers = ambiguousQualifiers,
        bindings = bindings.map { it.treeIdentity() },
      )
  }

  class BindingRow(
    parent: MetroTreeNode,
    val binding: KaBinding,
    override val text: String,
    override val grayText: String?,
  ) : MetroTreeNode(parent) {
    override val icon: Icon =
      when (binding) {
        is KaBinding.Alias -> MetroIcons.ALIAS
        is KaBinding.Multibinding -> MetroIcons.MULTIBINDING
        else -> MetroIcons.PROVIDER
      }
    override val pointer: SmartPsiElementPointer<*> = binding.pointer
    override val identity: Any = text + grayText.orEmpty()
  }

  class Multibinding(
    parent: MetroTreeNode,
    multibindingId: String,
    val contributions: List<KaBinding>,
  ) : MetroTreeNode(parent) {
    override val text: String = multibindingId
    override val grayText: String = "${contributions.size} contributions"
    override val icon: Icon = MetroIcons.MULTIBINDING
    override val identity: Any = multibindingId to contributions.map { it.treeIdentity() }
  }

  class Validation(parent: MetroTreeNode, val result: KaGraphValidationResult, stale: Boolean) :
    MetroTreeNode(parent) {
    override val text: String = "Validation"
    override val grayText: String = buildString {
      append(validationSummary(result))
      if (stale) append(" · code changed since this run, revalidate")
    }
    override val icon: Icon =
      when (result) {
        is KaGraphValidationResult.Completed ->
          if (result.diagnostics.isEmpty()) {
            AllIcons.General.InspectionsOK
          } else {
            AllIcons.General.Error
          }
        is KaGraphValidationResult.Incomplete -> AllIcons.General.Warning
        is KaGraphValidationResult.InternalError -> AllIcons.General.Error
      }
    // AsyncTreeModel retains equal nodes, so a new result must replace its old diagnostic rows.
    override val identity: Any = result to stale
  }

  class Diagnostic(parent: MetroTreeNode, val diagnostic: KaGraphDiagnostic, index: Int) :
    MetroTreeNode(parent) {
    override val text: String = "[${diagnostic.id.fullId}] ${diagnostic.diagnostic.title}"
    override val icon: Icon =
      when (diagnostic.severity) {
        MetroSeverity.ERROR -> AllIcons.General.Error
        MetroSeverity.WARNING -> AllIcons.General.Warning
      }
    override val identity: Any = DiagnosticIdentity(index, text, diagnostic.severity)
  }

  class StackEntry(
    parent: MetroTreeNode,
    override val text: String,
    override val pointer: SmartPsiElementPointer<*>?,
    index: Int,
  ) : MetroTreeNode(parent) {
    override val icon: Icon = MetroIcons.CONSUMER
    override val identity: Any = index to text
  }

  class Summary(parent: MetroTreeNode, override val text: String) : MetroTreeNode(parent) {
    override val icon: Icon = AllIcons.General.Information
    override val identity: Any = text
  }
}

private data class DiagnosticIdentity(
  val index: Int,
  val text: String,
  val severity: MetroSeverity,
)

private data class CategoryIdentity(
  val title: String,
  val grouped: Boolean,
  val ambiguousQualifiers: Set<Name>,
  val bindings: List<BindingTreeIdentity>,
)

/** Content used by child rows, kept independent of short-lived Analysis API model instances. */
private data class BindingTreeIdentity(
  val kind: String,
  val contextKey: ContextKeyTreeIdentity,
  val declarationFile: String?,
  val declarationOffset: Int?,
  val scope: String?,
  val implementationName: String?,
  val multibindingId: String?,
  val mapKeyValue: String?,
  val originClassId: String?,
  val containerId: String?,
  val includedContainerKey: String?,
  val replaces: List<String>,
  val contributionScopes: List<String>,
  val dependencies: List<ContextKeyTreeIdentity>,
  val isSuspend: Boolean,
  val label: String,
  val subtypeData: Any?,
)

private data class ContextKeyTreeIdentity(
  val rendered: String,
  val hasDefault: Boolean,
  val rawType: String?,
)

private fun KaContextualTypeKey.treeIdentity(): ContextKeyTreeIdentity {
  return ContextKeyTreeIdentity(
    rendered = render(short = false),
    hasDefault = hasDefault,
    rawType = rawType?.renderedType,
  )
}

private fun KaBinding.treeIdentity(): BindingTreeIdentity {
  val subtypeData =
    when (this) {
      is KaBinding.ConstructorInjected -> memberDependencies.map { it.treeIdentity() }
      is KaBinding.Provided -> null
      is KaBinding.Alias -> consumedKey?.treeIdentity() to isClassContribution
      is KaBinding.Multibinding -> allowEmpty to sourceBindings.map { it.render(short = false) }
      is KaBinding.BoundInstance -> isGraphInput to isBindingContainerInput
      is KaBinding.AssistedFactory ->
        listOf(
          targetConstructorDependencies.map { it.treeIdentity() },
          targetMemberDependencies.map { it.treeIdentity() },
          factoryFunctionName,
          factoryFunctionIsSuspend,
        )
      is KaBinding.GraphDependency -> ownerKey.render(short = false) to accessorIsSuspend
      is KaBinding.GraphInstance -> null
      is KaBinding.GraphExtension -> ownerKey.render(short = false)
      is KaBinding.CustomWrapper -> wrappedContextKey.treeIdentity()
    }
  return BindingTreeIdentity(
    kind = javaClass.name,
    contextKey = contextualTypeKey.treeIdentity(),
    declarationFile = pointer.virtualFile?.url,
    declarationOffset = pointer.element?.textOffset,
    scope = scope?.render(short = false),
    implementationName = implementationName,
    multibindingId = multibindingId,
    mapKeyValue = mapKeyValue,
    originClassId = originClassId?.asFqNameString(),
    containerId = containerId?.asFqNameString(),
    includedContainerKey = includedContainerKey?.render(short = false),
    replaces = replaces.map { it.asFqNameString() }.sorted(),
    contributionScopes = contributionScopes.map { it.asFqNameString() }.sorted(),
    dependencies = dependencies.map { it.treeIdentity() },
    isSuspend = isSuspend,
    label = label,
    subtypeData = subtypeData,
  )
}

/** `@com.example.reddit.InternalApi` renders as `@c.e.r.InternalApi`. */
private fun KaAnnotationSnapshot.renderAbbreviated(): String {
  val abbreviatedPrefix = buildString {
    append('@')
    for (segment in classId.packageFqName.pathSegments()) {
      append(segment.asString().first())
      append('.')
    }
    append(classId.relativeClassName.asString())
  }
  return render(short = false).replaceFirst("@${classId.asFqNameString()}", abbreviatedPrefix)
}

/** Compact outcome text shared by browser and explicit-result graph rows. */
internal fun validationSummary(result: KaGraphValidationResult): String {
  return when (result) {
    is KaGraphValidationResult.Completed ->
      when (val count = result.diagnostics.size) {
        0 -> "no problems found"
        1 -> "1 problem"
        else -> "$count problems"
      }
    is KaGraphValidationResult.Incomplete -> "analysis incomplete: ${result.reason}"
    is KaGraphValidationResult.InternalError -> "internal Metro plugin error"
  }
}

/**
 * The Metro tool window tree: graphs, their member bindings by category, and validation results.
 * Children are computed on demand, so a graph's bindings are only queried when it is expanded.
 */
internal class MetroTreeStructure(
  private val project: Project,
  private val indexProvider: (Module) -> BindingIndex = { module ->
    project.service<MetroResolutionService>().currentIndex(module)
  },
  private val pinService: GraphContextPinService = project.service(),
  private val filterText: () -> String,
) : AbstractTreeStructure() {

  private val root = MetroTreeNode.Root()
  private val contextOptionSnapshotLock = Any()
  private var contextOptionSnapshotGeneration = 0L
  @Volatile private var contextOptionSnapshot: List<GraphContextOption> = emptyList()

  override fun getRootElement(): Any = root

  override fun getChildElements(element: Any): Array<Any> {
    val node = element as? MetroTreeNode ?: return emptyArray()
    return computeChildren(node).toTypedArray()
  }

  override fun getParentElement(element: Any): Any? = (element as? MetroTreeNode)?.parent

  override fun createDescriptor(
    element: Any,
    parentDescriptor: NodeDescriptor<*>?,
  ): NodeDescriptor<*> = MetroNodeDescriptor(project, parentDescriptor, element as MetroTreeNode)

  override fun commit() {}

  override fun hasSomethingToCommit(): Boolean = false

  override fun isAlwaysLeaf(element: Any): Boolean =
    element is MetroTreeNode.BindingRow ||
      element is MetroTreeNode.StackEntry ||
      element is MetroTreeNode.Summary

  internal fun computeChildren(node: MetroTreeNode): List<MetroTreeNode> {
    // The index needs stub indexes and Analysis API resolution, so wait for smart mode
    if (DumbService.isDumb(project)) return emptyList()
    return when (node) {
      is MetroTreeNode.Root -> graphNodes(node)
      is MetroTreeNode.Graph -> graphChildren(node)
      is MetroTreeNode.Category -> categoryRows(node)
      is MetroTreeNode.Multibinding ->
        node.contributions.map {
          ProgressManager.checkCanceled()
          bindingRow(node, it, inMultibinding = true)
        }
      is MetroTreeNode.Validation -> validationTreeChildren(node)
      is MetroTreeNode.Diagnostic -> diagnosticTreeChildren(node)
      else -> emptyList()
    }
  }

  private fun categoryRows(node: MetroTreeNode.Category): List<MetroTreeNode> {
    if (!node.grouped) {
      return node.bindings.map {
        ProgressManager.checkCanceled()
        bindingRow(node, it, ambiguousQualifiers = node.ambiguousQualifiers)
      }
    }
    return node.bindings
      .groupBy {
        ProgressManager.checkCanceled()
        it.multibindingId!!
      }
      .toSortedMap()
      .map { (id, contributions) ->
        ProgressManager.checkCanceled()
        MetroTreeNode.Multibinding(node, id, contributions)
      }
  }

  /**
   * The distinct Metro-enabled indexes. Each index is project-wide; modules with the same binding
   * options reuse one instance even when their report or trace destinations differ.
   */
  private fun currentIndexes(): List<BindingIndex> {
    val indexes = mutableListOf<BindingIndex>()
    for (module in ModuleManager.getInstance(project).modules) {
      ProgressManager.checkCanceled()
      val index = indexProvider(module)
      val isNewIndex =
        index !== BindingIndex.EMPTY &&
          indexes.none {
            ProgressManager.checkCanceled()
            it === index
          }
      if (isNewIndex) {
        indexes += index
      }
    }
    return indexes
  }

  internal fun contextOptions(): List<GraphContextOption> = contextOptionSnapshot

  internal fun clearContextOptions() {
    synchronized(contextOptionSnapshotLock) {
      contextOptionSnapshotGeneration++
      contextOptionSnapshot = emptyList()
    }
  }

  private fun currentContexts(indexes: List<BindingIndex>): List<GraphContext> {
    val seen = HashSet<GraphPath>()
    return buildList {
      for (index in indexes) {
        ProgressManager.checkCanceled()
        index.withResolutionSession { session ->
          for (graph in index.graphs) {
            ProgressManager.checkCanceled()
            for (context in session.contextsFor(graph)) {
              ProgressManager.checkCanceled()
              if (seen.add(context.path)) add(context)
            }
          }
        }
      }
    }
  }

  private class ResolvedGraph(
    val index: BindingIndex,
    val session: BindingResolutionSession,
    val context: GraphContext,
  )

  /** Resolves [node]'s path against the indexes currently shown by the tree. */
  private fun resolveGraph(node: MetroTreeNode.Graph): ResolvedGraph? {
    for (index in currentIndexes()) {
      val resolved = index.withResolutionSession { session ->
        val fresh = session.findContext(node.context.path) ?: return@withResolutionSession null
        ResolvedGraph(index, session, fresh)
      }
      if (resolved != null) return resolved
    }
    return null
  }

  private fun graphNodes(root: MetroTreeNode.Root): List<MetroTreeNode> {
    val snapshotGeneration =
      synchronized(contextOptionSnapshotLock) { contextOptionSnapshotGeneration }
    val validationService = project.service<MetroGraphValidationService>()
    val indexes = currentIndexes()
    var contexts = currentContexts(indexes)
    val freshContextOptions =
      contexts
        .map { context ->
          ProgressManager.checkCanceled()
          GraphContextOption(context.presentableName(includeFile = true), context.path)
        }
        .sortedWith { left, right ->
          ProgressManager.checkCanceled()
          left.text.compareTo(right.text)
        }
    synchronized(contextOptionSnapshotLock) {
      if (contextOptionSnapshotGeneration == snapshotGeneration) {
        contextOptionSnapshot = freshContextOptions
      }
    }
    pinService.pinnedPath?.let { pinnedPath ->
      if (
        contexts.none {
          ProgressManager.checkCanceled()
          it.path == pinnedPath
        }
      ) {
        if (indexes.isNotEmpty()) pinService.clearIf(pinnedPath)
      } else {
        contexts = contexts.filter {
          ProgressManager.checkCanceled()
          it.path.isAtOrBelow(pinnedPath)
        }
      }
    }
    return contexts
      .sortedWith(
        compareBy(
          {
            ProgressManager.checkCanceled()
            it.graph.name.orEmpty()
          },
          { it.chain.drop(1).joinToString { parent -> parent.name.orEmpty() } },
          { it.dynamicGraph?.pointer?.virtualFile?.path.orEmpty() },
          { it.dynamicGraph?.pointer?.element?.textOffset ?: -1 },
          {
            it.dynamicGraph
              ?.containerKeys
              .orEmpty()
              .map { key -> key.render(short = false) }
              .sorted()
              .joinToString()
          },
        )
      )
      .map { context ->
        ProgressManager.checkCanceled()
        val graph = context.graph
        // Surface the last validation outcome on the graph row itself
        val cached =
          context.contextPointer.element?.let { validationService.cachedResult(it, context) }
        val grayText = buildString {
          val dynamicGraph = context.dynamicGraph
          if (dynamicGraph == null) {
            graph.pointer.virtualFile?.name?.let(::append)
          } else {
            append("dynamic at ")
            append(dynamicGraphLocation(dynamicGraph.pointer))
            append(" · ")
            append(dynamicGraph.containerKeys.map { it.type.shortType }.sorted().joinToString())
          }
          val parents = context.chain.drop(1)
          if (parents.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append("via ")
            append(parents.joinToString(" > ") { it.name ?: "<unknown>" })
          }
          if (cached != null) {
            append(" · ")
            append(validationSummary(cached.result))
          }
        }
        MetroTreeNode.Graph(
          parent = root,
          context = context,
          text = graph.name ?: "<unknown>",
          grayText = grayText.takeIf { it.isNotEmpty() },
        )
      }
  }

  private fun dynamicGraphLocation(pointer: SmartPsiElementPointer<*>): String {
    val file = pointer.virtualFile?.name ?: "<unknown>"
    val element = pointer.element ?: return file
    val document = element.containingFile?.viewProvider?.document ?: return file
    return "$file:${document.getLineNumber(element.textOffset) + 1}"
  }

  private fun graphChildren(node: MetroTreeNode.Graph): List<MetroTreeNode> {
    val resolved = resolveGraph(node) ?: return emptyList()
    val index = resolved.index
    val session = resolved.session
    val context = resolved.context
    val queryContext = session.queryContext(context) ?: return emptyList()
    val graph = context.graph
    val bindings = session.bindingsInContext(queryContext)
    val filter = filterText().trim()
    val filtered =
      if (filter.isEmpty()) {
        bindings
      } else {
        bindings.filter { binding ->
          ProgressManager.checkCanceled()
          binding.typeKey.render(short = true).contains(filter, ignoreCase = true) ||
            binding.implementationName?.contains(filter, ignoreCase = true) == true
        }
      }

    val multibound = mutableListOf<KaBinding>()
    val scoped = mutableListOf<KaBinding>()
    val contributed = mutableListOf<KaBinding>()
    val unscoped = mutableListOf<KaBinding>()
    for (binding in filtered) {
      ProgressManager.checkCanceled()
      when {
        binding.multibindingId != null -> multibound += binding
        binding.scope != null -> scoped += binding
        binding.contributionScopes.isNotEmpty() -> contributed += binding
        else -> unscoped += binding
      }
    }

    // Distinct qualifier classes sharing a simple name render with abbreviated packages so rows
    // like two different @InternalApi qualifiers stay tellable apart
    val ambiguousQualifiers =
      filtered
        .mapNotNull {
          ProgressManager.checkCanceled()
          it.typeKey.qualifier?.classId
        }
        .distinct()
        .groupBy { it.shortClassName }
        .filterValues { it.size > 1 }
        .keys

    val children = mutableListOf<MetroTreeNode>()

    val validationService = project.service<MetroGraphValidationService>()
    val element = graph.pointer.element
    val cached = element?.let { validationService.cachedResult(it, context) }
    if (cached != null) {
      children += MetroTreeNode.Validation(node, cached.result, cached.stale)
    }

    fun category(
      title: String,
      icon: Icon,
      bindings: List<KaBinding>,
      grouped: Boolean = false,
      hint: String? = null,
    ) {
      if (bindings.isEmpty()) return
      children +=
        MetroTreeNode.Category(
          parent = node,
          title = title,
          icon = icon,
          bindings =
            bindings.sortedWith { left, right ->
              ProgressManager.checkCanceled()
              left.typeKey.render(short = true).compareTo(right.typeKey.render(short = true))
            },
          ambiguousQualifiers = ambiguousQualifiers,
          grouped = grouped,
          hint = hint,
        )
    }

    category("Scoped", MetroIcons.SCOPED, scoped)
    category("Unscoped", MetroIcons.UNSCOPED, unscoped)
    category("Multibindings", MetroIcons.MULTIBINDING, multibound, grouped = true)
    category("Contributed", MetroIcons.CONTRIBUTED, contributed)

    // Only meaningful once a validation ran: explicitly authored bindings nothing requested.
    // Usage unions this graph's seal with any cached extension seals, since a binding declared
    // here is often consumed only by a child graph.
    val validated = cached?.result as? KaGraphValidationResult.Completed
    if (validated != null) {
      val usedKeys = HashSet<KaTypeKey>()
      // Re-keyed multibinding elements are copies of their index entries, so match by pointer
      val usedPointers =
        Collections.newSetFromMap(IdentityHashMap<SmartPsiElementPointer<*>, Boolean>())

      fun collectUsage(result: KaGraphValidationResult.Completed) {
        result.bindings.forEach { key, binding ->
          ProgressManager.checkCanceled()
          usedKeys += key
          usedPointers += binding.pointer
        }
      }
      collectUsage(validated)
      val visited = mutableSetOf<GraphPath>()
      fun visitExtensions(parent: GraphContext) {
        for (extension in session.extensionContextsOf(parent)) {
          ProgressManager.checkCanceled()
          if (!visited.add(extension.path)) continue
          extension.graph.pointer.element
            ?.let { validationService.cachedResult(it, extension) }
            ?.result
            ?.let { result ->
              if (result is KaGraphValidationResult.Completed) collectUsage(result)
            }
          visitExtensions(extension)
        }
      }
      visitExtensions(context)

      val unused = filtered.filter { binding ->
        ProgressManager.checkCanceled()
        (binding is KaBinding.Provided || binding is KaBinding.Alias) &&
          binding.typeKey !in usedKeys &&
          binding.pointer !in usedPointers
      }
      category("Unused", MetroIcons.UNUSED, unused, hint = "based on validated graphs only")
    }
    return children
  }
}

private fun bindingRow(
  parent: MetroTreeNode,
  binding: KaBinding,
  inMultibinding: Boolean = false,
  ambiguousQualifiers: Set<Name> = emptySet(),
): MetroTreeNode.BindingRow {
  val qualifier = binding.typeKey.qualifier
  val shortKey =
    if (qualifier != null && qualifier.classId.shortClassName in ambiguousQualifiers) {
      "${qualifier.renderAbbreviated()} ${binding.typeKey.render(short = true, includeQualifier = false)}"
    } else {
      binding.typeKey.render(short = true)
    }
  val implementation = binding.implementationName?.takeIf { it != binding.typeKey.type.shortType }
  val text =
    when {
      // The multibinding row already names the key, so contributions show just their source
      inMultibinding -> implementation ?: shortKey
      implementation != null -> "$shortKey -> $implementation"
      else -> shortKey
    }
  return MetroTreeNode.BindingRow(
    parent = parent,
    binding = binding,
    text = text,
    grayText = binding.location(),
  )
}

/** Builds the same completed-run rows for the graph browser and the last validation view. */
internal fun validationTreeChildren(node: MetroTreeNode.Validation): List<MetroTreeNode> {
  val result =
    when (val outcome = node.result) {
      is KaGraphValidationResult.Incomplete -> {
        return listOf(MetroTreeNode.Summary(node, "Validation incomplete: ${outcome.reason}"))
      }
      is KaGraphValidationResult.InternalError -> {
        return listOf(
          MetroTreeNode.Summary(node, "Validation failed due to an internal Metro plugin error")
        )
      }
      is KaGraphValidationResult.Completed -> outcome
    }

  val children = mutableListOf<MetroTreeNode>()
  val topology = result.topology
  children +=
    if (topology != null) {
      // Count real bindings only: skip the seal's bookkeeping nodes (graph instances,
      // multibinding nodes). Re-keyed multibinding elements count, one per contribution.
      var used = 0
      result.bindings.forEach { _, binding ->
        ProgressManager.checkCanceled()
        if (binding !is KaBinding.GraphInstance && binding !is KaBinding.Multibinding) used++
      }
      val text = buildString {
        append(used)
        append(if (used == 1) " binding" else " bindings")
        if (topology.deferredTypes.isNotEmpty()) {
          append(", ")
          append(topology.deferredTypes.size)
          append(" deferred to break cycles")
        }
      }
      MetroTreeNode.Summary(node, text)
    } else {
      MetroTreeNode.Summary(node, "Validation aborted on a fatal error")
    }
  result.diagnostics.mapIndexedTo(children) { i, diagnostic ->
    ProgressManager.checkCanceled()
    MetroTreeNode.Diagnostic(node, diagnostic, i)
  }
  return children
}

/** Preserves diagnostic stack and related-binding navigation in either result view. */
internal fun diagnosticTreeChildren(node: MetroTreeNode.Diagnostic): List<MetroTreeNode> {
  val graphFqName = (node.parent?.parent as? MetroTreeNode.Graph)?.graph?.classId?.asSingleFqName()
  val children = mutableListOf<MetroTreeNode>()
  node.diagnostic.stack.mapIndexedTo(children) { i, entry ->
    ProgressManager.checkCanceled()
    val text =
      entry.render(graphFqName ?: FqName.ROOT, short = true).lineSequence().joinToString(" ") {
        it.trim()
      }
    MetroTreeNode.StackEntry(node, text, entry.pointer, i)
  }
  // The offending bindings themselves, such as each source of a duplicate
  node.diagnostic.related.mapTo(children) {
    ProgressManager.checkCanceled()
    bindingRow(node, it)
  }
  return children
}

/** Renders precomputed rows without accessing PSI on the EDT. */
internal class MetroNodeDescriptor(
  project: Project,
  parentDescriptor: NodeDescriptor<*>?,
  private val node: MetroTreeNode,
) : PresentableNodeDescriptor<MetroTreeNode>(project, parentDescriptor) {

  override fun update(presentation: PresentationData) {
    presentation.addText(node.text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    node.grayText?.let { presentation.addText("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
    presentation.setIcon(node.icon)
  }

  override fun getElement(): MetroTreeNode = node
}
