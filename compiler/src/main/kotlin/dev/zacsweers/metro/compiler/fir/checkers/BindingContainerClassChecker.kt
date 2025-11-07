// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.BINDING_CONTAINER_ERROR
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.bindingContainerErrorMessage
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isBindingContainer
import dev.zacsweers.metro.compiler.fir.resolvedBindingContainersClassIds
import dev.zacsweers.metro.compiler.fir.resolvedClassId
import dev.zacsweers.metro.compiler.fir.resolvedIncludesClassIds
import dev.zacsweers.metro.compiler.fir.validateVisibility
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.isAnnotationClass
import org.jetbrains.kotlin.descriptors.isEnumEntry
import org.jetbrains.kotlin.descriptors.isObject
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousObject
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.constructors
import org.jetbrains.kotlin.fir.declarations.processAllDeclarations
import org.jetbrains.kotlin.fir.declarations.toAnnotationClass
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.declarations.utils.isEnumClass
import org.jetbrains.kotlin.fir.declarations.utils.isInner
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.declarations.utils.nameOrSpecialName
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.resolve.getSuperTypes
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal object BindingContainerClassChecker : FirClassChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    val source = declaration.source ?: return
    val session = context.session
    val classIds = session.classIds

    val bindingContainerAnno =
      declaration.annotationsIn(session, classIds.bindingContainerAnnotations).firstOrNull()

    if (bindingContainerAnno != null) {
      // Not valid on enums, enum entries, sealed classes, or companion objects
      fun report(type: String) {
        reporter.reportOn(
          bindingContainerAnno.source ?: source,
          BINDING_CONTAINER_ERROR,
          "$type cannot be annotated with @BindingContainer.",
        )
      }

      if (declaration.isEnumClass) {
        report("Enum classes")
        return
      } else if (declaration.classKind.isEnumEntry) {
        // This is prohibited by Metro's own annotation but a custom one may allow it as a target
        report("Enum entries")
        return
      } else if (declaration.classKind.isAnnotationClass) {
        report("Annotation classes")
        return
      } else if (declaration.classKind.isObject && declaration.status.isCompanion) {
        report("Companion objects")
        return
      } else if (declaration.modality == Modality.SEALED) {
        report("Sealed classes")
        return
      } else if (declaration.isInner) {
        report("Inner classes")
        return
      } else if (declaration is FirAnonymousObject) {
        report("Anonymous objects")
        return
      } else if (declaration.isLocal) {
        report("Local classes")
        return
      }
    }

    // Cannot be annotated with both bindingContainer and a graphlike
    val graphLikeAnno =
      declaration.annotationsIn(session, classIds.graphLikeAnnotations).firstOrNull()

    if (graphLikeAnno != null && bindingContainerAnno != null) {
      reporter.reportOn(
        graphLikeAnno.source ?: bindingContainerAnno.source ?: source,
        BINDING_CONTAINER_ERROR,
        "Classes cannot be annotated with both '@${
          bindingContainerAnno.toAnnotationClass(
            session
          )?.name
        }' and '@${graphLikeAnno.toAnnotationClass(session)?.name}'.",
      )
    }

    val isBindingContainer = bindingContainerAnno != null

    if (isBindingContainer) {
      // Binding containers can't extend other binding containers
      for (supertype in declaration.symbol.getSuperTypes(session)) {
        val supertypeClass = supertype.toClassSymbol(session) ?: continue
        if (supertypeClass.isBindingContainer(session)) {
          val directRef = declaration.superTypeRefs.firstOrNull { it.coneType == supertype }
          val source = directRef?.source ?: source
          reporter.reportOn(
            source,
            BINDING_CONTAINER_ERROR,
            "Binding containers cannot extend other binding containers, use `includes` instead. Container '${declaration.classId.asFqNameString()}' extends '${supertypeClass.classId.asFqNameString()}'.",
          )
        }
      }
    }

    val includesToCheck =
      bindingContainerAnno?.resolvedIncludesClassIds()
        ?: graphLikeAnno?.resolvedBindingContainersClassIds()
        ?: emptyList()
    val seen = mutableMapOf<ClassId, FirGetClassCall>()
    for (includedClassCall in includesToCheck) {
      val classId = includedClassCall.resolvedClassId() ?: continue
      val previous = seen.put(classId, includedClassCall)
      if (previous != null) {
        listOf(includedClassCall, previous).forEach {
          reporter.reportOn(
            it.source,
            BINDING_CONTAINER_ERROR,
            "Duplicate inclusion of binding container '${classId.asSingleFqName()}'.",
          )
        }
      }

      // includes can only be objects, interfaces/abstract classes, or simple non-generic classes
      // with a noarg constructor
      val target =
        includedClassCall.resolvedClassId()?.toLookupTag()?.toClassSymbol(session) ?: continue

      // Target must be a binding container
      if (!target.isBindingContainer(session)) {
        reporter.reportOn(
          includedClassCall.source,
          BINDING_CONTAINER_ERROR,
          "Included classes must be binding containers but '${target.classId.asSingleFqName()}' is not.",
        )
        continue
      }

      target.bindingContainerErrorMessage(session, alreadyCheckedAnnotation = true)?.let {
        bindingContainerErrorMessage ->
        reporter.reportOn(
          includedClassCall.source,
          BINDING_CONTAINER_ERROR,
          "Invalid binding container argument: $bindingContainerErrorMessage",
        )
        continue
      }

      if (target.classKind == ClassKind.CLASS && target.modality != Modality.ABSTRACT) {
        // open or final
        if (target.typeParameterSymbols.isNotEmpty()) {
          reporter.reportOn(
            includedClassCall.source ?: source,
            BINDING_CONTAINER_ERROR,
            "Included binding container '${target.classId.asSingleFqName()}' is generic and thus cannot be included via annotation. Remove its generics or declare it as a graph factory parameter instead.",
          )
          continue
        }

        val constructors = target.constructors(session)
        if (constructors.isNotEmpty()) {
          constructors
            .firstOrNull { it.valueParameterSymbols.isEmpty() }
            ?.let { constructor ->
              if (constructor.visibility != Visibilities.Public) {
                reporter.reportOn(
                  includedClassCall.source ?: source,
                  BINDING_CONTAINER_ERROR,
                  "Included binding container '${target.classId.asSingleFqName()}' does not have a public constructor.",
                )
                continue
              }
            }
            ?: run {
              reporter.reportOn(
                includedClassCall.source ?: source,
                BINDING_CONTAINER_ERROR,
                "Included binding container '${target.classId.asSingleFqName()}'s does not have a no-arg constructor and thus cannot be included via annotation. Add a no-arg constructor or declare it as a graph factory parameter instead.",
              )
              continue
            }
        }
      }
    }

    val isInterface = declaration.isInterface

    if (!isInterface && !declaration.classKind.isObject) {
      val isContributed = declaration.isAnnotatedWithAny(session, classIds.contributesToAnnotations)
      if (isContributed) {
        // Check for a single, no-arg constructor
        val constructors = declaration.constructors(session)
        if (constructors.isNotEmpty()) {
          val noArgConstructor =
            declaration.constructors(session).find { it.valueParameterSymbols.isEmpty() }
          if (noArgConstructor == null) {
            reporter.reportOn(
              source,
              BINDING_CONTAINER_ERROR,
              "Contributed binding containers must have a no-arg constructor.",
            )
          } else {
            noArgConstructor.validateVisibility(
              "Contributed binding container ${declaration.classId.asFqNameString()}'s no-arg constructor"
            ) {
              return
            }
          }
        }
      }
    }

    val isAbstract = isInterface || declaration.isAbstract

    // Check for no conflicting names, requires class-level
    val providerNames = mutableMapOf<Name, FirCallableSymbol<*>>()
    declaration.processAllDeclarations(session) { symbol ->
      if (symbol !is FirCallableSymbol<*>) return@processAllDeclarations
      if (symbol.isAnnotatedWithAny(session, classIds.providesAnnotations)) {

        if (isBindingContainer && isAbstract) {
          val type = if (declaration.isInterface) "interface" else "abstract class"
          reporter.reportOn(
            symbol.source,
            BINDING_CONTAINER_ERROR,
            "Abstract binding containers cannot contain @Provides callables ('${declaration.nameOrSpecialName}' is an $type). Either convert it to an object class or move these declarations to its companion object.",
          )
          return@processAllDeclarations
        }

        val previous = providerNames.put(symbol.name, symbol)
        if (previous != null) {
          listOf(symbol, previous).forEach {
            reporter.reportOn(
              it.source,
              BINDING_CONTAINER_ERROR,
              "Class ${declaration.nameOrSpecialName} cannot contain multiple @Provides callables with the same name '${symbol.name}'.",
            )
          }
        }
      }
    }
  }
}
