// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.computeMetroDefault
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.generators.collectAbstractFunctions
import dev.zacsweers.metro.compiler.isPlatformType
import dev.zacsweers.metro.compiler.mapToArray
import dev.zacsweers.metro.compiler.memoized
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.GuiceSymbols
import dev.zacsweers.metro.compiler.symbols.Symbols
import java.util.Objects
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.classKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaredMemberScope
import org.jetbrains.kotlin.fir.analysis.checkers.getAllowedAnnotationTargets
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.declarations.evaluateAs
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.getDeprecationsProvider
import org.jetbrains.kotlin.fir.declarations.getTargetType
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.declarations.utils.isFinal
import org.jetbrains.kotlin.fir.declarations.utils.isInner
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.declarations.utils.isOpen
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.deserialization.toQualifiedPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirCall
import org.jetbrains.kotlin.fir.expressions.FirClassReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildEnumEntryDeserializedAccessExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildLiteralExpression
import org.jetbrains.kotlin.fir.expressions.unexpandedClassId
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension.TypeResolveService
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.QualifierPartBuilder
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.references.impl.FirSimpleNamedReference
import org.jetbrains.kotlin.fir.references.toResolvedPropertySymbol
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.renderer.ConeIdRendererForDiagnostics
import org.jetbrains.kotlin.fir.renderer.ConeIdShortRenderer
import org.jetbrains.kotlin.fir.renderer.ConeTypeRendererForReadability
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.getSuperTypes
import org.jetbrains.kotlin.fir.resolve.lookupSuperTypes
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.scopes.processAllCallables
import org.jetbrains.kotlin.fir.scopes.processAllClassifiers
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousObjectSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirBackingFieldSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertyAccessorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildUserTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.types.isResolved
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.types.ConstantValueKind

internal fun FirBasedSymbol<*>.isAnnotatedInject(session: FirSession): Boolean {
  return isAnnotatedWithAny(session, session.classIds.injectAnnotations)
}

internal fun FirBasedSymbol<*>.isBinds(session: FirSession): Boolean {
  return isAnnotatedWithAny(session, session.classIds.bindsAnnotations)
}

internal fun FirBasedSymbol<*>.isDependencyGraph(session: FirSession): Boolean {
  return isAnnotatedWithAny(session, session.classIds.dependencyGraphAnnotations)
}

internal fun FirBasedSymbol<*>.isGraphFactory(session: FirSession): Boolean {
  return isAnnotatedWithAny(session, session.classIds.dependencyGraphFactoryAnnotations)
}

internal fun FirAnnotationContainer.isAnnotatedWithAny(
  session: FirSession,
  names: Collection<ClassId>,
): Boolean {
  return names.any { hasAnnotation(it, session) }
}

internal fun FirAnnotationContainer.annotationsIn(
  session: FirSession,
  names: Set<ClassId>,
): Sequence<FirAnnotation> {
  return annotations.annotationsIn(session, names)
}

internal fun List<FirAnnotation>.annotationsIn(
  session: FirSession,
  names: Set<ClassId>,
): Sequence<FirAnnotation> {
  return asSequence().filter { it.toAnnotationClassIdSafe(session) in names }
}

internal fun FirBasedSymbol<*>.annotationsIn(
  session: FirSession,
  names: Set<ClassId>,
): Sequence<FirAnnotation> {
  return resolvedCompilerAnnotationsWithClassIds
    .asSequence()
    .filter { it.isResolved }
    .flatMap {
      val classId =
        it.toAnnotationClassIdSafe(session) ?: return@flatMap emptySequence<FirAnnotation>()
      if (classId in names) {
        if (classId in session.classIds.allRepeatableContributesAnnotationsContainers) {
          it.flattenRepeatedAnnotations()
        } else {
          sequenceOf(it)
        }
      } else {
        emptySequence()
      }
    }
}

/**
 * Not typed as `FirArrayLiteral` or `FirCollectionLiteral` due to
 * https://github.com/ZacSweers/metro/issues/1217
 */
// TODO after min kotlin 2.3.20 we can remove this for FirCollectionLiteral
private fun FirAnnotation.arrayArgument(name: Name, index: Int): FirCall? {
  return argumentAsOrNull<FirCall>(name, index)
}

/** @see [dev.zacsweers.metro.compiler.ClassIds.allRepeatableContributesAnnotationsContainers] */
internal fun FirAnnotation.flattenRepeatedAnnotations(): Sequence<FirAnnotation> {
  return arrayArgument(StandardNames.DEFAULT_VALUE_PARAMETER, 0)
    ?.arguments
    ?.asSequence()
    ?.filterIsInstance<FirAnnotation>()
    .orEmpty()
}

internal fun FirBasedSymbol<*>.isAnnotatedWithAny(
  session: FirSession,
  names: Set<ClassId>,
): Boolean {
  return resolvedCompilerAnnotationsWithClassIds
    .filter { it.isResolved }
    .any { it.toAnnotationClassIdSafe(session) in names }
}

internal fun List<FirAnnotation>.isAnnotatedWithAny(
  session: FirSession,
  names: Set<ClassId>,
): Boolean {
  return annotationsIn(session, names).any()
}

internal inline fun FirMemberDeclaration.checkVisibility(
  allowProtected: Boolean = false,
  onError: (source: KtSourceElement?, allowedVisibilities: String) -> Unit,
) {
  visibility.checkVisibility(source, allowProtected, onError)
}

internal inline fun FirCallableSymbol<*>.checkVisibility(
  allowProtected: Boolean = false,
  onError: (source: KtSourceElement?, allowedVisibilities: String) -> Unit,
) {
  visibility.checkVisibility(source, allowProtected, onError)
}

internal inline fun Visibility.checkVisibility(
  source: KtSourceElement?,
  allowProtected: Boolean = false,
  onError: (source: KtSourceElement?, allowedVisibilities: String) -> Unit,
) {
  // TODO what about expect/actual/protected
  when (this) {
    Visibilities.Public,
    Visibilities.Internal -> {
      // These are fine
      // TODO what about across modules? Is internal really ok? Or PublishedApi?
    }

    Visibilities.Protected -> {
      if (!allowProtected) {
        onError(source, "public or internal")
      }
    }

    else -> {
      onError(source, if (allowProtected) "public, internal or protected" else "public or internal")
    }
  }
}

@OptIn(DirectDeclarationsAccess::class)
internal fun FirClassSymbol<*>.callableDeclarations(
  session: FirSession,
  includeSelf: Boolean,
  includeAncestors: Boolean,
  yieldAncestorsFirst: Boolean = true,
): Sequence<FirCallableSymbol<*>> {
  return sequence {
    val declaredMembers =
      if (includeSelf) {
        declarationSymbols.asSequence().filterIsInstance<FirCallableSymbol<*>>().filterNot {
          it is FirConstructorSymbol
        }
      } else {
        emptySequence()
      }

    if (includeSelf && !yieldAncestorsFirst) {
      yieldAll(declaredMembers)
    }
    if (includeAncestors) {
      val superTypes = getSuperTypes(session)
      val superTypesToCheck = if (yieldAncestorsFirst) superTypes.asReversed() else superTypes
      for (superType in superTypesToCheck.mapNotNull { it.toClassSymbol(session) }) {
        yieldAll(
          // If we're recursing up, we no longer want to include ancestors because we're handling
          // that here
          superType.callableDeclarations(
            session = session,
            includeSelf = true,
            includeAncestors = false,
            yieldAncestorsFirst = yieldAncestorsFirst,
          )
        )
      }
    }
    if (includeSelf && yieldAncestorsFirst) {
      yieldAll(declaredMembers)
    }
  }
}

context(context: CheckerContext)
internal inline fun FirClass.singleAbstractFunction(
  session: FirSession,
  reporter: DiagnosticReporter,
  type: String,
  allowProtected: Boolean = false,
  onError: () -> Nothing,
): FirNamedFunctionSymbol {
  val abstractFunctions = symbol.collectAbstractFunctions(session).orEmpty()
  if (abstractFunctions.size != 1) {
    if (abstractFunctions.isEmpty()) {
      reporter.reportOn(
        source,
        MetroDiagnostics.FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION,
        type,
        "none",
      )
    } else {
      // Report each function
      for (abstractFunction in abstractFunctions) {
        reporter.reportOn(
          abstractFunction.source,
          MetroDiagnostics.FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION,
          type,
          abstractFunctions.size.toString(),
        )
      }
    }
    onError()
  }

  val function = abstractFunctions.single()
  function.checkVisibility(allowProtected) { source, allowedVisibilities ->
    reporter.reportOn(
      source,
      MetroDiagnostics.METRO_DECLARATION_VISIBILITY_ERROR,
      "$type classes' single abstract functions",
      allowedVisibilities,
    )
    onError()
  }
  return function
}

/**
 * Computes a hash key for this annotation instance composed of its underlying type and value
 * arguments.
 */
internal fun FirAnnotationCall.computeAnnotationHash(
  session: FirSession,
  typeResolver: TypeResolveService? = null,
): Int {
  return Objects.hash(
    toAnnotationClassIdSafe(session),
    arguments
      .map { arg ->
        when (arg) {
          is FirLiteralExpression -> arg.value
          is FirGetClassCall -> {
            typeResolver?.let { arg.resolvedArgumentConeKotlinType(it)?.classId }
              ?: run {
                val argument = arg.argument
                if (argument is FirResolvedQualifier) {
                  argument.classId
                } else {
                  argument.resolvedType.classId
                }
              }
          }
          // Enum entry reference
          is FirPropertyAccessExpression -> {
            arg.calleeReference
              .toResolvedPropertySymbol()
              ?.resolvedReceiverTypeRef
              ?.coneType
              ?.classId
          }

          else -> {
            reportCompilerBug(
              "Unexpected annotation argument type: ${arg::class.java} - ${arg.render()}"
            )
          }
        }
      }
      .toTypedArray()
      .contentDeepHashCode(),
  )
}

context(context: CheckerContext, reporter: DiagnosticReporter)
internal inline fun FirClassSymbol<*>.findInjectConstructor(
  session: FirSession,
  checkClass: Boolean,
  classIds: Set<ClassId>,
  onError: () -> Nothing,
): FirInjectConstructor? {
  val constructorInjections =
    findInjectConstructorsImpl(
      session = session,
      annotationClassIds = classIds,
      checkClass = checkClass,
    )
  return when (constructorInjections.size) {
    0 -> null
    1 -> {
      constructorInjections[0].also { (injectAnno, _, isOnClass, ctorCount) ->
        if (!isOnClass) {
          val warnOnInjectAnnotationPlacement =
            session.metroFirBuiltIns.options.warnOnInjectAnnotationPlacement
          if (warnOnInjectAnnotationPlacement && ctorCount == 1) {
            if (KotlinTarget.CLASS in injectAnno.getAllowedAnnotationTargets(session)) {
              reporter.reportOn(injectAnno.source, MetroDiagnostics.SUGGEST_CLASS_INJECTION)
            }
          }
        }
      }
    }

    else -> {
      for (constructorInjection in constructorInjections) {
        reporter.reportOn(
          constructorInjection.annotation.source,
          MetroDiagnostics.CANNOT_HAVE_MULTIPLE_INJECTED_CONSTRUCTORS,
        )
      }
      onError()
    }
  }
}

internal fun FirClassSymbol<*>.findAssistedInjectConstructors(
  session: FirSession,
  checkClass: Boolean = true,
): List<FirInjectConstructor> =
  findInjectConstructorsImpl(
    session,
    session.metroFirBuiltIns.classIds.assistedInjectAnnotations,
    checkClass,
  )

internal fun FirClassSymbol<*>.findInjectConstructors(
  session: FirSession,
  checkClass: Boolean = true,
): List<FirInjectConstructor> =
  findInjectConstructorsImpl(
    session,
    session.metroFirBuiltIns.classIds.injectAnnotations,
    checkClass,
  )

internal fun FirClassSymbol<*>.findInjectLikeConstructors(
  session: FirSession,
  checkClass: Boolean = true,
): List<FirInjectConstructor> =
  findInjectConstructorsImpl(
    session = session,
    annotationClassIds =
      if (checkClass) {
        // When checking classes, use inject-like annotations (@Inject and @Contributes*)
        session.metroFirBuiltIns.classIds.injectLikeAnnotations
      } else {
        // When not checking classes (constructor-only), use only @Inject
        session.metroFirBuiltIns.classIds.allInjectAnnotations
      },
    checkClass = checkClass,
  )

internal fun FirClassSymbol<*>.findInjectConstructorsImpl(
  session: FirSession,
  annotationClassIds: Set<ClassId>,
  checkClass: Boolean,
): List<FirInjectConstructor> {
  if (classKind != ClassKind.CLASS) return emptyList()
  rawStatus.modality?.let { if (it != Modality.FINAL && it != Modality.OPEN) return emptyList() }

  // Look at raw declarations, otherwise we infinite loop
  @OptIn(DirectDeclarationsAccess::class)
  val ctors = declarationSymbols.filterIsInstance<FirConstructorSymbol>()
  if (checkClass) {
    val classInject = annotationsIn(session, annotationClassIds).firstOrNull()
    if (classInject != null) {
      val primaryConstructor = ctors.find { it.isPrimary }
      return listOf(FirInjectConstructor(classInject, primaryConstructor, true, ctors.size))
    }
  }

  return buildList {
    for (ctor in ctors) {
      val injectAnno = ctor.annotationsIn(session, annotationClassIds).firstOrNull() ?: continue
      add(FirInjectConstructor(injectAnno, ctor, false, ctors.size))
    }
  }
}

internal data class FirInjectConstructor(
  val annotation: FirAnnotation,
  val constructor: FirConstructorSymbol?,
  val annotationOnClass: Boolean,
  val ctorCount: Int,
)

internal fun FirClass.validateInjectedClass(
  context: CheckerContext,
  reporter: DiagnosticReporter,
  classInjectAnnotations: List<FirAnnotation>,
) {
  if (isLocal) {
    reporter.reportOn(source, MetroDiagnostics.LOCAL_CLASSES_CANNOT_BE_INJECTED, context)
    return
  }

  when (classKind) {
    ClassKind.CLASS -> {
      when (modality) {
        Modality.FINAL,
        Modality.OPEN -> {
          // final/open This is fine
        }

        else -> {
          // sealed/abstract
          reporter.reportOn(
            source,
            MetroDiagnostics.ONLY_FINAL_AND_OPEN_CLASSES_CAN_BE_INJECTED,
            context,
          )
        }
      }
    }
    // This is fine for @Contributes* injection cases but errors
    ClassKind.OBJECT if (classInjectAnnotations.isEmpty()) -> {
      // If we hit here, it's because the class has a `@Contributes*` annotation implying its
      // injectability but no regular `@Inject` annotations. So, report nothing
    }

    else -> {
      reporter.reportOn(source, MetroDiagnostics.ONLY_CLASSES_CAN_BE_INJECTED, context)
    }
  }

  checkVisibility { source, allowedVisibilities ->
    reporter.reportOn(
      source,
      MetroDiagnostics.INJECTED_CLASSES_MUST_BE_VISIBLE,
      allowedVisibilities,
      context,
    )
  }
}

internal fun FirCallableSymbol<*>.allAnnotations(): Sequence<FirAnnotation> {
  return sequence {
    yieldAll(resolvedCompilerAnnotationsWithClassIds)
    if (this@allAnnotations is FirPropertySymbol) {
      yieldAll(backingFieldSymbol?.resolvedCompilerAnnotationsWithClassIds.orEmpty())
      getterSymbol?.resolvedCompilerAnnotationsWithClassIds?.let { yieldAll(it) }
      setterSymbol?.resolvedCompilerAnnotationsWithClassIds?.let { yieldAll(it) }
    }
  }
}

context(context: CheckerContext, reporter: DiagnosticReporter)
internal inline fun FirClass.validateApiDeclaration(
  type: String,
  checkConstructor: Boolean,
  onError: () -> Nothing,
) {
  if (isLocal) {
    reporter.reportOn(
      source,
      MetroDiagnostics.METRO_DECLARATION_ERROR,
      "$type cannot be local classes.",
    )
    onError()
  }

  when (classKind) {
    ClassKind.INTERFACE -> {
      // This is fine
      when (modality) {
        Modality.SEALED -> {
          reporter.reportOn(
            source,
            MetroDiagnostics.METRO_DECLARATION_ERROR,
            "$type should be non-sealed abstract classes or interfaces.",
          )
          onError()
        }

        else -> {
          // This is fine
        }
      }
    }

    ClassKind.CLASS -> {
      when (modality) {
        Modality.ABSTRACT -> {
          // This is fine
        }

        else -> {
          // final/open/sealed
          reporter.reportOn(
            source,
            MetroDiagnostics.METRO_DECLARATION_ERROR,
            "$type should be non-sealed abstract classes or interfaces.",
          )
          onError()
        }
      }
    }

    else -> {
      reporter.reportOn(
        source,
        MetroDiagnostics.METRO_DECLARATION_ERROR,
        "$type should be non-sealed abstract classes or interfaces.",
      )
      onError()
    }
  }

  checkVisibility { source, allowedVisibilities ->
    reporter.reportOn(
      source,
      MetroDiagnostics.METRO_DECLARATION_VISIBILITY_ERROR,
      type,
      allowedVisibilities,
    )
    onError()
  }
  if (checkConstructor && isAbstract && classKind == ClassKind.CLASS) {
    primaryConstructorIfAny(context.session)?.validateVisibility("$type' primary constructor") {
      onError()
    }
  }
}

context(context: CheckerContext, reporter: DiagnosticReporter)
internal inline fun FirConstructorSymbol.validateVisibility(type: String, onError: () -> Nothing) {
  checkVisibility { source, allowedVisibilities ->
    reporter.reportOn(
      source,
      MetroDiagnostics.METRO_DECLARATION_VISIBILITY_ERROR,
      type,
      allowedVisibilities,
    )
    onError()
  }
}

internal fun FirBasedSymbol<*>.qualifierAnnotation(
  session: FirSession,
  typeResolver: TypeResolveService? = null,
): MetroFirAnnotation? =
  resolvedCompilerAnnotationsWithClassIds.qualifierAnnotation(session, typeResolver)

internal fun List<FirAnnotation>.qualifierAnnotation(
  session: FirSession,
  typeResolver: TypeResolveService? = null,
): MetroFirAnnotation? =
  asSequence()
    .annotationAnnotatedWithAny(session, session.classIds.qualifierAnnotations, typeResolver)
    ?.takeIf {
      // Guice's `@Assisted` annoyingly annotates itself as a qualifier too, so we catch that here
      it.fir.toAnnotationClassIdSafe(session) != GuiceSymbols.ClassIds.assisted
    }

internal fun FirBasedSymbol<*>.mapKeyAnnotation(session: FirSession): MetroFirAnnotation? =
  resolvedCompilerAnnotationsWithClassIds.mapKeyAnnotation(session)

internal fun List<FirAnnotation>.mapKeyAnnotation(session: FirSession): MetroFirAnnotation? =
  asSequence().annotationAnnotatedWithAny(session, session.classIds.mapKeyAnnotations)

// TODO use FirExpression extensions
//  fun FirExpression.extractClassesFromArgument(session: FirSession): List<FirRegularClassSymbol>
//  fun FirExpression.extractClassFromArgument(session: FirSession): FirRegularClassSymbol?

internal fun List<FirAnnotation>.scopeAnnotations(
  session: FirSession
): Sequence<MetroFirAnnotation> = asSequence().scopeAnnotations(session)

internal fun Sequence<FirAnnotation>.scopeAnnotations(
  session: FirSession
): Sequence<MetroFirAnnotation> =
  annotationsAnnotatedWithAny(session, session.classIds.scopeAnnotations)

// TODO add a single = true|false param? How would we propagate errors
internal fun Sequence<FirAnnotation>.annotationAnnotatedWithAny(
  session: FirSession,
  names: Set<ClassId>,
  typeResolver: TypeResolveService? = null,
): MetroFirAnnotation? {
  return annotationsAnnotatedWithAny(session, names, typeResolver).firstOrNull()
}

internal fun Sequence<FirAnnotation>.annotationsAnnotatedWithAny(
  session: FirSession,
  names: Set<ClassId>,
  typeResolver: TypeResolveService? = null,
): Sequence<MetroFirAnnotation> {
  return filter { it.isResolved }
    .filterIsInstance<FirAnnotationCall>()
    .filter { annotationCall -> annotationCall.isAnnotatedWithAny(session, names) }
    .map { MetroFirAnnotation(it, session, typeResolver) }
}

internal fun FirAnnotationCall.isAnnotatedWithAny(
  session: FirSession,
  names: Set<ClassId>,
): Boolean {
  val annotationType = resolvedType as? ConeClassLikeType ?: return false
  val annotationClass = annotationType.toClassSymbol(session) ?: return false
  return annotationClass.resolvedCompilerAnnotationsWithClassIds.isAnnotatedWithAny(session, names)
}

internal fun createDeprecatedHiddenAnnotation(session: FirSession): FirAnnotation =
  buildAnnotation {
    val deprecatedAnno =
      session.symbolProvider.getClassLikeSymbolByClassId(StandardClassIds.Annotations.Deprecated)
        as FirRegularClassSymbol

    annotationTypeRef = deprecatedAnno.defaultType().toFirResolvedTypeRef()

    argumentMapping = buildAnnotationArgumentMapping {
      mapping[Name.identifier("message")] =
        buildLiteralExpression(
          null,
          ConstantValueKind.String,
          "This synthesized declaration should not be used directly",
          setType = true,
        )

      // It has nothing to do with enums deserialization, but it is simply easier to build it this
      // way.
      mapping[Name.identifier("level")] =
        buildEnumEntryDeserializedAccessExpression {
            enumClassId = StandardClassIds.DeprecationLevel
            enumEntryName = Name.identifier("HIDDEN")
          }
          .toQualifiedPropertyAccessExpression(session)
    }
  }

internal fun FirClassLikeDeclaration.markImpl(session: FirSession) {
  replaceAnnotations(
    annotations +
      listOf(buildSimpleAnnotation { session.metroFirBuiltIns.metroImplMarkerClassSymbol })
  )
}

internal fun FirClassLikeDeclaration.markAsDeprecatedHidden(session: FirSession) {
  replaceAnnotations(annotations + listOf(createDeprecatedHiddenAnnotation(session)))
  replaceDeprecationsProvider(this.getDeprecationsProvider(session))
}

internal fun ConeTypeProjection.wrapInProviderIfNecessary(
  session: FirSession,
  providerClassId: ClassId,
): ConeClassLikeType {
  val type = this.type
  if (type is ConeClassLikeType) {
    val classId = type.lookupTag.classId
    if (classId in session.classIds.providerTypes) {
      // Already a provider
      return type
    }
  }
  return providerClassId.constructClassLikeType(arrayOf(this))
}

internal fun ConeTypeProjection.wrapInLazyIfNecessary(
  session: FirSession,
  lazyClassId: ClassId,
): ConeClassLikeType {
  val type = this.type
  if (type is ConeClassLikeType) {
    val classId = type.lookupTag.classId
    if (classId in session.classIds.lazyTypes) {
      // Already a lazy
      return type
    }
  }
  return lazyClassId.constructClassLikeType(arrayOf(this))
}

internal fun FirClassSymbol<*>.constructType(
  typeParameterRefs: List<FirTypeParameterRef>
): ConeClassLikeType {
  return constructType(typeParameterRefs.mapToArray { it.symbol.toConeType() })
}

// Annoyingly, FirDeclarationOrigin.Plugin does not implement equals()
// TODO this still doesn't seem to work in 2.2
internal fun FirBasedSymbol<*>.hasOrigin(vararg keys: GeneratedDeclarationKey): Boolean {
  for (key in keys) {
    if (hasOrigin(key.origin)) return true
  }
  return false
}

internal fun FirBasedSymbol<*>.hasOrigin(o: FirDeclarationOrigin): Boolean {
  val thisOrigin = origin

  if (thisOrigin == o) return true
  if (thisOrigin is FirDeclarationOrigin.Plugin && o is FirDeclarationOrigin.Plugin) {
    return thisOrigin.key == o.key
  }
  return false
}

/** Properties can store annotations in SO many places */
internal fun FirCallableSymbol<*>.findAnnotation(
  session: FirSession,
  findAnnotation: FirBasedSymbol<*>.(FirSession) -> MetroFirAnnotation?,
  callingAccessor: FirCallableSymbol<*>? = null,
): MetroFirAnnotation? {
  findAnnotation(session)?.let {
    return it
  }
  when (this) {
    is FirPropertySymbol -> {
      getterSymbol
        ?.takeUnless { it == callingAccessor }
        ?.findAnnotation(session)
        ?.let {
          return it
        }
      setterSymbol
        ?.takeUnless { it == callingAccessor }
        ?.findAnnotation(session)
        ?.let {
          return it
        }
      backingFieldSymbol
        ?.takeUnless { it == callingAccessor }
        ?.findAnnotation(session)
        ?.let {
          return it
        }
    }

    is FirPropertyAccessorSymbol -> {
      return propertySymbol.findAnnotation(session, findAnnotation, this)
    }

    is FirBackingFieldSymbol -> {
      return propertySymbol.findAnnotation(session, findAnnotation, this)
    }
  // else it's a function, covered by the above
  }
  return null
}

context(compatContext: CompatContext)
internal fun FirBasedSymbol<*>.requireContainingClassSymbol(): FirClassLikeSymbol<*> =
  with(compatContext) {
    getContainingClassSymbol() ?: reportCompilerBug("No containing class symbol found for $this")
  }

private val FirPropertyAccessExpression.qualifierName: Name?
  get() = (calleeReference as? FirSimpleNamedReference)?.name

internal fun FirAnnotation.originArgument() =
  classArgument(StandardNames.DEFAULT_VALUE_PARAMETER, index = 0)

internal fun FirAnnotation.scopeArgument() = classArgument(Symbols.Names.scope, index = 0)

internal fun FirAnnotation.additionalScopesArgument() =
  arrayArgument(Symbols.Names.additionalScopes, index = 1)

internal fun FirAnnotation.bindingContainersArgument() =
  arrayArgument(Symbols.Names.bindingContainers, index = 4)

internal fun FirAnnotation.includesArgument() = arrayArgument(Symbols.Names.includes, index = 0)

internal fun FirAnnotation.allScopeClassIds(): Set<ClassId> =
  buildSet {
      resolvedScopeClassId()?.let(::add)
      resolvedAdditionalScopesClassIds()?.let(::addAll)
    }
    .filterNotTo(mutableSetOf()) { it == StandardClassIds.Nothing }

internal fun FirAnnotation.excludesArgument() = arrayArgument(Symbols.Names.excludes, index = 2)

internal fun FirAnnotation.replacesArgument() = arrayArgument(Symbols.Names.replaces, index = 2)

internal fun FirAnnotation.rankValue(): Long {
  // Although the parameter is defined as an Int, the value we receive here may end up being
  // an Int or a Long so we need to handle both
  return rankArgument()?.value?.let { it as? Long ?: (it as? Int)?.toLong() } ?: Long.MIN_VALUE
}

private fun FirAnnotation.rankArgument() =
  argumentAsOrNull<FirLiteralExpression>(Symbols.Names.rank, index = 5)

internal fun FirAnnotation.bindingArgument() = annotationArgument(Symbols.Names.binding, index = 1)

internal fun FirAnnotation.resolvedBindingArgument(
  session: FirSession,
  typeResolver: TypeResolveService? = null,
): FirTypeRef? {
  // Return a binding defined using Metro's API
  bindingArgument()?.let { binding ->
    return binding.typeArguments[0].expectAsOrNull<FirTypeProjectionWithVariance>()?.typeRef
  }
  // Anvil interop - try a boundType defined using anvil KClass
  return anvilKClassBoundTypeArgument(session, typeResolver)
}

internal fun FirAnnotation.anvilKClassBoundTypeArgument(
  session: FirSession,
  typeResolver: TypeResolveService? = null,
): FirTypeRef? {
  return getAnnotationKClassArgument(Symbols.Names.boundType, session, typeResolver)
    ?.toFirResolvedTypeRef()
}

internal fun FirAnnotation.getAnnotationKClassArgument(
  name: Name,
  session: FirSession,
  typeResolver: TypeResolveService? = null,
): ConeKotlinType? {
  val argument = findArgumentByNameSafe(name) ?: return null
  return argument.evaluateAs<FirGetClassCall>(session)?.getTargetType()
    ?: typeResolver?.let { (argument as FirGetClassCall).resolvedArgumentConeKotlinType(it) }
}

internal fun FirAnnotation.resolvedScopeClassId() = scopeArgument()?.resolvedClassId()

internal fun FirAnnotation.resolvedScopeClassId(typeResolver: TypeResolveService): ClassId? {
  val scopeArgument = scopeArgument() ?: return null
  // Try to resolve it normally first. If this fails,
  // try to resolve within the enclosing scope
  return scopeArgument.resolvedClassId()
    ?: scopeArgument.resolvedArgumentConeKotlinType(typeResolver)?.classId
}

internal fun FirAnnotation.resolvedAdditionalScopesClassIds() =
  additionalScopesArgument()?.argumentList?.arguments?.mapNotNull {
    it.expectAsOrNull<FirGetClassCall>()?.resolvedClassId()
  }

internal fun FirAnnotation.resolvedBindingContainersClassIds() =
  bindingContainersArgument()?.argumentList?.arguments?.mapNotNull {
    it.expectAsOrNull<FirGetClassCall>()
  }

internal fun FirAnnotation.resolvedIncludesClassIds() =
  includesArgument()?.argumentList?.arguments?.mapNotNull { it.expectAsOrNull<FirGetClassCall>() }

internal fun FirAnnotation.resolvedAdditionalScopesClassIds(
  typeResolver: TypeResolveService
): List<ClassId> {
  val additionalScopes =
    additionalScopesArgument()?.argumentList?.arguments?.mapNotNull {
      it.expectAsOrNull<FirGetClassCall>()
    } ?: return emptyList()
  // Try to resolve it normally first. If this fails,
  // try to resolve within the enclosing scope
  return additionalScopes.mapNotNull { it.resolvedClassId() }.takeUnless { it.isEmpty() }
    ?: additionalScopes.mapNotNull { it.resolvedArgumentConeKotlinType(typeResolver)?.classId }
}

internal fun FirAnnotation.resolvedExcludedClassIds(
  typeResolver: TypeResolveService
): Set<ClassId> {
  val excludesArgument =
    excludesArgument()?.argumentList?.arguments?.mapNotNull { it.expectAsOrNull<FirGetClassCall>() }
      ?: return emptySet()
  // Try to resolve it normally first. If this fails, try to resolve within the enclosing scope
  val excluded =
    excludesArgument.mapNotNull { it.resolvedClassId() }.takeUnless { it.isEmpty() }
      ?: excludesArgument.mapNotNull { it.resolvedArgumentConeKotlinType(typeResolver)?.classId }
  return excluded.toSet()
}

internal fun FirAnnotation.resolvedReplacedClassIds(
  typeResolver: MetroFirTypeResolver
): Set<ClassId> {
  val replacesArgument =
    replacesArgument()?.argumentList?.arguments?.mapNotNull { it.expectAsOrNull<FirGetClassCall>() }
      ?: return emptySet()
  val replaced =
    replacesArgument.mapNotNull { getClassCall ->
      getClassCall.resolveClassId(typeResolver)
      // If it's available and resolved, just use it directly!
      getClassCall.coneTypeIfResolved()?.classId?.let {
        return@mapNotNull it
      }
      // Otherwise fall back to trying to parse from the reference
      val reference = getClassCall.resolvedArgumentTypeRef() ?: return@mapNotNull null
      typeResolver.resolveType(reference).classId
    }
  return replaced.toSet()
}

internal fun FirGetClassCall.resolveClassId(typeResolver: MetroFirTypeResolver): ClassId? {
  // If it's available and resolved, just use it directly!
  coneTypeIfResolved()?.classId?.let {
    return it
  }
  // Otherwise fall back to trying to parse from the reference
  val reference = resolvedArgumentTypeRef() ?: return null
  return typeResolver.resolveType(reference).classId
}

internal fun FirGetClassCall.resolvedClassId() = (argument as? FirResolvedQualifier)?.classId

internal fun FirGetClassCall.resolvedArgumentConeKotlinType(
  typeResolver: TypeResolveService
): ConeKotlinType? {
  coneTypeIfResolved()?.let {
    return it
  }
  val ref = resolvedArgumentTypeRef() ?: return null
  return typeResolver.resolveUserType(ref).coneType
}

private fun FirGetClassCall.coneTypeIfResolved(): ConeKotlinType? {
  return when (val arg = argument) {
    // I'm not really sure why these sometimes come down as different types but shrug
    is FirClassReferenceExpression if (isResolved) -> arg.classTypeRef.coneTypeOrNull
    is FirResolvedQualifier if (isResolved) -> arg.resolvedType
    else -> null
  }
}

internal fun FirGetClassCall.resolvedArgumentTypeRef(): FirUserTypeRef? {
  val source = source ?: return null

  return typeRefFromQualifierParts(isMarkedNullable = false, source) {
    fun visitQualifiers(expression: FirExpression) {
      if (expression !is FirPropertyAccessExpression) return
      expression.explicitReceiver?.let { visitQualifiers(it) }
      expression.qualifierName?.let { part(it) }
    }
    visitQualifiers(argument)
  }
}

internal fun FirAnnotation.classArgument(name: Name, index: Int) =
  argumentAsOrNull<FirGetClassCall>(name, index)

internal fun FirAnnotation.annotationArgument(name: Name, index: Int) =
  argumentAsOrNull<FirFunctionCall>(name, index)

internal inline fun <reified T : Any> FirAnnotation.argumentAsOrNull(name: Name, index: Int): T? {
  return argumentAsOrNull(T::class.java, name, index)
}

/**
 * Retrieves a specific argument from a [FirAnnotation] by its [name] and [index], and casts it to
 * the specified type [klass] (if it matches). If the argument is not found, is not of the expected
 * type, or the name does not match, returns null.
 *
 * @param klass The expected class type of the argument.
 * @param name The name of the argument to retrieve.
 * @param index The position of the argument in the argument list.
 * @return The casted argument if found, otherwise null.
 */
internal fun <T : Any> FirAnnotation.argumentAsOrNull(klass: Class<T>, name: Name, index: Int): T? {
  argumentMapping.mapping[name]?.let {
    return if (klass.isInstance(it)) {
      @Suppress("UNCHECKED_CAST")
      it as T
    } else {
      null
    }
  }
  if (this !is FirAnnotationCall) return null

  if (arguments.isEmpty()) {
    // Nothing to do here
    return null
  } else if (index == 0 && arguments.size == 1) {
    val arg0 = arguments[0]
    return if (arg0 is FirNamedArgumentExpression) {
      if (arg0.name != name || !klass.isInstance(arg0.expression)) {
        null
      } else {
        @Suppress("UNCHECKED_CAST")
        arg0.expression as? T
      }
    } else if (klass.isInstance(arg0)) {
      @Suppress("UNCHECKED_CAST")
      arg0 as T
    } else {
      null
    }
  }

  val argByIndex = arguments.getOrNull(index)

  // Check if the index is present but under a different name
  if (argByIndex != null && argumentMapping.mapping.isNotEmpty()) {
    for ((argName, arg) in argumentMapping.mapping) {
      if (arg == argByIndex && argName != name) {
        // Different name than expected, bounce out
        return null
      }
    }
  }

  val thoroughMapping =
    argumentMapping.mapping.ifEmpty {
      // These may not be present but are present in arguments
      buildMap(arguments.size) {
        for (arg in arguments) {
          if (arg !is FirNamedArgumentExpression) continue
          if (arg.name == name) {
            return if (klass.isInstance(arg.expression)) {
              @Suppress("UNCHECKED_CAST")
              arg.expression as T
            } else {
              null
            }
          } else if (arg == argByIndex) {
            // Different name than expected, bounce out
            return null
          }
          put(arg.name, arg)
        }
      }
    }

  if (argByIndex == null) {
    // Nothing to check here anyway
    return null
  }

  // In external declarations, there is no argumentMapping (based on reading kotlinc source)
  thoroughMapping.entries
    .find { it.value == argByIndex }
    ?.let { (associatedName, _) ->
      if (associatedName != name) {
        return null
      }
    }

  // Fall back to the index if necessary
  return if (klass.isInstance(argByIndex)) {
    @Suppress("UNCHECKED_CAST")
    argByIndex as T
  } else {
    null
  }
}

/**
 * In most cases if we're searching for an argument by name, we do not want to default to the first
 * argument. E.g. when looking for 'boundType', if it's not explicitly defined, then receiving the
 * first argument would mean receiving the 'scope' argument and it would still compile fine since
 * those annotation params share the same type.
 *
 * ```
 * Given `@ContributesBinding(scope = AppScope::class)`
 * findArgumentByName("boundType")     returns AppScope::class
 * findArgumentByNameSafe("boundType") returns null
 * ```
 */
internal fun FirAnnotation.findArgumentByNameSafe(name: Name): FirExpression? =
  findArgumentByName(name, returnFirstWhenNotFound = false)

internal fun List<FirElement>.joinToRender(separator: String = ", "): String {
  return joinToString(separator) {
    buildString {
      append(it.render())
      if (it is FirAnnotation) {
        append(" resolved=")
        append(it.isResolved)
        append(" unexpandedClassId=")
        append(it.unexpandedClassId)
      }
    }
  }
}

internal fun buildSimpleAnnotation(symbol: () -> FirRegularClassSymbol): FirAnnotation {
  return buildAnnotation {
    annotationTypeRef = symbol().defaultType().toFirResolvedTypeRef()

    argumentMapping = buildAnnotationArgumentMapping()
  }
}

internal fun FirClass.isOrImplements(supertype: ClassId, session: FirSession): Boolean {
  if (classId == supertype) return true
  return implements(supertype, session)
}

internal fun FirClass.implements(supertype: ClassId, session: FirSession): Boolean {
  return lookupSuperTypes(
      klass = this,
      lookupInterfaces = true,
      deep = true,
      useSiteSession = session,
      substituteTypes = true,
    )
    .any { it.classId?.let { it == supertype } == true }
}

internal fun FirClassSymbol<*>.isOrImplements(supertype: ClassId, session: FirSession): Boolean {
  if (classId == supertype) return true
  return implements(supertype, session)
}

internal fun FirClassSymbol<*>.implements(supertype: ClassId, session: FirSession): Boolean {
  return lookupSuperTypes(
      symbols = listOf(this),
      lookupInterfaces = true,
      deep = true,
      useSiteSession = session,
      substituteTypes = true,
    )
    .any { it.classId?.let { it == supertype } == true }
}

internal fun ConeKotlinType.render(short: Boolean): String {
  return buildString { renderType(short, this@render) }
}

// Custom renderer that excludes annotations
internal fun StringBuilder.renderType(short: Boolean, type: ConeKotlinType) {
  val renderer =
    object :
      ConeTypeRendererForReadability(
        this,
        null,
        { if (short) ConeIdShortRenderer() else ConeIdRendererForDiagnostics() },
      ) {
      override fun ConeKotlinType.renderAttributes() {
        // Do nothing, we don't want annotations
      }
    }
  renderer.render(type)
}

context(context: CheckerContext)
internal fun FirClassSymbol<*>.nestedClasses(): List<FirRegularClassSymbol> {
  val collected = mutableListOf<FirRegularClassSymbol>()
  declaredMemberScope().processAllClassifiers { symbol ->
    if (symbol is FirRegularClassSymbol) {
      collected += symbol
    }
  }
  return collected
}

internal fun NestedClassGenerationContext.nestedClasses(): List<FirRegularClassSymbol> {
  val collected = mutableListOf<FirRegularClassSymbol>()
  declaredScope?.processAllClassifiers { symbol ->
    if (symbol is FirRegularClassSymbol) {
      collected += symbol
    }
  }
  return collected
}

context(context: CheckerContext)
internal fun FirClassSymbol<*>.directCallableSymbols(): List<FirCallableSymbol<*>> {
  val collected = mutableListOf<FirCallableSymbol<*>>()
  declaredMemberScope().processAllCallables { collected += it }
  return collected
}

// Build a complete substitution map that includes mappings for ancestor type parameters
internal fun buildFullSubstitutionMap(
  targetClass: FirClassSymbol<*>,
  directMappings: Map<FirTypeParameterSymbol, ConeKotlinType>,
  session: FirSession,
): Map<FirTypeParameterSymbol, ConeKotlinType> {
  return buildSubstitutionMapInner(targetClass, directMappings, session, full = true)
}

private fun buildSubstitutionMapInner(
  targetClass: FirClassSymbol<*>,
  directMappings: Map<FirTypeParameterSymbol, ConeKotlinType>,
  session: FirSession,
  full: Boolean,
): Map<FirTypeParameterSymbol, ConeKotlinType> {
  val result = mutableMapOf<FirTypeParameterSymbol, ConeKotlinType>()

  // Start with the direct mappings for the target class
  result.putAll(directMappings)

  // Walk up the inheritance chain and collect substitutions
  var currentClass: FirClassSymbol<*>? = targetClass
  while (currentClass != null) {
    val superType =
      currentClass.resolvedSuperTypes.firstOrNull {
        it.classId != session.builtinTypes.anyType.coneType.classId
      }

    if (superType is ConeClassLikeType && superType.typeArguments.isNotEmpty()) {
      val superClass = superType.toRegularClassSymbol(session)
      if (superClass != null) {
        // Map ancestor type parameters to their concrete types in the inheritance chain
        superClass.typeParameterSymbols.zip(superType.typeArguments).forEach { (param, arg) ->
          if (arg is ConeKotlinTypeProjection) {
            // Apply existing substitutions to the argument type
            val substitutor = substitutorByMap(result, session)
            val substitutedType = substitutor.substituteOrNull(arg.type) ?: arg.type
            result[param] = substitutedType
          }
        }
      }
      currentClass = superClass
    } else if (!full) {
      // Shallow one-layer
      break
    } else {
      break
    }
  }

  return result
}

internal fun typeRefFromQualifierParts(
  isMarkedNullable: Boolean,
  source: KtSourceElement,
  builder: QualifierPartBuilder.() -> Unit,
): FirUserTypeRef {
  val userTypeRef = buildUserTypeRef {
    this.isMarkedNullable = isMarkedNullable
    this.source = source
    QualifierPartBuilder(qualifier).builder()
  }
  return userTypeRef
}

internal val FirSession.memoizedAllSessionsSequence: Sequence<FirSession>
  get() = sequenceOf(this).plus(moduleData.allDependsOnDependencies.map { it.session }).memoized()

internal fun FirClassSymbol<*>.originClassId(
  session: FirSession,
  typeResolver: MetroFirTypeResolver,
): ClassId? =
  annotationsIn(session, session.classIds.originAnnotations)
    .firstOrNull()
    ?.originArgument()
    ?.resolveClassId(typeResolver)

internal fun FirValueParameterSymbol.hasMetroDefault(session: FirSession): Boolean {
  return computeMetroDefault(
    behavior = session.metroFirBuiltIns.options.optionalBindingBehavior,
    isAnnotatedOptionalDep = {
      isAnnotatedWithAny(session, session.classIds.optionalBindingAnnotations)
    },
    hasDefaultValue = { this@hasMetroDefault.hasDefaultValue },
  )
}

context(compatContext: CompatContext)
internal fun FirCallableSymbol<*>.isEffectivelyOpen(): Boolean =
  with(compatContext) {
    if (visibility == Visibilities.Private) return false

    // If it's in an interface - not private
    // If it's in an abstract class - open
    val containingClass = getContainingClassSymbol() ?: return false
    val containingClassKind = containingClass.classKind ?: return false
    return when (containingClassKind) {
      ClassKind.INTERFACE -> true
      ClassKind.CLASS -> !containingClass.isFinal && (isOpen || isAbstract)
      ClassKind.ENUM_CLASS,
      ClassKind.ENUM_ENTRY,
      ClassKind.ANNOTATION_CLASS,
      ClassKind.OBJECT -> false
    }
  }

/**
 * If this returns null, it's a valid container type. If this returns non-null, the string is the
 * error message.
 */
internal fun FirClassLikeSymbol<*>.bindingContainerErrorMessage(
  session: FirSession,
  alreadyCheckedAnnotation: Boolean = false,
): String? {
  return if (classId.isPlatformType()) {
    "Platform type '${classId.asFqNameString()}' is not a binding container."
  } else if (this is FirAnonymousObjectSymbol) {
    "Anonymous objects cannot be binding containers."
  } else if (isLocal) {
    "Local class '${classId.shortClassName}' cannot be a binding container."
  } else if (isInner) {
    "Inner class '${classId.shortClassName}' cannot be a binding container."
  } else if (
    !alreadyCheckedAnnotation &&
      !isAnnotatedWithAny(session, session.metroFirBuiltIns.classIds.bindingContainerAnnotations)
  ) {
    "'${classId.asFqNameString()}' is not annotated with a `@BindingContainer` annotation."
  } else {
    null
  }
}
