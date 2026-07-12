// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.util.treeView.AbstractTreeStructure
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.ide.util.treeView.PresentableNodeDescriptor
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.SimpleTextAttributes
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.idea.MetroIcons
import dev.zacsweers.metro.idea.graph.GraphValidationResult
import dev.zacsweers.metro.idea.graph.KaGraphDiagnostic
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphNode
import dev.zacsweers.metro.idea.model.KaTypeKey
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.Icon
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

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
    val graph: KaGraphNode
      get() = context.graph

    override val icon: Icon = MetroIcons.GRAPH
    override val pointer: SmartPsiElementPointer<*> = graph.pointer
    override val identity: Any = context.path
  }

  class Category(
    parent: MetroTreeNode,
    title: String,
    override val icon: Icon,
    /** Sorted member bindings; rows build from these on each children request. */
    val bindings: List<KaBinding>,
    val ambiguousQualifiers: Set<Name>,
    /** True for the Multibindings category, whose children group by aggregate id. */
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

    // Content-aware so refreshed categories replace stale tree nodes instead of matching them
    override val identity: Any = title to bindings.map { it.typeKey }
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

  class Aggregate(
    parent: MetroTreeNode,
    multibindingId: String,
    val contributions: List<KaBinding>,
  ) : MetroTreeNode(parent) {
    override val text: String = multibindingId
    override val grayText: String = "${contributions.size} contributions"
    override val icon: Icon = MetroIcons.MULTIBINDING
    override val identity: Any = multibindingId to contributions.map { it.typeKey }
  }

  class Validation(parent: MetroTreeNode, val result: GraphValidationResult, stale: Boolean) :
    MetroTreeNode(parent) {
    override val text: String = "Validation"
    override val grayText: String = buildString {
      append(validationSummary(result))
      if (stale) append(" · code changed since this run, revalidate")
    }
    override val icon: Icon =
      when (result) {
        is GraphValidationResult.Completed ->
          if (result.diagnostics.isEmpty()) {
            AllIcons.General.InspectionsOK
          } else {
            AllIcons.General.Error
          }
        is GraphValidationResult.InternalError -> AllIcons.General.Error
      }
    override val identity: Any = Unit
  }

  class Diagnostic(parent: MetroTreeNode, val diagnostic: KaGraphDiagnostic, index: Int) :
    MetroTreeNode(parent) {
    override val text: String = "[${diagnostic.id.fullId}] ${diagnostic.diagnostic.title}"
    override val icon: Icon =
      when (diagnostic.severity) {
        MetroSeverity.ERROR -> AllIcons.General.Error
        MetroSeverity.WARNING -> AllIcons.General.Warning
      }
    override val identity: Any = index
  }

  class StackEntry(
    parent: MetroTreeNode,
    override val text: String,
    override val pointer: SmartPsiElementPointer<*>?,
    index: Int,
  ) : MetroTreeNode(parent) {
    override val icon: Icon = MetroIcons.CONSUMER
    override val identity: Any = index
  }

  class Summary(parent: MetroTreeNode, override val text: String) : MetroTreeNode(parent) {
    override val icon: Icon = AllIcons.General.Information
    override val identity: Any = Unit
  }
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

private fun validationSummary(result: GraphValidationResult): String {
  return when (result) {
    is GraphValidationResult.Completed ->
      when (val count = result.diagnostics.size) {
        0 -> "no problems found"
        1 -> "1 problem"
        else -> "$count problems"
      }
    is GraphValidationResult.InternalError -> "internal Metro plugin error"
  }
}

/**
 * The Metro tool window tree: graphs, their member bindings by category, and validation results.
 * Children are computed on demand, so a graph's bindings are only queried when it is expanded.
 */
internal class MetroTreeStructure(
  private val project: Project,
  private val filterText: () -> String,
) : AbstractTreeStructure() {

  private val root = MetroTreeNode.Root()

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
      is MetroTreeNode.Aggregate ->
        node.contributions.map { bindingRow(node, it, inAggregate = true) }
      is MetroTreeNode.Validation -> validationChildren(node)
      is MetroTreeNode.Diagnostic -> diagnosticChildren(node)
      else -> emptyList()
    }
  }

  private fun categoryRows(node: MetroTreeNode.Category): List<MetroTreeNode> {
    if (!node.grouped) {
      return node.bindings.map {
        bindingRow(node, it, ambiguousQualifiers = node.ambiguousQualifiers)
      }
    }
    return node.bindings
      .groupBy { it.multibindingId!! }
      .toSortedMap()
      .map { (id, contributions) -> MetroTreeNode.Aggregate(node, id, contributions) }
  }

  /**
   * The distinct Metro-enabled indexes. Each index is project-wide, but modules with different
   * option fingerprints get their own instance, so union across them.
   */
  private fun currentIndexes(): List<BindingIndex> {
    val resolutionService = project.service<MetroResolutionService>()
    val indexes = mutableListOf<BindingIndex>()
    for (module in ModuleManager.getInstance(project).modules) {
      val index = resolutionService.index(module)
      if (index !== BindingIndex.EMPTY && indexes.none { it === index }) {
        indexes += index
      }
    }
    return indexes
  }

  /** [node]'s context in the current indexes, refreshed so children never use stale entries. */
  private fun resolveGraph(node: MetroTreeNode.Graph): Pair<BindingIndex, GraphContext>? {
    for (index in currentIndexes()) {
      val fresh = index.findContext(node.context.path)
      if (fresh != null) return index to fresh
    }
    return null
  }

  private fun graphNodes(root: MetroTreeNode.Root): List<MetroTreeNode> {
    val validationService = project.service<MetroGraphValidationService>()
    val seen = HashSet<GraphPath>()
    val contexts =
      currentIndexes()
        .flatMap { index -> index.graphs.flatMap(index::contextsFor) }
        .filter { context ->
          seen.add(context.path)
        }
    return contexts
      .sortedWith(
        compareBy(
          { it.graph.name.orEmpty() },
          { it.chain.drop(1).joinToString { parent -> parent.name.orEmpty() } },
        )
      )
      .map { context ->
        val graph = context.graph
        // Surface the last validation outcome on the graph row itself
        val cached = graph.pointer.element?.let { validationService.cachedResult(it, context) }
        val grayText = buildString {
          graph.pointer.virtualFile?.name?.let(::append)
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

  private fun graphChildren(node: MetroTreeNode.Graph): List<MetroTreeNode> {
    val (index, context) = resolveGraph(node) ?: return emptyList()
    val queryContext = index.queryContext(context) ?: return emptyList()
    val graph = context.graph
    val bindings = index.bindingsInContext(queryContext)
    val filter = filterText().trim()
    val filtered =
      if (filter.isEmpty()) {
        bindings
      } else {
        bindings.filter { binding ->
          binding.typeKey.render(short = true).contains(filter, ignoreCase = true) ||
            binding.implementationName?.contains(filter, ignoreCase = true) == true
        }
      }

    val (multibound, regular) = filtered.partition { it.multibindingId != null }
    val (scoped, unscopedOrContributed) = regular.partition { it.scope != null }
    val (contributed, unscoped) =
      unscopedOrContributed.partition { it.contributionScopes.isNotEmpty() }

    // Distinct qualifier classes sharing a simple name render with abbreviated packages so rows
    // like two different @InternalApi qualifiers stay tellable apart
    val ambiguousQualifiers =
      filtered
        .mapNotNull { it.typeKey.qualifier?.classId }
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
          bindings = bindings.sortedBy { it.typeKey.render(short = true) },
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
    val validated = cached?.result as? GraphValidationResult.Completed
    if (validated != null) {
      val usedKeys = HashSet<KaTypeKey>()
      // Re-keyed multibinding elements are copies of their index entries, so match by pointer
      val usedPointers =
        Collections.newSetFromMap(IdentityHashMap<SmartPsiElementPointer<*>, Boolean>())

      fun collectUsage(result: GraphValidationResult.Completed) {
        result.bindings.forEach { key, binding ->
          usedKeys += key
          usedPointers += binding.pointer
        }
      }
      collectUsage(validated)
      val visited = mutableSetOf<GraphPath>()
      fun visitExtensions(parent: GraphContext) {
        for (extension in index.extensionContextsOf(parent)) {
          if (!visited.add(extension.path)) continue
          extension.graph.pointer.element
            ?.let { validationService.cachedResult(it, extension) }
            ?.result
            ?.let { result ->
              if (result is GraphValidationResult.Completed) collectUsage(result)
            }
          visitExtensions(extension)
        }
      }
      visitExtensions(context)

      val unused = filtered.filter { binding ->
        (binding is KaBinding.Provided || binding is KaBinding.Alias) &&
          binding.typeKey !in usedKeys &&
          binding.pointer !in usedPointers
      }
      category("Unused", MetroIcons.UNUSED, unused, hint = "based on validated graphs only")
    }
    return children
  }

  private fun bindingRow(
    parent: MetroTreeNode,
    binding: KaBinding,
    inAggregate: Boolean = false,
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
        // The aggregate row already names the key, so contributions show just their source
        inAggregate -> implementation ?: shortKey
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

  private fun validationChildren(node: MetroTreeNode.Validation): List<MetroTreeNode> {
    val result = node.result
    if (result is GraphValidationResult.InternalError) {
      return listOf(
        MetroTreeNode.Summary(node, "Validation failed due to an internal Metro plugin error")
      )
    }
    result as GraphValidationResult.Completed

    val children = mutableListOf<MetroTreeNode>()
    val topology = result.topology
    children +=
      if (topology != null) {
        // Count real bindings only: skip the seal's bookkeeping nodes (graph instances,
        // multibinding aggregates). Re-keyed multibinding elements count, one per contribution.
        var used = 0
        result.bindings.forEach { _, binding ->
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
      MetroTreeNode.Diagnostic(node, diagnostic, i)
    }
    return children
  }

  private fun diagnosticChildren(node: MetroTreeNode.Diagnostic): List<MetroTreeNode> {
    val graphFqName =
      (node.parent?.parent as? MetroTreeNode.Graph)?.graph?.classId?.asSingleFqName()
    val children = mutableListOf<MetroTreeNode>()
    node.diagnostic.stack.mapIndexedTo(children) { i, entry ->
      val text =
        entry.render(graphFqName ?: FqName.ROOT, short = true).lineSequence().joinToString(" ") {
          it.trim()
        }
      MetroTreeNode.StackEntry(node, text, entry.pointer, i)
    }
    // The offending bindings themselves, such as each source of a duplicate
    node.diagnostic.related.mapTo(children) { bindingRow(node, it) }
    return children
  }
}

private class MetroNodeDescriptor(
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
