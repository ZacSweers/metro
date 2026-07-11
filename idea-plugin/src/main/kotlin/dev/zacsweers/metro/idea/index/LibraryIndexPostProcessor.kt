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
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.HintAvailability
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphNode
import dev.zacsweers.metro.idea.scopeAnnotation
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.createUseSiteVisibilityChecker
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
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
    val useSites = useSitesByModule()
    val fileIndex = ProjectFileIndex.getInstance(project)
    val allScope = GlobalSearchScope.allScope(project)
    val hints = mutableListOf<LibraryHint>()
    for (scopeId in scopeIds) {
      ProgressManager.checkCanceled()
      val hintFqName = MetroHints.hintCallableId(scopeId).asSingleFqName().asString()
      for (hintFunction in KotlinTopLevelFunctionFqnNameIndex[hintFqName, project, allScope]) {
        val virtualFile = hintFunction.containingFile.virtualFile ?: continue
        // Project-source contributions are already covered by the annotation sweeps; hints only
        // exist as generated declarations in binaries.
        if (fileIndex.isInContent(virtualFile)) continue
        hints += LibraryHint(scopeId, hintFunction)
      }
    }
    if (hints.isEmpty()) return

    val visibleModulesByHint = visibleModulesByHint(hints, useSites)
    for (hint in hints) {
      val visibleModules = visibleModulesByHint.getValue(hint.function)
      if (visibleModules.isEmpty()) continue
      val isNonPublic =
        hint.function.hasModifier(KtTokens.INTERNAL_KEYWORD) ||
          hint.function.hasModifier(KtTokens.PRIVATE_KEYWORD)
      val hintAvailability = if (isNonPublic) HintAvailability(visibleModules) else null
      val context = useSites.getValue(visibleModules.first())
      processLibraryHint(hint.function, hint.scopeId, context, hintAvailability)
    }
  }

  private fun useSitesByModule(): Map<KaModule, KtElement> {
    val result = linkedMapOf<KaModule, KtElement>()

    fun addUseSite(element: PsiElement?) {
      if (element !is KtElement) return
      val module = KaModuleProvider.getModule(project, element, useSiteModule = null)
      result.putIfAbsent(module, element)
    }

    graphs.forEach { addUseSite(it.pointer.element) }
    contributions.forEach { addUseSite(it.pointer.element) }
    consumers.forEach { addUseSite(it.pointer.element) }
    return result
  }

  private fun processLibraryHint(
    hintFunction: KtNamedFunction,
    scopeId: ClassId,
    context: KtElement,
    hintAvailability: HintAvailability?,
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
          hintAvailability,
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
            hintAvailability = hintAvailability,
          )
      }
      // Generated members hold the machine-readable binding declarations that annotation
      // arguments in binaries can't carry, like binding<T>() type args. Contribution-provider
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
                hintAvailability = hintAvailability,
              )
          }
        }
      }
    }
  }

  /** Modules from which Kotlin considers each [LibraryHint] visible. */
  @OptIn(KaExperimentalApi::class)
  private fun visibleModulesByHint(
    hints: List<LibraryHint>,
    useSites: Map<KaModule, KtElement>,
  ): Map<KtNamedFunction, Set<KaModule>> {
    val result = hints.associateTo(linkedMapOf()) { it.function to linkedSetOf<KaModule>() }
    for ((module, useSite) in useSites) {
      ProgressManager.checkCanceled()
      analyze(useSite) {
        val checker =
          createUseSiteVisibilityChecker(
            useSiteFile = useSite.containingKtFile.symbol,
            receiverExpression = null,
            position = useSite,
          )
        for (hint in hints) {
          val hintSymbol = hint.function.symbol as? KaNamedFunctionSymbol ?: continue
          if (checker.isVisible(hintSymbol)) {
            result.getValue(hint.function) += module
          }
        }
      }
    }
    return result
  }

  /**
   * Demand-driven resolution of constructor-injected classes from compiled dependencies: the
   * annotation sweeps only cover project sources, but inject annotations survive in library
   * metadata. For each consumer key with no project-source binding, checks whether the consumed
   * class itself is injectable and synthesizes a binding entry targeting the library declaration.
   */
  private fun resolveLibraryInjectBindings() {
    val bindingKeys = bindings.mapToSet { it.typeKey }
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
          KaBinding.ConstructorInjected(
            pointerManager.createSmartPsiElementPointer(psi),
            key,
            scopeAnnotation(classSymbol, options),
            classSymbol.name.asString(),
            dependencies = injectClassDependencyKeys(classSymbol, options),
          )
      }
    }
  }

  private fun ptr(element: KtElement): SmartPsiElementPointer<KtElement> {
    return pointerManager.createSmartPsiElementPointer(element)
  }

  private class LibraryHint(val scopeId: ClassId, val function: KtNamedFunction)
}
