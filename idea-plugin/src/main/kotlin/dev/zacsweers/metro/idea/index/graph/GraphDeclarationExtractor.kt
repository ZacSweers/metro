// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.graph

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.idea.annotationScopeKeys
import dev.zacsweers.metro.idea.checkCanceledEvery
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.implicitSingleInAnnotation
import dev.zacsweers.metro.idea.index.FactoryInputEntry
import dev.zacsweers.metro.idea.index.callableBindingView
import dev.zacsweers.metro.idea.index.classListArgument
import dev.zacsweers.metro.idea.index.graphFactoryInputs
import dev.zacsweers.metro.idea.index.typeKey
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.GraphExtensionFactoryAccessor
import dev.zacsweers.metro.idea.model.GraphReference
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.scopeAnnotations
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

/** Reads one graph's declared shape and leaves shared factory-input merging to its caller. */
internal class GraphDeclarationExtractor(
  private val options: MetroOptions,
  private val pointerManager: SmartPointerManager,
  private val graphMembers: GraphMemberExtractor,
  private val consumers: MutableList<ConsumerEntry>,
  private val onFactoryInput: (FactoryInputEntry) -> Unit,
  private val onDeclarationFile: (PsiFile) -> Unit,
  private val recordAnnotations: KaSession.(KaAnnotated, PsiElement?) -> Unit,
  /** Source annotation sweeps already capture these parameters; binary callers supply a sink. */
  private val onInstanceBinding: ((KaBinding.BoundInstance) -> Unit)? = null,
) {
  private var cancellationWorkIndex = 0

  private fun checkCanceled() {
    checkCanceledEvery(cancellationWorkIndex++)
  }

  /** Completes all symbol and type reads inside the supplied Analysis API session. */
  fun extract(session: KaSession, ktClass: KtClassOrObject): KaGraphDeclaration? =
    with(session) {
      val classSymbol = ktClass.symbol as? KaNamedClassSymbol ?: return@with null
      val graphAnnotations =
        classSymbol.annotations.filter { it.classId in options.dependencyGraphAnnotations }
      val extensionAnnotations =
        classSymbol.annotations.filter { it.classId in options.graphExtensionAnnotations }
      val annotations = graphAnnotations + extensionAnnotations
      if (annotations.isEmpty()) return@with null
      recordAnnotations(this, classSymbol, ktClass)
      val scopeKeys = annotations.flatMapToSet { annotationScopeKeys(it) }
      val excludes = annotations.flatMapToSet { classListArgument(it, "excludes") }
      val containerIds = annotations.flatMapToSet { classListArgument(it, "bindingContainers") }
      val graphClassId = ktClass.getClassId()
      val graphPointer = pointerManager.createSmartPsiElementPointer(ktClass)
      val graphId = GraphDeclarationId(graphClassId, graphPointer.virtualFile)
      val factoryAnnotations =
        options.dependencyGraphFactoryAnnotations + options.graphExtensionFactoryAnnotations
      val nestedClassIds = mutableSetOf<ClassId>()
      val includedBindingContainers = mutableSetOf<KaTypeKey>()
      val includedDependencies = mutableSetOf<KaTypeKey>()
      val extensionCreations = mutableSetOf<GraphReference>()
      val extensionFactories = mutableListOf<GraphExtensionFactoryAccessor>()
      val injectedMemberOwnerIds = mutableSetOf<ClassId>()
      val memberTarget =
        GraphMemberTarget(
          graphId,
          consumers,
          extensionCreations,
          extensionFactories,
          injectedMemberOwnerIds,
          factoryContext = classSymbol.defaultType as? KaClassType,
        )
      indexClassLiteralContainers(this, containerIds, memberTarget)

      for (member in ktClass.declarations) {
        checkCanceled()
        when (member) {
          is KtClassOrObject -> {
            val memberClassId = member.getClassId() ?: continue
            nestedClassIds += memberClassId
            val memberSymbol = member.symbol as? KaClassSymbol ?: continue
            val isBinaryCompanion =
              memberSymbol.classKind == KaClassKind.COMPANION_OBJECT &&
                memberSymbol.origin == KaSymbolOrigin.LIBRARY
            if (isBinaryCompanion) {
              graphMembers.indexCompanionBindings(this, memberSymbol, memberTarget)
              continue
            }
            if (!memberSymbol.hasAnyAnnotation(factoryAnnotations)) continue
            val graphInputs =
              memberSymbol.graphFactoryInputs(
                this,
                options,
                pointerManager,
                graphId,
                includeInstanceBindings = onInstanceBinding != null,
              )
            for (binding in graphInputs.instanceBindings) onInstanceBinding?.invoke(binding)
            graphInputs.cacheDependencies.forEach(onDeclarationFile)
            includedBindingContainers += graphInputs.bindingContainers
            includedDependencies += graphInputs.graphDependencies
            for (input in graphInputs.inputs) {
              checkCanceled()
              onFactoryInput(input)
            }
          }
          is KtCallableDeclaration -> {
            if (member !is KtNamedFunction && member !is KtProperty) continue
            val symbol = member.symbol as? KaCallableSymbol ?: continue
            val view = callableBindingView(symbol)
            graphMembers.indexDeclaredMember(this, view, member, memberTarget)
          }
          else -> {}
        }
      }

      // Supertype members merge into the graph, mirroring the compiler. Their accessors become
      // this graph's consumers and their class ids gate their providers' membership.
      val supertypeIds = mutableSetOf<ClassId>()
      val supertypeKeys = linkedSetOf<KaTypeKey>()
      val supertypeDeclarations = linkedSetOf<GraphReference>()
      // FIR may already expose generated contribution supertypes. Only source-written parents
      // belong to this unconditional surface; implicit contributions are selected after merging.
      val writtenSupertypes =
        ktClass.superTypeListEntries.asSequence().flatMap { entry ->
          val type = entry.typeReference?.type?.fullyExpandedType as? KaClassType
          if (type == null) emptySequence() else sequenceOf(type) + type.allSupertypes
        }
      for (superType in writtenSupertypes) {
        checkCanceled()
        if (superType.isAnyType) continue
        val classType = superType as? KaClassType ?: continue
        val superClass = classType.symbol as? KaNamedClassSymbol ?: continue
        val superClassId = superClass.classId ?: continue
        if (!supertypeKeys.add(typeKey(classType, null))) continue
        supertypeIds += superClassId
        supertypeDeclarations += classType.graphReference()
        superClass.psi?.containingFile?.let(onDeclarationFile)
        graphMembers.indexSupertypeMembers(this, classType, memberTarget)
      }

      // Each aggregation scope implicitly conveys @SingleIn(scope) on the graph, alongside any
      // explicitly declared scope annotations
      val scopingAnnotations = buildSet {
        scopeKeys.mapTo(this, ::implicitSingleInAnnotation)
        addAll(scopeAnnotations(classSymbol, options))
      }

      KaGraphDeclaration(
        graphPointer,
        scopeKeys,
        classId = graphClassId,
        excludes = excludes,
        bindingContainers = containerIds,
        includedBindingContainers = includedBindingContainers,
        includedDependencies = includedDependencies,
        isExtension = graphAnnotations.isEmpty(),
        selfIds = setOfNotNull(graphClassId) + nestedClassIds,
        supertypeIds = supertypeIds,
        injectedMemberOwnerIds = injectedMemberOwnerIds,
        daggerAnvilInteropEnabled = options.enableDaggerAnvilInterop,
        extensionCreations = extensionCreations,
        runtimeCoroutinesAvailable = findClass(MetroClassIds.suspendDoubleCheck) != null,
        scopingAnnotations = scopingAnnotations,
        supertypeKeys = supertypeKeys,
        supertypeDeclarations = supertypeDeclarations,
        extensionFactories = extensionFactories,
        defaultImplementations = memberTarget.defaultImplementations,
      )
    }

  /** Source containers are swept separately; their includes can still lead to binary providers. */
  private fun indexClassLiteralContainers(
    session: KaSession,
    containers: Set<ClassId>,
    target: GraphMemberTarget,
  ) =
    with(session) {
      val pending = ArrayDeque(containers)
      val visited = hashSetOf<ClassId>()
      while (pending.isNotEmpty()) {
        checkCanceled()
        val classId = pending.removeFirst()
        if (!visited.add(classId)) continue
        val symbol = findClass(classId) as? KaNamedClassSymbol ?: continue
        symbol.psi?.containingFile?.let(onDeclarationFile)
        recordAnnotations(this, symbol, symbol.psi)
        for (annotation in symbol.annotations) {
          if (annotation.classId in options.bindingContainerAnnotations) {
            pending.addAll(classListArgument(annotation, "includes"))
          }
        }
        val type = symbol.defaultType as? KaClassType ?: continue
        graphMembers.indexContainerBindings(this, type, target)
      }
    }
}
