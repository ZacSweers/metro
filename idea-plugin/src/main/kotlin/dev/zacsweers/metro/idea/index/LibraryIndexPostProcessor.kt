// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import dev.zacsweers.metro.compiler.MetroHints
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.idea.classLiteralClassId
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.model.BindingKind
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphNode
import dev.zacsweers.metro.idea.scopeAnnotation
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.stubindex.KotlinTopLevelFunctionFqnNameIndex
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Cross-file passes that need the merged project shard: compiled contribution hints and
 * demand-driven library constructor-injection bindings.
 */
internal class LibraryIndexPostProcessor(
  private val project: Project,
  private val options: MetroOptions,
  private val bindings: MutableList<KaBinding>,
  private val consumers: List<ConsumerEntry>,
  private val graphs: List<KaGraphNode>,
  private val contributions: MutableList<ContributionEntry>,
) {
  private val pointerManager = SmartPointerManager.getInstance(project)
  private val processedLibraryContributionScopes = HashMap<KtClassOrObject, MutableSet<ClassId>>()

  fun postProcess() {
    scanLibraryContributionHints()
    resolveLibraryInjectBindings()
  }

  /**
   * Discovers contributions from compiled dependencies the way the compiler does for classpath
   * merging (`ContributionHintFirGenerator` / `ContributedInterfaceSupertypeGenerator`): scanning
   * top-level hint functions in the `metro.hints` package, named after the scope class, whose
   * single parameter type is the contributing class.
   */
  private fun scanLibraryContributionHints() {
    val scopeIds = buildSet {
      graphs.forEach { addAll(it.scopeKeys) }
      contributions.forEach { addAll(it.scopeKeys) }
    }
    if (scopeIds.isEmpty()) return
    // Analyze from a project-source use site: library-use-site sessions deserialize annotation
    // values differently (e.g. unresolved class literal ids).
    val context =
      graphs.firstNotNullOfOrNull { it.pointer.element }
        ?: contributions.firstNotNullOfOrNull { it.pointer.element }
        ?: return
    val useSiteModulesByScope = useSiteModulesByScope()
    val fileIndex = ProjectFileIndex.getInstance(project)
    val allScope = GlobalSearchScope.allScope(project)
    for (scopeId in scopeIds) {
      ProgressManager.checkCanceled()
      val hintFqName = MetroHints.hintCallableId(scopeId).asSingleFqName().asString()
      for (hintFunction in KotlinTopLevelFunctionFqnNameIndex[hintFqName, project, allScope]) {
        val virtualFile = hintFunction.containingFile.virtualFile ?: continue
        // Project-source contributions are already covered by the annotation sweeps; hints only
        // exist as generated declarations in binaries.
        if (fileIndex.isInContent(virtualFile)) continue
        // Mirrors the compiler's visibility filtering: internal hints are visible only from their
        // own module and formal friend/associated compilations.
        if (
          hintFunction.hasModifier(KtTokens.INTERNAL_KEYWORD) &&
            !isVisibleInternalHint(hintFunction, useSiteModulesByScope[scopeId].orEmpty())
        ) {
          continue
        }
        processLibraryHint(hintFunction, scopeId, context)
      }
    }
  }

  private fun useSiteModulesByScope(): Map<ClassId, Set<KaModule>> {
    val result = mutableMapOf<ClassId, MutableSet<KaModule>>()

    fun addUseSite(element: PsiElement?, scopeKeys: Set<ClassId>) {
      if (element !is KtElement) return
      val module = KaModuleProvider.getModule(project, element, useSiteModule = null)
      for (scopeKey in scopeKeys) {
        result.getOrPut(scopeKey) { mutableSetOf() } += module
      }
    }

    graphs.forEach { addUseSite(it.pointer.element, it.scopeKeys) }
    contributions.forEach { addUseSite(it.pointer.element, it.scopeKeys) }
    return result
  }

  private fun processLibraryHint(
    hintFunction: KtNamedFunction,
    scopeId: ClassId,
    context: KtElement,
  ) {
    analyze(context) {
      val symbol = hintFunction.symbol as? KaNamedFunctionSymbol ?: return@analyze
      val contributedType =
        symbol.valueParameters.singleOrNull()?.returnType?.fullyExpandedType ?: return@analyze
      val classSymbol = (contributedType as? KaClassType)?.symbol as? KaNamedClassSymbol
      val ktClass = classSymbol?.psi as? KtClassOrObject ?: return@analyze
      val processedScopes = processedLibraryContributionScopes.getOrPut(ktClass) { mutableSetOf() }
      if (!processedScopes.add(scopeId)) return@analyze

      // Contribution-provider containers carry @Origin pointing back at the real contributing
      // class; prefer it for presentation and as the contribution anchor.
      val originClassId =
        classSymbol.annotations
          .firstOrNull { it.classId in options.originAnnotations }
          ?.arguments
          ?.firstOrNull { it.name.asString() == "value" }
          ?.let { classLiteralClassId(it.expression) }
      val originPsi = originClassId?.let { findClass(it)?.psi as? KtClassOrObject }
      val contributionAnchor = originPsi ?: ktClass

      val contributedClassId = originClassId ?: ktClass.getClassId()
      contributions +=
        ContributionEntry(
          pointerManager.createSmartPsiElementPointer(contributionAnchor),
          setOf(scopeId),
          contributedClassId,
        )
      val classReplaces =
        classSymbol.annotations
          .filter { it.classId in options.allContributesAnnotations }
          .flatMapToSet { classListArgument(it, "replaces") }
      for (data in bindingData(ktClass, options)) {
        bindings +=
          data.toKaBinding(
            ptr(ktClass),
            originClassId = data.originClassId ?: contributedClassId,
            replaces = data.replaces + classReplaces,
            contributionScopes = data.contributionScopes.ifEmpty { setOf(scopeId) },
          )
      }
      // Generated members hold the machine-readable binding declarations that annotation
      // arguments in binaries can't carry (e.g. binding<T>() type args): contribution-provider
      // containers hold @Provides members directly, and contributed classes hold nested
      // MetroContribution interfaces with @Binds members.
      val memberHolders = listOf(ktClass) + ktClass.declarations.filterIsInstance<KtClassOrObject>()
      for (holder in memberHolders) {
        for (member in holder.declarations.filterIsInstance<KtCallableDeclaration>()) {
          for (data in bindingData(member, options)) {
            bindings +=
              data.toKaBinding(
                ptr(member),
                originClassId = contributedClassId,
                implementationName =
                  data.implementationName ?: originClassId?.shortClassName?.asString(),
                replaces = classReplaces,
                contributionScopes = setOf(scopeId),
              )
          }
        }
      }
    }
  }

  /** Whether an internal [hintFunction] is visible from a formal friend/associated use site. */
  private fun isVisibleInternalHint(
    hintFunction: KtNamedFunction,
    useSiteModules: Set<KaModule>,
  ): Boolean {
    for (useSiteModule in useSiteModules) {
      val hintModule = KaModuleProvider.getModule(project, hintFunction, useSiteModule)
      if (hintModule == useSiteModule) return true
      if (useSiteModule is KaSourceModule && hintModule in useSiteModule.directFriendDependencies) {
        return true
      }
    }
    return false
  }

  /**
   * Demand-driven resolution of constructor-injected classes from compiled dependencies: the
   * annotation sweeps only cover project sources, but inject annotations survive in library
   * metadata. For each consumer key with no project-source binding, checks whether the consumed
   * class itself is injectable and synthesizes a binding entry targeting the library declaration.
   */
  private fun resolveLibraryInjectBindings() {
    val bindingKeys = bindings.mapToSet { it.key }
    val unresolved =
      consumers
        .filter {
          it.multibindingId == null &&
            it.typeClassId != null &&
            it.key.qualifier == null &&
            it.key !in bindingKeys
        }
        .groupBy { it.key }
    if (unresolved.isEmpty()) return

    val fileIndex = ProjectFileIndex.getInstance(project)
    for ((key, sites) in unresolved) {
      ProgressManager.checkCanceled()
      val context = sites.firstNotNullOfOrNull { it.pointer.element } ?: continue
      analyze(context) {
        val classSymbol =
          findClass(sites.first().typeClassId!!) as? KaNamedClassSymbol ?: return@analyze
        val psi = classSymbol.psi ?: return@analyze
        // Project sources were already swept; finding nothing there was authoritative
        val virtualFile = psi.containingFile?.virtualFile ?: return@analyze
        if (fileIndex.isInContent(virtualFile)) return@analyze
        if (classSymbol.classKind != KaClassKind.CLASS) return@analyze

        val constructors = classSymbol.memberScope.constructors.toList()
        val hasInject =
          classSymbol.hasAnyAnnotation(options.injectAnnotations) ||
            constructors.any { it.hasAnyAnnotation(options.injectAnnotations) }
        val isAssisted =
          classSymbol.hasAnyAnnotation(options.assistedInjectAnnotations) ||
            constructors.any { it.hasAnyAnnotation(options.assistedInjectAnnotations) }
        if (!hasInject || isAssisted) return@analyze

        bindings +=
          KaBinding(
            pointerManager.createSmartPsiElementPointer(psi),
            key,
            BindingKind.INJECT,
            scopeAnnotation(classSymbol, options),
            classSymbol.name.asString(),
          )
      }
    }
  }

  private fun ptr(element: KtElement): SmartPsiElementPointer<KtElement> {
    return pointerManager.createSmartPsiElementPointer(element)
  }
}
