// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.GIT_SHA
import dev.zacsweers.metro.idea.MetroSettings
import dev.zacsweers.metro.idea.VERSION
import dev.zacsweers.metro.idea.graph.CachedValidation
import dev.zacsweers.metro.idea.graph.KaBindingLookup
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.retryCancelledIndexBuild
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingResolutionSession
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.GraphExtensionFactoryAccessor
import dev.zacsweers.metro.idea.model.GraphQueryContext
import dev.zacsweers.metro.idea.model.GraphReference
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaAnnotationValueSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import dev.zacsweers.metro.idea.model.canonicalContextKey
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibrarySourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.name.ClassId

/** Builds a privacy-filtered report on demand and writes it to the IDE log directory. */
@Service(Service.Level.PROJECT)
internal class MetroGraphDebugExporter(
  private val project: Project,
  private val scope: CoroutineScope,
) {
  private var exportJob: Job? = null

  /** Called from the tool-window action on the EDT. A newer request supersedes an older export. */
  fun export(context: GraphContext) {
    exportJob?.cancel()
    exportJob = scope.launch {
      var failureType: String? = null
      val output =
        try {
          val report =
            withBackgroundProgress(project, "Collecting Metro graph debug info") {
              retryCancelledIndexBuild { smartReadAction(project) { report(context) } }
            }
          report?.let {
            withContext(Dispatchers.IO) {
              writeGraphDebugReport(PathManager.getLogDir(), it)
            }
          }
        } catch (failure: Throwable) {
          // The report can contain private project identifiers. Do not attach it, or an arbitrary
          // exception message, to the plugin's automatic error-reporting path.
          failureType = safeFailureType(failure)
          null
        }
      withContext(Dispatchers.EDT) {
        if (project.isDisposed) return@withContext
        val message =
          if (output == null) {
            val failure = failureType
            if (failure == null) {
              "Metro graph debug info is unavailable; refresh the graph"
            } else {
              "Metro graph debug info failed ($failure)"
            }
          } else {
            try {
              RevealFileAction.openFile(output.toFile())
              "Metro graph debug info exported"
            } catch (failure: Throwable) {
              "Metro graph debug info was exported but could not be shown (${safeFailureType(failure)})"
            }
          }
        StatusBar.Info.set(message, project)
      }
    }
  }

  /** Builds a report for the current version of this exact parent path under a read action. */
  fun report(context: GraphContext): String? {
    val element = context.contextPointer.element ?: return null
    val service = project.service<MetroGraphValidationService>()
    return service.debugLookup(element, context) { index, session, queryContext, options, lookup ->
      val currentContext = queryContext.graphContext
      val declaration = currentContext.graph.pointer.element ?: element
      val cached = service.cachedResult(declaration, currentContext)
      GraphDebugReport(project, index, session, queryContext, options, cached, lookup).render()
    }
  }

  private fun safeFailureType(failure: Throwable): String {
    when (failure) {
      is CancellationException,
      is ProcessCanceledException,
      is VirtualMachineError,
      is ThreadDeath -> throw failure
    }
    return failure.javaClass.name
  }
}

internal fun writeGraphDebugReport(directory: Path, report: String): Path {
  Files.createDirectories(directory)
  val output = Files.createTempFile(directory, "metro-graph-debug-", ".txt")
  return Files.writeString(output, report, StandardCharsets.UTF_8)
}

/**
 * One local report. Nothing here reads declaration bodies or renders arbitrary annotation values.
 */
private class GraphDebugReport(
  private val project: Project,
  private val index: BindingIndex,
  private val session: BindingResolutionSession,
  private val queryContext: GraphQueryContext,
  private val options: MetroOptions,
  private val cachedValidation: CachedValidation?,
  private val lookup: KaBindingLookup,
) {
  private val output = StringBuilder()
  private val typeIds = LinkedHashMap<KaTypeSnapshot, Int>()
  private val annotationIds = LinkedHashMap<KaAnnotationSnapshot, Int>()
  private val multibindingIds = LinkedHashMap<String, Int>()
  private val mapKeyIds = LinkedHashMap<String, Int>()
  private val fileIds = LinkedHashMap<VirtualFile, Int>()
  private val moduleIds = LinkedHashMap<KaModule, Int>()
  private val bindingIds = IdentityHashMap<KaBinding, Int>()
  private val bindingRecords = ArrayList<KaBinding>()
  private val bindingSortKeys = IdentityHashMap<KaBinding, String>()
  private val indexedBindings = index.bindings.toHashSet()
  private val rawCandidates = HashMap<KaTypeSnapshot, List<KaBinding>>()
  private val contextCandidates = HashMap<KaTypeKey, List<KaBinding>>()
  private val selectedCandidates = HashMap<KaContextualTypeKey, SelectedCandidates>()

  private class SelectedCandidates(
    val bindings: List<KaBinding>,
    val duplicates: List<List<KaBinding>>,
  )

  fun render(): String {
    line("Metro Graph Debug Info")
    field("formatVersion", 1)
    field("plugin.version", VERSION)
    field("plugin.gitSha", GIT_SHA.ifEmpty { "<development build>" })
    line(
      "Local report only; source bodies, absolute paths, and annotation literal values are omitted."
    )
    line("Type and annotation IDs preserve exact identity when literal values are omitted.")

    section("Metro options")
    line(safeOptions(options))
    section("IDE settings")
    val settings = MetroSettings.getInstance(project).state
    field("suppressUnusedWarnings", settings.suppressUnusedWarnings)
    field("suppressKaptConfigurationWarning", settings.suppressKaptConfigurationWarning)
    field("enableBindingResolution", settings.enableBindingResolution)
    field("automaticallyRefreshGraphData", settings.automaticallyRefreshGraphData)
    field("resolveFromLibraries", settings.resolveFromLibraries)
    field("assistedParameterInlays", settings.assistedParameterInlays)

    section("Validation")
    validation()

    val context = queryContext.graphContext
    section("Graph context")
    field("compilationModule", module(queryContext.graphModule))
    field(
      "path (selected graph first)",
      context.chain.joinToString(" <- ") { graphId(it.declarationId) },
    )
    field("scopes", classIds(context.scopes))
    field("scopingAnnotations", annotations(context.scopingAnnotations))
    field("excludes", classIds(context.excludes))
    field("effectiveBindingContainers", classIds(queryContext.containers))
    field("includedBindingContainers", keys(context.includedBindingContainers))
    field("includedDependencies", keys(context.includedDependencies))
    field("injectedMemberOwners", classIds(context.injectedMemberOwnerIds))
    field("daggerAnvilInteropEnabled", context.daggerAnvilInteropEnabled)
    context.dynamicGraph?.let { dynamicGraph ->
      field("dynamic.callSite", location(dynamicGraph.pointer))
      field("dynamic.requestedType", classId(dynamicGraph.id.requestedTypeClassId))
      field("dynamic.isFactory", dynamicGraph.isFactory)
      field("dynamic.containers", keys(dynamicGraph.containerKeys))
      field("dynamic.replacementKeys", keys(dynamicGraph.bindingKeys))
    }
    for (graph in context.chain) {
      ProgressManager.checkCanceled()
      writeGraph(graph)
    }

    section("Contributions")
    val ownContributions = session.contributionsFor(queryContext)
    val inheritedContributions = session.inheritedContributionsFor(queryContext)
    field("own", ownContributions.size)
    val contributionOrder =
      compareBy<ContributionEntry>(
        { it.classId?.asFqNameString().orEmpty() },
        { pointerSortKey(it.pointer) },
      )
    for (contribution in ownContributions.sortedWith(contributionOrder)) {
      ProgressManager.checkCanceled()
      line(
        "  ${classId(contribution.classId)} scopes=${classIds(contribution.scopeKeys)} at ${location(contribution.pointer)}"
      )
    }
    field("inherited", inheritedContributions.size)
    for (contribution in inheritedContributions.sortedWith(contributionOrder)) {
      ProgressManager.checkCanceled()
      line(
        "  ${classId(contribution.classId)} scopes=${classIds(contribution.scopeKeys)} at ${location(contribution.pointer)}"
      )
    }

    val requests =
      session
        .accessorsFor(queryContext)
        .sortedWith(
          compareBy({ pointerSortKey(it.pointer) }, { it.contextKey.render(short = false) })
        )
    section("Index counts")
    field("bindings", index.bindings.size)
    field(
      "bindingKinds",
      index.bindings.groupingBy { it.javaClass.simpleName }.eachCount().toSortedMap(),
    )
    field("bindingsInContext", session.bindingsInContext(queryContext).size)
    field("consumers", index.consumers.size)
    field("graphs", index.graphs.size)
    field("contributions", index.contributions.size)
    field("assistedSites", index.assistedSites.size)
    field("bindingContainers", index.bindingContainers.size)
    field("graphRequests", requests.size)

    section("Graph requests")
    line("rawSameType uses BindingIndex.bindingsWithType (all qualifiers).")
    line(
      "inContext uses BindingIndex.bindingsForKey (including incompatible scopes for diagnostics)."
    )
    line("selected and duplicates come from the graph's initialized validation lookup.")
    for ((number, request) in requests.withIndex()) {
      ProgressManager.checkCanceled()
      val requestKey =
        request.contextKey.withDefault(request.isOptional || request.contextKey.hasDefault)
      val raw = rawCandidates.getOrPut(request.key.type) { index.bindingsWithType(request.key) }
      val inContext =
        contextCandidates.getOrPut(request.key) {
          index.bindingsForKey(request.key, lookup.queryPlan)
        }
      val selected = selectCandidates(requestKey)
      line("request ${number + 1}:")
      field("  location", location(request.pointer))
      field("  kind", request.graphRequestKind?.name ?: "DEPENDENCY")
      field("  key", contextKey(requestKey))
      field("  isOptional", request.isOptional)
      field("  isSuspend", request.isSuspend)
      field("  rawSameType", bindingReferences(raw))
      field("  inContext", bindingReferences(inContext))
      field("  selected", bindingReferences(selected.bindings))
      field(
        "  duplicates",
        selected.duplicates.joinToString(prefix = "[", postfix = "]") { bindingReferences(it) },
      )
    }

    section("Candidate bindings")
    // A collection record registers its synthetic elements through the initialized lookup. Drain
    // those records too, without traversing ordinary dependencies or sealing the graph.
    var recordIndex = 0
    while (recordIndex < bindingRecords.size) {
      ProgressManager.checkCanceled()
      line("binding#${recordIndex + 1}:")
      writeBinding(bindingRecords[recordIndex])
      recordIndex++
    }
    return output.toString()
  }

  private fun validation() {
    val cached = cachedValidation
    if (cached == null) {
      field("state", "never validated")
      return
    }
    field("freshness", if (cached.stale) "stale" else "current")
    when (val result = cached.result) {
      is KaGraphValidationResult.Completed -> {
        field("state", "completed")
        field(
          "diagnostics",
          result.diagnostics.groupingBy { "${it.severity}:${it.id}" }.eachCount().toSortedMap(),
        )
        field("bindings", result.bindings.size)
        field("topologyAvailable", result.topology != null)
        field("reachableKeys", result.topology?.reachableKeys?.size ?: 0)
        field("suspendKeys", result.suspendKeys.size)
        field("parentReservations", result.parentReservations.size)
      }
      is KaGraphValidationResult.Incomplete -> field("state", "incomplete")
      is KaGraphValidationResult.InternalError -> {
        field("state", "internal error")
        // Exception messages and stacks can contain source text and user-specific paths.
        field("errorType", result.cause.javaClass.name)
      }
    }
  }

  private fun writeGraph(graph: KaGraphDeclaration) {
    val composition = session.graphComposition(queryContext, graph)
    line("graph ${graphId(graph.declarationId)}:")
    field("  declaration", location(graph.pointer))
    field("  isExtension", graph.isExtension)
    field("  scopes", classIds(graph.scopeKeys))
    field("  scopingAnnotations", annotations(graph.scopingAnnotations))
    field("  selfIds", classIds(graph.selfIds))
    field("  writtenSupertypeKeys", keys(graph.supertypeKeys))
    field("  writtenSupertypeDeclarations", graphReferences(graph.supertypeDeclarations))
    field("  selectedSupertypeKeys", keys(composition.supertypeKeys))
    field("  selectedSupertypeDeclarations", graphReferences(composition.supertypeDeclarations))
    val contributions =
      composition.contributions.sortedWith(
        compareBy({ it.classId?.asFqNameString().orEmpty() }, { pointerSortKey(it.pointer) })
      )
    field(
      "  selectedContributedInterfaces",
      contributions.joinToString(prefix = "[", postfix = "]") {
        "${classId(it.classId)} at ${location(it.pointer)}"
      },
    )
    field("  declaredBindingContainers", classIds(graph.bindingContainers))
    field("  includedBindingContainers", keys(graph.includedBindingContainers))
    field("  includedDependencies", keys(graph.includedDependencies))
    field("  excludes", classIds(graph.excludes))
    field("  writtenInjectedMemberOwners", classIds(graph.injectedMemberOwnerIds))
    field("  selectedInjectedMemberOwners", classIds(composition.injectedMemberOwnerIds))
    field("  writtenExtensionCreations", graphReferences(graph.extensionCreations))
    field("  selectedExtensionCreations", graphReferences(composition.extensionCreations))
    field("  writtenExtensionFactories", extensionFactories(graph.extensionFactories))
    field("  selectedExtensionFactories", extensionFactories(composition.extensionFactories))
    field("  runtimeCoroutinesAvailable", graph.runtimeCoroutinesAvailable)
    field("  daggerAnvilInteropEnabled", graph.daggerAnvilInteropEnabled)
  }

  private fun writeBinding(binding: KaBinding) {
    field("  kind", binding.javaClass.simpleName)
    field("  indexed", binding in indexedBindings)
    field("  key", contextKey(binding.contextualTypeKey))
    field("  declaration", location(binding.pointer))
    field("  origin", classId(binding.originClassId))
    field("  container", classId(binding.containerId))
    field("  ownerGraph", binding.ownerGraphId?.let(::graphId) ?: "none")
    field("  includedContainer", binding.includedContainerKey?.let(::key) ?: "none")
    field("  scope", binding.scope?.let(::annotation) ?: "none")
    field("  contributionScopes", classIds(binding.contributionScopes))
    field("  priority", binding.priority)
    field("  priorityFromAnvilRank", binding.priorityFromAnvilRank)
    field("  replaces", classIds(binding.replaces))
    field("  isGraphPrivate", binding.isGraphPrivate)
    field("  isSuspend", binding.isSuspend)
    field("  memberInjectionOwners", classIds(binding.memberInjectionOwnerIds))
    field(
      "  multibinding",
      binding.multibindingId?.let {
        "multibinding#${multibindingIds.getOrPut(it) { multibindingIds.size + 1 }}"
      } ?: "none",
    )
    field(
      "  mapKey",
      binding.mapKeyValue?.let { "mapKey#${mapKeyIds.getOrPut(it) { mapKeyIds.size + 1 }}" }
        ?: "none",
    )
    field(
      "  dependencies",
      binding.dependencies.joinToString(prefix = "[", postfix = "]", transform = ::contextKey),
    )
    when (binding) {
      is KaBinding.ConstructorInjected -> field("  isAssisted", binding.isAssisted)
      is KaBinding.Provided -> field("  isClassContribution", binding.isClassContribution)
      is KaBinding.Alias -> {
        field("  isClassContribution", binding.isClassContribution)
        field("  consumedKey", binding.consumedKey?.let(::contextKey) ?: "none")
      }
      is KaBinding.Multibinding -> {
        field("  allowEmpty", binding.allowEmpty)
        val elements =
          binding.sourceBindings.flatMap { sourceKey ->
            ProgressManager.checkCanceled()
            selectCandidates(sourceKey.canonicalContextKey()).bindings
          }
        field("  sourceBindings", bindingReferences(elements))
      }
      is KaBinding.BoundInstance -> {
        field("  isGraphInput", binding.isGraphInput)
        field("  isBindingContainerInput", binding.isBindingContainerInput)
        val ownerGraphs =
          binding.additionalOwnerGraphIds.sortedWith(
            compareBy({ it.classId?.asFqNameString().orEmpty() }, { it.file?.path.orEmpty() })
          )
        field(
          "  additionalOwnerGraphs",
          ownerGraphs.joinToString(prefix = "[", postfix = "]", transform = ::graphId),
        )
      }
      is KaBinding.AssistedFactory ->
        field("  targetKey", binding.targetTypeKey?.let(::key) ?: "none")
      is KaBinding.GraphDependency -> {
        field("  ownerKey", key(binding.ownerKey))
        field("  isParentScoped", binding.isParentScoped)
      }
      is KaBinding.GraphExtension -> {
        field("  ownerKey", key(binding.ownerKey))
        field("  isFactory", binding.isFactory)
      }
      is KaBinding.CustomWrapper -> field("  wrappedKey", contextKey(binding.wrappedContextKey))
      else -> Unit
    }
  }

  private fun selectCandidates(key: KaContextualTypeKey): SelectedCandidates {
    return selectedCandidates.getOrPut(key) {
      val duplicates = mutableListOf<List<KaBinding>>()
      val bindings = lookup.lookup(key) { _, candidates -> duplicates += candidates }
      SelectedCandidates(bindings.toList(), duplicates)
    }
  }

  private fun bindingReferences(bindings: Collection<KaBinding>): String {
    return bindings.sortedBy(::bindingSortKey).joinToString(prefix = "[", postfix = "]") { binding
      ->
      val number =
        bindingIds[binding]
          ?: (bindingRecords.size + 1).also {
            bindingIds[binding] = it
            bindingRecords += binding
          }
      "binding#$number"
    }
  }

  /** Sort with exact internal identities; only the separately redacted rendering is exported. */
  private fun bindingSortKey(binding: KaBinding): String =
    bindingSortKeys.getOrPut(binding) {
      listOf(
          binding.typeKey.render(short = false),
          binding.javaClass.simpleName,
          pointerSortKey(binding.pointer),
          binding.ownerGraphId?.classId?.asFqNameString().orEmpty(),
          binding.ownerGraphId?.file?.path.orEmpty(),
          binding.includedContainerKey?.render(short = false).orEmpty(),
          binding.originClassId?.asFqNameString().orEmpty(),
          binding.scope?.render(short = false).orEmpty(),
          binding.contributionScopes.map { it.asFqNameString() }.sorted().joinToString(),
          binding.priority.toString(),
          binding.priorityFromAnvilRank.toString(),
          binding.multibindingId.orEmpty(),
          binding.mapKeyValue.orEmpty(),
          binding.dependencies.joinToString { "${it.render(short = false)}:${it.hasDefault}" },
          binding.isGraphPrivate.toString(),
          binding.isSuspend.toString(),
        )
        .joinToString("\u0000")
    }

  private fun pointerSortKey(pointer: SmartPsiElementPointer<out PsiElement>): String {
    return "${pointer.virtualFile?.path.orEmpty()}\u0000${pointer.element?.textRange?.startOffset ?: -1}"
  }

  private fun contextKey(key: KaContextualTypeKey): String {
    val wrapped = key.wrappedType.render { type(it) }
    val qualifier = key.typeKey.qualifier?.let { " ${annotation(it)}" }.orEmpty()
    return "$wrapped$qualifier default=${key.hasDefault}"
  }

  private fun key(key: KaTypeKey): String {
    val qualifier = key.qualifier?.let { " ${annotation(it)}" }.orEmpty()
    return "${type(key.type)}$qualifier"
  }

  private fun keys(keys: Collection<KaTypeKey>): String =
    keys
      .sortedBy { it.render(short = false) }
      .joinToString(prefix = "[", postfix = "]", transform = ::key)

  private fun type(snapshot: KaTypeSnapshot): String {
    val id = typeIds.getOrPut(snapshot) { typeIds.size + 1 }
    return "${structuralType(snapshot)} [type#$id]"
  }

  /**
   * A renderer's type-use annotations/error text may contain literals, so do not copy that text.
   */
  private fun structuralType(snapshot: KaTypeSnapshot): String = buildString {
    val className = snapshot.classId?.let(::classId)
    if (className != null) {
      append(className)
    } else {
      val parameter = snapshot.renderedType.takeIf(SIMPLE_IDENTIFIER::matches)
      append(parameter ?: "<non-class type>")
    }
    if (snapshot.typeArguments.isNotEmpty()) {
      snapshot.typeArguments.joinTo(this, prefix = "<", postfix = ">", transform = ::structuralType)
    } else if ('<' in snapshot.renderedType) {
      append("<opaque arguments>")
    }
    if (snapshot.renderedType.endsWith('?')) append('?')
  }

  private fun annotations(values: Collection<KaAnnotationSnapshot>): String =
    values
      .sortedBy { it.render(short = false) }
      .joinToString(prefix = "[", postfix = "]", transform = ::annotation)

  private fun annotation(value: KaAnnotationSnapshot): String {
    val id = annotationIds.getOrPut(value) { annotationIds.size + 1 }
    val arguments =
      value.arguments.joinToString(prefix = "(", postfix = ")") { (name, argument) ->
        "${singleLine(name.asString())}=${annotationValue(argument)}"
      }
    return "@${classId(value.classId)}$arguments [annotation#$id]"
  }

  private fun annotationValue(value: KaAnnotationValueSnapshot): String =
    when (value) {
      is KaAnnotationValueSnapshot.Literal -> "<redacted>"
      is KaAnnotationValueSnapshot.KClassRef -> "${classId(value.classId)}::class"
      is KaAnnotationValueSnapshot.EnumEntry ->
        value.callableId?.asSingleFqName()?.asString()?.let(::singleLine) ?: "<unresolved enum>"
      is KaAnnotationValueSnapshot.Array ->
        value.values.joinToString(prefix = "[", postfix = "]", transform = ::annotationValue)
      is KaAnnotationValueSnapshot.Nested -> annotation(value.annotation)
      KaAnnotationValueSnapshot.Unsupported -> "<unsupported>"
    }

  private fun classId(classId: ClassId?): String =
    classId?.asFqNameString()?.let(::singleLine) ?: "none"

  private fun classIds(classIds: Collection<ClassId>): String =
    classIds.map(::classId).sorted().joinToString(prefix = "[", postfix = "]")

  private fun graphId(id: GraphDeclarationId): String = "${classId(id.classId)}@${file(id.file)}"

  private fun graphReference(reference: GraphReference): String =
    "${classId(reference.classId)}@${file(reference.file)}"

  private fun graphReferences(references: Collection<GraphReference>): String {
    val sorted =
      references.sortedWith(compareBy({ it.classId.asFqNameString() }, { it.file?.path.orEmpty() }))
    return sorted.joinToString(prefix = "[", postfix = "]", transform = ::graphReference)
  }

  private fun extensionFactories(factories: Collection<GraphExtensionFactoryAccessor>): String {
    val sorted =
      factories.sortedWith(
        compareBy({ it.factoryKey.render(short = false) }, { pointerSortKey(it.pointer) })
      )
    return sorted.joinToString(prefix = "[", postfix = "]") {
      "${key(it.factoryKey)} -> ${key(it.extensionKey)} (${graphReference(it.extension)}) at ${location(it.pointer)}"
    }
  }

  private fun location(pointer: SmartPsiElementPointer<out PsiElement>): String {
    val element = pointer.element
    val file = file(pointer.virtualFile)
    val document = element?.containingFile?.viewProvider?.document
    val line =
      if (element != null && document != null) document.getLineNumber(element.textOffset) + 1
      else null
    val name = (element as? PsiNamedElement)?.name?.let(::singleLine)
    val module = element?.let {
      module(KaModuleProvider.getModule(project, it, useSiteModule = null))
    }
    return listOfNotNull(module, file + (line?.let { ":$it" } ?: ""), name).joinToString(" ")
  }

  private fun file(file: VirtualFile?): String {
    if (file == null) return "<no file>"
    val id = fileIds.getOrPut(file) { fileIds.size + 1 }
    val basePath = project.basePath?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    val projectRelative =
      if (basePath != null && file.path.startsWith("$basePath/"))
        file.path.removePrefix("$basePath/")
      else null
    val contentRoot = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
    val contentRelative = contentRoot?.let { VfsUtilCore.getRelativePath(file, it, '/') }
    val path = projectRelative ?: contentRelative ?: "<external>/${file.name}"
    return "file#$id:${singleLine(path)}"
  }

  private fun module(module: KaModule): String {
    val id = moduleIds.getOrPut(module) { moduleIds.size + 1 }
    val name =
      when (module) {
        is KaSourceModule -> module.name
        is KaLibraryModule -> module.libraryName
        is KaLibrarySourceModule -> module.libraryName
        else -> module.javaClass.simpleName
      }
    // Module descriptions and library names are platform-defined and can be filesystem paths.
    val safeName = if ('/' in name || '\\' in name) "<redacted name>" else singleLine(name)
    return "module#$id:$safeName"
  }

  private fun section(name: String) {
    line()
    line("[$name]")
  }

  private fun field(name: String, value: Any?) = line("$name=$value")

  private fun line(value: String = "") {
    output.appendLine(value)
  }

  private companion object {
    val SIMPLE_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
  }
}

private fun singleLine(value: String): String = buildString {
  for (char in value) {
    when (char) {
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> append(if (char.isISOControl()) '?' else char)
    }
  }
}

private val debugOptionsJson = Json {
  encodeDefaults = true
  prettyPrint = true
}

private val safeCompilerVersion =
  Regex("[0-9]{1,3}\\.[0-9]{1,3}(?:\\.[0-9]{1,5})?(?:-(?:RC|Beta|M)[0-9]*|-dev-[0-9]+|-SNAPSHOT)?")

/**
 * Keep future free-text/path options private too, without maintaining a second Metro option list.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun safeOptions(options: MetroOptions): String {
  val serializer = MetroOptions.serializer()
  val descriptor = serializer.descriptor
  val encoded = debugOptionsJson.encodeToJsonElement(serializer, options).jsonObject
  val values =
    encoded
      .mapValues { (name, value) ->
        val optionDescriptor = descriptor.getElementDescriptor(descriptor.getElementIndex(name))
        when {
          value == JsonNull -> value
          name == "compilerVersion" &&
            options.compilerVersion?.matches(safeCompilerVersion) == true -> value
          isSafeOptionValue(optionDescriptor) -> sortOptionValue(value)
          else -> JsonPrimitive("<redacted>")
        }
      }
      .toMutableMap()
  values["reportsEnabled"] = JsonPrimitive(options.reportsEnabled)
  values["traceEnabled"] = JsonPrimitive(options.traceEnabled)
  return debugOptionsJson.encodeToString(JsonElement.serializer(), JsonObject(values.toSortedMap()))
}

@OptIn(ExperimentalSerializationApi::class)
private fun isSafeOptionValue(descriptor: SerialDescriptor): Boolean =
  when (descriptor.kind) {
    PrimitiveKind.BOOLEAN,
    PrimitiveKind.BYTE,
    PrimitiveKind.SHORT,
    PrimitiveKind.INT,
    PrimitiveKind.LONG,
    PrimitiveKind.FLOAT,
    PrimitiveKind.DOUBLE,
    SerialKind.ENUM -> true
    PrimitiveKind.STRING -> descriptor.serialName == "ClassId"
    StructureKind.LIST -> isSafeOptionValue(descriptor.getElementDescriptor(0))
    else -> false
  }

private fun sortOptionValue(value: JsonElement): JsonElement =
  when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { sortOptionValue(it.value) })
    is JsonArray -> JsonArray(value.map(::sortOptionValue).sortedBy { it.toString() })
    else -> value
  }
