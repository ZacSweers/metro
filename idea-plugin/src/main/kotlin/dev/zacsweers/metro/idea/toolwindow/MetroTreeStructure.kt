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
    val graph: KaGraphNode,
    override val text: String,
    override val grayText: String?,
  ) : MetroTreeNode(parent) {
    override val icon: Icon = MetroIcons.GRAPH
    override val pointer: SmartPsiElementPointer<*> = graph.pointer
    override val identity: Any = graph.classId ?: text
  }

  class Category(parent: MetroTreeNode, title: String, override val icon: Icon) :
    MetroTreeNode(parent) {
    val rows = mutableListOf<MetroTreeNode>()
    override val text: String = title
    override val grayText: String
      get() = "${rows.size}"

    override val identity: Any = title
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

  class Aggregate(parent: MetroTreeNode, multibindingId: String) : MetroTreeNode(parent) {
    val rows = mutableListOf<MetroTreeNode>()
    override val text: String = multibindingId
    override val grayText: String
      get() = "${rows.size} contributions"

    override val icon: Icon = MetroIcons.MULTIBINDING
    override val identity: Any = multibindingId
  }

  class Validation(parent: MetroTreeNode, val result: GraphValidationResult, stale: Boolean) :
    MetroTreeNode(parent) {
    override val text: String = "Validation"
    override val grayText: String = buildString {
      append(validationSummary(result))
      if (stale) append(" · code changed since this run, revalidate")
    }
    override val icon: Icon =
      if (result.diagnostics.isEmpty()) AllIcons.General.InspectionsOK else AllIcons.General.Error
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
  return when (val count = result.diagnostics.size) {
    0 -> "no problems found"
    1 -> "1 problem"
    else -> "$count problems"
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
    return when (node) {
      is MetroTreeNode.Root -> graphNodes(node)
      is MetroTreeNode.Graph -> graphChildren(node)
      is MetroTreeNode.Category -> node.rows
      is MetroTreeNode.Aggregate -> node.rows
      is MetroTreeNode.Validation -> validationChildren(node)
      is MetroTreeNode.Diagnostic -> diagnosticChildren(node)
      else -> emptyList()
    }
  }

  /** The first Metro-enabled module's index. Indexes are project-wide, so any module works. */
  private fun currentIndex(): BindingIndex? {
    val resolutionService = project.service<MetroResolutionService>()
    for (module in ModuleManager.getInstance(project).modules) {
      val index = resolutionService.index(module)
      if (index !== BindingIndex.EMPTY) return index
    }
    return null
  }

  private fun graphNodes(root: MetroTreeNode.Root): List<MetroTreeNode> {
    val index = currentIndex() ?: return emptyList()
    val validationService = project.service<MetroGraphValidationService>()
    return index.graphs
      .sortedBy { it.name.orEmpty() }
      .map { graph ->
        // Surface the last validation outcome on the graph row itself
        val cached = graph.pointer.element?.let { validationService.cachedResult(it, graph) }
        val grayText = buildString {
          graph.pointer.virtualFile?.name?.let(::append)
          if (cached != null) {
            append(" · ")
            append(validationSummary(cached.result))
          }
        }
        MetroTreeNode.Graph(
          parent = root,
          graph = graph,
          text = graph.name ?: "<unknown>",
          grayText = grayText.takeIf { it.isNotEmpty() },
        )
      }
  }

  private fun graphChildren(node: MetroTreeNode.Graph): List<MetroTreeNode> {
    val index = currentIndex() ?: return emptyList()
    val graph = node.graph
    val bindings = index.bindingsInContext(index.contextFor(graph))
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
    val cached = element?.let { validationService.cachedResult(it, graph) }
    if (cached != null) {
      children += MetroTreeNode.Validation(node, cached.result, cached.stale)
    }

    fun category(title: String, icon: Icon, bindings: List<KaBinding>) {
      if (bindings.isEmpty()) return
      val category = MetroTreeNode.Category(node, title, icon)
      bindings
        .sortedBy { it.typeKey.render(short = true) }
        .mapTo(category.rows) {
          bindingRow(category, it, ambiguousQualifiers = ambiguousQualifiers)
        }
      children += category
    }

    category("Scoped", MetroIcons.SCOPED, scoped)
    category("Unscoped", MetroIcons.UNSCOPED, unscoped)
    if (multibound.isNotEmpty()) {
      val category = MetroTreeNode.Category(node, "Multibindings", MetroIcons.MULTIBINDING)
      multibound
        .groupBy { it.multibindingId!! }
        .toSortedMap()
        .mapTo(category.rows) { (id, contributions) ->
          val aggregate = MetroTreeNode.Aggregate(category, id)
          contributions.mapTo(aggregate.rows) { bindingRow(aggregate, it, inAggregate = true) }
          aggregate
        }
      children += category
    }
    category("Contributed", MetroIcons.CONTRIBUTED, contributed)

    // Only meaningful once a validation ran: explicitly authored bindings nothing requested.
    // Usage unions this graph's seal with any cached extension seals, since a binding declared
    // here is often consumed only by a child graph.
    if (cached != null) {
      val usedKeys = HashSet<KaTypeKey>()
      // Re-keyed multibinding elements are copies of their index entries, so match by pointer
      val usedPointers =
        Collections.newSetFromMap(IdentityHashMap<SmartPsiElementPointer<*>, Boolean>())

      fun collectUsage(result: GraphValidationResult) {
        result.bindings.forEach { key, binding ->
          usedKeys += key
          usedPointers += binding.pointer
        }
      }
      collectUsage(cached.result)
      val visited = mutableSetOf<KaGraphNode>()
      fun visitExtensions(parent: KaGraphNode) {
        for (extension in index.extensionsOf(parent)) {
          if (!visited.add(extension)) continue
          extension.pointer.element
            ?.let { validationService.cachedResult(it, extension) }
            ?.let { collectUsage(it.result) }
          visitExtensions(extension)
        }
      }
      visitExtensions(graph)

      val unused = filtered.filter { binding ->
        (binding is KaBinding.Provided || binding is KaBinding.Alias) &&
          binding.typeKey !in usedKeys &&
          binding.pointer !in usedPointers
      }
      category("Unused", MetroIcons.UNUSED, unused)
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
