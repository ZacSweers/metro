// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.computeMetroDefault
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.generators.CompositeMetroFirDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.fir.generators.collectAbstractFunctions
import dev.zacsweers.metro.compiler.isPlatformType
import dev.zacsweers.metro.compiler.mapToArray
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.GuiceSymbols
import dev.zacsweers.metro.compiler.symbols.Symbols
import java.util.Objects
import kotlin.contracts.contract
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
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.declarations.declaredFunctions
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.getBooleanArgument
import org.jetbrains.kotlin.fir.declarations.getTargetType
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassLikeSymbol
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.declarations.utils.isFinal
import org.jetbrains.kotlin.fir.declarations.utils.isInner
import org.jetbrains.kotlin.fir.declarations.utils.isLateInit
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
import org.jetbrains.kotlin.fir.expressions.UnresolvedExpressionTypeAccess
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildArgumentList
import org.jetbrains.kotlin.fir.expressions.builder.buildEnumEntryDeserializedAccessExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildGetClassCall
import org.jetbrains.kotlin.fir.expressions.builder.buildLiteralExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.unexpandedClassId
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension.TypeResolveService
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.QualifierPartBuilder
import org.jetbrains.kotlin.fir.java.FirCliSession
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.references.impl.FirSimpleNamedReference
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.renderer.ConeIdRenderer
import org.jetbrains.kotlin.fir.renderer.ConeIdShortRenderer
import org.jetbrains.kotlin.fir.renderer.ConeTypeRendererForReadability
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.getSuperTypes
import org.jetbrains.kotlin.fir.resolve.lookupSuperTypes
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.scopes.impl.declaredMemberScope
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.scopes.processAllCallables
import org.jetbrains.kotlin.fir.scopes.processAllClassifiers
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousObjectSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirBackingFieldSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFieldSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertyAccessorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeClassLikeLookupTag
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.FirPlaceholderProjection
import org.jetbrains.kotlin.fir.types.FirStarProjection
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.abbreviatedType
import org.jetbrains.kotlin.fir.types.builder.buildUserTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.ClassIdBasedLocality
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.types.ConstantValueKind

@OptIn(UnresolvedExpressionTypeAccess::class)
internal val FirExpression.isResolved: Boolean
  get() = coneTypeOrNull != null

internal fun FirBasedSymbol<*>.isAnnotatedInject(session: FirSession): Boolean {
  return isAnnotatedWithAny(session, session.classIds.injectAnnotations)
}

/**
 * Returns `true` if this is beholden to contribution providers.
 * [generateContributionProviders][MetroOptions.generateContributionProviders] is enabled and the
 * class has `@Contributes*` annotations — unless the class is annotated with `@ExposeImplBinding`,
 * which opts out of the skip.
 *
 * Top-level classes generated by Metro extensions are also never skipped, because FIR cannot
 * generate top-level classes from other generated top-level classes, so contribution providers
 * cannot be created for them. These classes are tagged with [isExtensionGenerated] by the
 * [CompositeMetroFirDeclarationGenerationExtension].
 */
@OptIn(SymbolInternals::class)
internal fun FirBasedSymbol<*>.usesContributionProviderPath(session: FirSession): Boolean {
  if (!session.metroFirBuiltIns.options.generateContributionProviders) return false
  if (this is FirClassSymbol<*> && fir.isExtensionGenerated == true) return false
  if (isAnnotatedWithAny(session, session.classIds.contributionProviderExclusionAnnotations)) {
    return false
  }
  if (
    !isAnnotatedWithAny(session, session.classIds.contributesBindingLikeAnnotationsWithContainers)
  ) {
    return false
  }
  // Can't generate a contribution provider if the inject constructor is private
  if (this is FirClassSymbol<*>) {
    val injectCtor = findInjectLikeConstructors(session).firstOrNull()
    if (injectCtor?.constructor?.rawStatus?.visibility == Visibilities.Private) {
      return false
    }
  }
  return true
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
          it.flattenRepeatedAnnotations(session)
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
private fun FirAnnotation.arrayArgument(session: FirSession, name: Name, index: Int): FirCall? {
  return argumentAsOrNull<FirCall>(session, name, index)
}

/** @see [dev.zacsweers.metro.compiler.ClassIds.allRepeatableContributesAnnotationsContainers] */
internal fun FirAnnotation.flattenRepeatedAnnotations(
  session: FirSession
): Sequence<FirAnnotation> {
  return arrayArgument(session, StandardNames.DEFAULT_VALUE_PARAMETER, 0)
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
internal fun FirAnnotation.computeAnnotationHash(
  session: FirSession,
  typeResolver: TypeResolveService? = null,
): Int {
  val args =
    if (this is FirAnnotationCall) {
      arguments
    } else {
      argumentMapping.mapping.values
    }
  return Objects.hash(
    toAnnotationClassIdSafe(session),
    args
      .map { arg -> renderAnnotationArgument(session, arg, typeResolver) }
      .toTypedArray()
      .contentDeepHashCode(),
  )
}

private fun renderAnnotationArgument(
  session: FirSession,
  arg: FirExpression,
  typeResolver: TypeResolveService?,
): Any? {
  val compatContext = session.compatContext
  return with(compatContext) {
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

      is FirNamedArgumentExpression -> {
        // Ignore the name for the hash, it's the value we want
        renderAnnotationArgument(session, arg.expression, typeResolver)
      }

      // Enum entry reference or const val reference.
      // Use toResolvedCallableSymbol() (not toResolvedPropertySymbol()) because
      // enum entries are FirEnumEntrySymbol, not FirPropertySymbol.
      is FirPropertyAccessExpression -> {
        arg.calleeReference.toResolvedCallableSymbol()?.callableId
      }

      is FirFunctionCall -> {
        // This is some constant-able expression like "foo" + "bar" in an annotation arg, which
        // is legal
        val evaluated = arg.evaluateAsCompat(session, FirElement::class)
        return if (evaluated is FirLiteralExpression) {
          evaluated.value
        } else {
          // May have been something like a GetClass expression, which can fall through here in 2.4+
          // but isn't "evaluatable"
          null
        }
      }

      is FirCall -> {
        // Could be FirArrayLiteral on <2.3.20
        // Could be FirCollectionLiteral on 2.3.20+
        // This is an array of some type
        arg.arguments.map { renderAnnotationArgument(session, it, typeResolver) }
      }

      else -> {
        System.err.println(
          "Unexpected annotation argument type: ${arg::class.java} - ${arg.render()}"
        )
        null
      }
    }
  }
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
  return buildList {
    var primary: FirConstructorSymbol? = null

    // Always check for an annotated constructor first even if the annotated. Otherwise something
    // annotated with `@Contributes*` with contributesAsInject enabled may fall back to just using
    // the primary constructor
    for (ctor in ctors) {
      if (ctor.isPrimary) {
        primary = ctor
      }
      val injectAnno = ctor.annotationsIn(session, annotationClassIds).firstOrNull() ?: continue
      add(FirInjectConstructor(injectAnno, ctor, false, ctors.size))
    }
    if (isEmpty() && checkClass) {
      val classInject = annotationsIn(session, annotationClassIds).firstOrNull()
      if (classInject != null) {
        val primaryConstructor = primary ?: ctors.find { it.isPrimary }
        add(FirInjectConstructor(classInject, primaryConstructor, true, ctors.size))
      }
    }
  }
}

internal data class FirInjectConstructor(
  val annotation: FirAnnotation,
  val constructor: FirConstructorSymbol?,
  val annotationOnClass: Boolean,
  val ctorCount: Int,
)

context(compatContext: CompatContext)
internal fun FirClass.validateInjectedClass(
  context: CheckerContext,
  reporter: DiagnosticReporter,
  classInjectAnnotations: List<FirAnnotation>,
) {
  if (with(compatContext) { isLocalCompat }) {
    reporter.reportOn(source, MetroDiagnostics.LOCAL_CLASSES_CANNOT_BE_INJECTED, context)
    return
  }

  when (classKind) {
    CLASS -> {
      when (modality) {
        FINAL,
        OPEN -> {
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
    OBJECT if (classInjectAnnotations.isEmpty()) -> {
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

context(context: CheckerContext, reporter: DiagnosticReporter, compatContext: CompatContext)
internal inline fun FirClass.validateApiDeclaration(
  type: String,
  checkConstructor: Boolean,
  onError: () -> Nothing,
) {
  if (with(compatContext) { isLocalCompat }) {
    reporter.reportOn(
      source,
      MetroDiagnostics.METRO_DECLARATION_ERROR,
      "$type cannot be local classes.",
    )
    onError()
  }

  when (classKind) {
    INTERFACE -> {
      // This is fine
      when (modality) {
        SEALED -> {
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

    CLASS -> {
      when (modality) {
        ABSTRACT -> {
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

internal fun FirAnnotationContainer.qualifierAnnotation(
  session: FirSession,
  typeResolver: TypeResolveService? = null,
): MetroFirAnnotation? = annotations.qualifierAnnotation(session, typeResolver)

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

internal fun FirAnnotationContainer.mapKeyAnnotation(session: FirSession): MetroFirAnnotation? =
  annotations.mapKeyAnnotation(session)

internal fun List<FirAnnotation>.mapKeyAnnotation(session: FirSession): MetroFirAnnotation? =
  asSequence().annotationAnnotatedWithAny(session, session.classIds.mapKeyAnnotations)

/**
 * Checks if the given [mapKeyAnnotation]'s `@MapKey` meta-annotation has `implicitClassKey = true`.
 */
internal fun MetroFirAnnotation.hasImplicitClassKey(session: FirSession): Boolean {
  val annotationClassId = fir.toAnnotationClassIdSafe(session) ?: return false
  val annotationClassSymbol =
    session.symbolProvider.getClassLikeSymbolByClassId(annotationClassId) ?: return false
  val mapKeyAnno =
    annotationClassSymbol.resolvedCompilerAnnotationsWithClassIds
      .annotationsIn(session, session.classIds.mapKeyAnnotations)
      .firstOrNull() ?: return false
  return mapKeyAnno.getBooleanArgument(Symbols.Names.implicitClassKey, session) == true
}

/**
 * Returns the [FirGetClassCall] of the value argument of this map key annotation, if it was
 * explicitly provided. Returns null if the value uses the default.
 */
internal fun MetroFirAnnotation.mapKeyClassValueExpression(): FirGetClassCall? {
  if (fir !is FirAnnotationCall) return null
  return fir.arguments.firstOrNull()?.expectAsOrNull<FirGetClassCall>()
}

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
    .filter { annotation -> annotation.isAnnotatedWithAny(session, names) }
    .map { MetroFirAnnotation(it, session, typeResolver) }
}

internal fun FirAnnotation.isAnnotatedWithAny(session: FirSession, names: Set<ClassId>): Boolean {
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

context(compatContext: CompatContext)
internal fun FirClassLikeDeclaration.markAsDeprecatedHidden(session: FirSession) {
  with(compatContext) {
    replaceAnnotations(annotations + listOf(createDeprecatedHiddenAnnotation(session)))
    getDeprecationsProviderCompat(session)?.let(::replaceDeprecationsProvider)
  }
}

context(compatContext: CompatContext)
internal fun FirCallableDeclaration.markAsDeprecatedHidden(session: FirSession) {
  with(compatContext) {
    replaceAnnotations(annotations + listOf(createDeprecatedHiddenAnnotation(session)))
    getDeprecationsProviderCompat(session)?.let(::replaceDeprecationsProvider)
  }
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

internal fun ConeTypeProjection.stripIfLazy(session: FirSession): ConeTypeProjection {
  val type = this.type
  if (type is ConeClassLikeType) {
    val classId = type.lookupTag.classId
    if (classId in session.classIds.lazyTypes) {
      // It's a lazy, return the type arg
      return type.typeArguments[0]
    }
  }
  return this
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

internal fun FirAnnotation.originArgument(session: FirSession) =
  classArgument(session, StandardNames.DEFAULT_VALUE_PARAMETER, index = 0)

internal fun FirAnnotation.scopeArgument(session: FirSession) =
  classArgument(session, Symbols.Names.scope, index = 0)

internal fun FirAnnotation.additionalScopesArgument(session: FirSession) =
  arrayArgument(session, Symbols.Names.additionalScopes, index = 1)

internal fun FirAnnotation.bindingContainersArgument(session: FirSession) =
  arrayArgument(session, Symbols.Names.bindingContainers, index = 4)

internal fun FirAnnotation.modulesArgument(session: FirSession) =
  arrayArgument(session, Symbols.Names.modules, index = 1)

internal fun FirAnnotation.bindingContainerClasses(
  session: FirSession,
  includeModulesArg: Boolean,
): FirCall? {
  return bindingContainersArgument(session)
    ?: if (includeModulesArg) modulesArgument(session) else null
}

internal fun FirAnnotation.includesArgument(session: FirSession) =
  arrayArgument(session, Symbols.Names.includes, index = 0)

internal fun FirAnnotation.subcomponentsArgument(session: FirSession) =
  arrayArgument(session, Symbols.Names.subcomponents, index = 1)

internal fun FirAnnotation.allScopeClassIds(session: FirSession): Set<ClassId> =
  buildSet {
      resolvedScopeClassId(session)?.let(::add)
      resolvedAdditionalScopesClassIds(session)?.let(::addAll)
    }
    .filterNotTo(mutableSetOf()) { it == StandardClassIds.Nothing }

internal fun FirAnnotation.excludesArgument(session: FirSession) =
  arrayArgument(session, Symbols.Names.excludes, index = 2)
    ?: run {
      if (session.metroFirBuiltIns.options.enableDaggerAnvilInterop) {
        arrayArgument(session, Symbols.Names.exclude, index = 3)
      } else {
        null
      }
    }

internal fun FirAnnotation.replacesArgument(session: FirSession) =
  arrayArgument(session, Symbols.Names.replaces, index = 2)

// KIA ContributesBinding parameter order:
// 0 - scope
// 1 - boundType
// 2 - replaces
// 3 - multibinding
internal fun FirAnnotation.isKiaIntoMultibinding(session: FirSession): Boolean =
  argumentAsOrNull<FirLiteralExpression>(session, Symbols.Names.multibinding, index = 3)?.value
    as? Boolean ?: false

internal fun FirAnnotation.rankValue(session: FirSession): Long {
  // Although the parameter is defined as an Int, the value we receive here may end up being
  // an Int or a Long so we need to handle both
  return rankArgument(session)?.value?.let { it as? Long ?: (it as? Int)?.toLong() }
    ?: Long.MIN_VALUE
}

private fun FirAnnotation.rankArgument(session: FirSession) =
  argumentAsOrNull<FirLiteralExpression>(session, Symbols.Names.rank, index = 5)

internal fun FirAnnotation.bindingArgument(session: FirSession) =
  annotationArgument(session, Symbols.Names.binding, index = 1)

internal fun FirAnnotation.resolvedBindingArgument(
  session: FirSession,
  typeResolver: TypeResolveService? = null,
): FirTypeRef? {
  // Return a binding defined using Metro's API
  bindingArgument(session)?.let { binding ->
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
  return with(session.compatContext) {
    argument.evaluateAsCompat(session, FirGetClassCall::class)?.getTargetType()
      ?: typeResolver?.let { (argument as FirGetClassCall).resolvedArgumentConeKotlinType(it) }
  }
}

internal fun FirAnnotation.resolvedScopeClassId(session: FirSession) =
  scopeArgument(session)?.resolvedClassId()

internal fun FirAnnotation.resolvedScopeClassId(
  session: FirSession,
  typeResolver: MetroFirTypeResolver,
): ClassId? {
  val scopeArgument = scopeArgument(session) ?: return null
  return scopeArgument.resolveClassId(typeResolver)
}

internal fun FirAnnotation.resolvedScopeClassId(
  session: FirSession,
  typeResolver: TypeResolveService,
): ClassId? {
  val scopeArgument = scopeArgument(session) ?: return null
  // Try to resolve it normally first. If this fails,
  // try to resolve within the enclosing scope
  return scopeArgument.resolvedClassId()
    ?: scopeArgument.resolvedArgumentConeKotlinType(typeResolver)?.classId
}

internal fun FirAnnotation.resolvedAdditionalScopesClassIds(session: FirSession) =
  additionalScopesArgument(session)?.argumentList?.arguments?.mapNotNull {
    it.expectAsOrNull<FirGetClassCall>()?.resolvedClassId()
  }

internal fun FirAnnotation.resolvedBindingContainersClassIds(
  session: FirSession,
  includeModulesArg: Boolean,
) =
  bindingContainerClasses(session, includeModulesArg)?.argumentList?.arguments?.mapNotNull {
    it.expectAsOrNull<FirGetClassCall>()
  }

internal fun FirAnnotation.resolvedIncludesClassIds(session: FirSession) =
  includesArgument(session)?.argumentList?.arguments?.mapNotNull {
    it.expectAsOrNull<FirGetClassCall>()
  }

internal fun FirAnnotation.resolvedAdditionalScopesClassIds(
  session: FirSession,
  typeResolver: TypeResolveService,
): List<ClassId> {
  val additionalScopes =
    additionalScopesArgument(session)?.argumentList?.arguments?.mapNotNull {
      it.expectAsOrNull<FirGetClassCall>()
    } ?: return emptyList()
  // Try to resolve it normally first. If this fails,
  // try to resolve within the enclosing scope
  return additionalScopes.mapNotNull { it.resolvedClassId() }.takeUnless { it.isEmpty() }
    ?: additionalScopes.mapNotNull { it.resolvedArgumentConeKotlinType(typeResolver)?.classId }
}

internal fun FirAnnotation.resolvedExcludedClassIds(
  session: FirSession,
  typeResolver: TypeResolveService,
): Set<ClassId> {
  val excludesArgument =
    excludesArgument(session)?.argumentList?.arguments?.mapNotNull {
      it.expectAsOrNull<FirGetClassCall>()
    } ?: return emptySet()
  // Try to resolve it normally first. If this fails, try to resolve within the enclosing scope
  val excluded =
    excludesArgument.mapNotNull { it.resolvedClassId() }.takeUnless { it.isEmpty() }
      ?: excludesArgument.mapNotNull { it.resolvedArgumentConeKotlinType(typeResolver)?.classId }
  return excluded.toSet()
}

internal fun FirAnnotation.resolvedReplacedClassIds(
  session: FirSession,
  typeResolver: MetroFirTypeResolver,
): Set<ClassId> {
  val replacesArgument =
    replacesArgument(session)?.argumentList?.arguments?.mapNotNull {
      it.expectAsOrNull<FirGetClassCall>()
    } ?: return emptySet()
  val replaced = replacesArgument.mapNotNull { getClassCall ->
    getClassCall.resolveClassId(typeResolver)?.let {
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

internal fun FirGetClassCall.coneTypeIfResolved(): ConeKotlinType? {
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

internal fun FirAnnotation.classArgument(session: FirSession, name: Name, index: Int) =
  argumentAsOrNull<FirGetClassCall>(session, name, index)

internal fun FirAnnotation.annotationArgument(session: FirSession, name: Name, index: Int) =
  argumentAsOrNull<FirFunctionCall>(session, name, index)

internal inline fun <reified T : Any> FirAnnotation.argumentAsOrNull(
  session: FirSession,
  name: Name,
  index: Int,
): T? {
  return argumentAsOrNull(session, T::class.java, name, index)
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
internal fun <T : Any> FirAnnotation.argumentAsOrNull(
  session: FirSession,
  klass: Class<T>,
  name: Name,
  index: Int,
): T? {
  // Fast path: argumentMapping already has our name
  argumentMapping.mapping[name]?.let {
    @Suppress("UNCHECKED_CAST")
    return if (klass.isInstance(it)) it as T else null
  }

  if (this !is FirAnnotationCall || arguments.isEmpty()) return null

  // Try looking through named args directly
  for (arg in arguments) {
    if (arg is FirNamedArgumentExpression && arg.name == name) {
      @Suppress("UNCHECKED_CAST")
      return if (klass.isInstance(arg.expression)) arg.expression as T else null
    }
  }

  // When argumentMapping is populated, it contains all resolved arguments and is the source of
  // truth. Since our name was not found in it above, the argument is absent. The positional
  // fallback below is only needed for external/compiled declarations where mapping isn't available.
  if (argumentMapping.mapping.isNotEmpty()) return null

  // Fallback: resolve constructor params to map positional args back to names
  val classSymbol = toAnnotationClassLikeSymbol(session) as? FirRegularClassSymbol
  val ctorParams = classSymbol?.primaryConstructorIfAny(session)?.valueParameterSymbols
  if (ctorParams != null) {
    // Build complete mapping: for each argument, if it's named use
    // that name, otherwise use the constructor param name at that index
    for ((i, arg) in arguments.withIndex()) {
      val argName =
        when (arg) {
          is FirNamedArgumentExpression -> arg.name
          else -> ctorParams.getOrNull(i)?.name
        }
      if (argName == name) {
        val value =
          when (arg) {
            is FirNamedArgumentExpression -> arg.expression
            else -> arg
          }
        @Suppress("UNCHECKED_CAST")
        return if (klass.isInstance(value)) value as T else null
      }
    }
  }

  // Definitively not present
  return null
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

internal fun buildSimpleAnnotation(symbol: () -> FirClassSymbol<*>): FirAnnotation {
  return buildAnnotation {
    annotationTypeRef = symbol().defaultType().toFirResolvedTypeRef()

    argumentMapping = buildAnnotationArgumentMapping()
  }
}

internal fun buildHiddenFromObjCAnnotation(session: FirSession): FirAnnotation? {
  return session.metroFirBuiltIns.hiddenFromObjCClassSymbol?.let { buildSimpleAnnotation { it } }
}

/**
 * Builds `@JvmStatic` and `@JsStatic` annotations when [MetroOptions.generateStaticAnnotations] is
 * enabled and the annotations are present on the current classpath. Empty list otherwise.
 */
internal fun buildStaticAnnotations(session: FirSession): List<FirAnnotation> {
  if (!session.metroFirBuiltIns.options.generateStaticAnnotations) return emptyList()
  return buildList {
    session.metroFirBuiltIns.jvmStaticClassSymbol?.let { add(buildSimpleAnnotation { it }) }
    session.metroFirBuiltIns.jsStaticClassSymbol?.let { add(buildSimpleAnnotation { it }) }
  }
}

internal fun FirClass.isOrImplements(supertype: ClassId, session: FirSession): Boolean {
  if (classId == supertype) return true
  return implements(supertype, session)
}

internal fun FirClass.implements(supertype: ClassId, session: FirSession): Boolean {
  return implementsAny(session, setOf(supertype))
}

internal fun FirClass.implementsAny(session: FirSession, supertypes: Set<ClassId>): Boolean {
  return lookupSuperTypes(
      klass = this,
      lookupInterfaces = true,
      deep = true,
      useSiteSession = session,
      substituteTypes = true,
    )
    .any { it.expectAs<ConeKotlinType>().classId?.let { it in supertypes } == true }
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
    // TODO remove expectAs in 2.3.20
    .any { it.expectAs<ConeKotlinType>().classId?.let { it == supertype } == true }
}

internal fun FirClassLikeSymbol<*>.isBindingContainer(session: FirSession): Boolean {
  return when {
    isAnnotatedWithAny(session, session.classIds.bindingContainerAnnotations) -> true
    this is FirClassSymbol<*> && session.metroFirBuiltIns.options.enableGuiceRuntimeInterop -> {
      // Guice interop
      implements(GuiceSymbols.ClassIds.module, session)
    }
    else -> false
  }
}

internal fun ConeKotlinType.render(short: Boolean, includeAbbreviation: Boolean = !short): String {
  return buildString { renderType(short, this@render, includeAbbreviation) }
}

// Custom renderer that excludes annotations
internal fun StringBuilder.renderType(
  short: Boolean,
  type: ConeKotlinType,
  includeAbbreviation: Boolean = !short,
) {
  val abbreviatedType = if (includeAbbreviation) type.abbreviatedType else null
  if (abbreviatedType != null) {
    renderType(short, abbreviatedType, includeAbbreviation = false)
    append(" (typealias to ")
  }
  if (type.classId == Symbols.ClassIds.function0) {
    // the native renderer changes this format in later versions, so short-hand it for consistency
    append("() -> ")
    renderType(short, type.typeArguments[0].type!!, includeAbbreviation)
  } else {
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
  if (abbreviatedType != null) {
    append(')')
  }
}

// Original in kotlinc was removed
private class ConeIdRendererForDiagnostics : ConeIdRenderer() {
  override fun renderClassId(classId: ClassId) {
    builder.append(classId.asFqNameString())
  }

  override fun renderCallableId(callableId: CallableId) {
    builder.append(callableId.asSingleFqName().asString())
  }
}

context(context: CheckerContext)
internal fun FirClassSymbol<*>.nestedClasses(
  memberRequiredPhase: FirResolvePhase = FirResolvePhase.STATUS
): List<FirRegularClassSymbol> =
  nestedClasses(context.session, memberRequiredPhase = memberRequiredPhase)

internal fun FirClassSymbol<*>.nestedClasses(
  session: FirSession,
  memberRequiredPhase: FirResolvePhase = FirResolvePhase.STATUS,
): List<FirRegularClassSymbol> {
  val collected = mutableListOf<FirRegularClassSymbol>()
  declaredMemberScope(session, memberRequiredPhase = memberRequiredPhase).processAllClassifiers {
    symbol ->
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
internal fun FirClassSymbol<*>.callableSymbols(): List<FirCallableSymbol<*>> {
  val collected = mutableListOf<FirCallableSymbol<*>>()
  val scope =
    unsubstitutedScope(
      context.session,
      ScopeSession(),
      withForcedTypeCalculator = false,
      memberRequiredPhase = null,
    )
  scope.processAllCallables { collected += it }
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
        // TODO remove expectAs in 2.3.20
        it.classId != session.builtinTypes.anyType.coneType.expectAs<ConeKotlinType>().classId
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

internal val FirSession.allSessions: List<FirSession>
  get() = buildList {
    add(this@allSessions)
    for (transitive in moduleData.allDependsOnDependencies) {
      add(transitive.session)
    }
  }

internal fun FirClassSymbol<*>.originClassId(
  session: FirSession,
  typeResolver: MetroFirTypeResolver,
): ClassId? =
  annotationsIn(session, session.classIds.originAnnotations)
    .firstOrNull()
    ?.originArgument(session)
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

internal fun FirFieldSymbol.hasMetroDefault(session: FirSession): Boolean {
  return computeMetroDefault(
    behavior = session.metroFirBuiltIns.options.optionalBindingBehavior,
    isAnnotatedOptionalDep = {
      isAnnotatedWithAny(session, session.classIds.optionalBindingAnnotations)
    },
    hasDefaultValue = { this@hasMetroDefault.hasInitializer },
  )
}

internal fun FirPropertySymbol.hasMetroDefault(session: FirSession): Boolean {
  if (isLateInit) return false
  return computeMetroDefault(
    behavior = session.metroFirBuiltIns.options.optionalBindingBehavior,
    isAnnotatedOptionalDep = {
      isAnnotatedWithAny(session, session.classIds.optionalBindingAnnotations)
    },
    hasDefaultValue = { hasInitializer },
  )
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun FirSession.isCli(): Boolean {
  contract { returns(true) implies (this@isCli is FirCliSession) }
  return this is FirCliSession
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun FirSession.isIde(): Boolean {
  contract { returns(true) implies (this@isIde !is FirCliSession) }
  return this !is FirCliSession
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
      INTERFACE -> true
      CLASS -> !containingClass.isFinal && (isOpen || isAbstract)
      ENUM_CLASS,
      ENUM_ENTRY,
      ANNOTATION_CLASS,
      OBJECT -> false
    }
  }

/**
 * If this returns null, it's a valid container type. If this returns non-null, the string is the
 * error message.
 */
context(compatContext: CompatContext)
internal fun FirClassLikeSymbol<*>.bindingContainerErrorMessage(
  session: FirSession,
  alreadyCheckedAnnotation: Boolean = false,
): String? {
  return if (classId.isPlatformType()) {
    "Platform type '${classId.diagnosticString(session)}' is not a binding container."
  } else if (this is FirAnonymousObjectSymbol) {
    "Anonymous objects cannot be binding containers."
  } else if (with(compatContext) { isLocalCompat }) {
    "Local class '${classId.shortClassName}' cannot be a binding container."
  } else if (isInner) {
    "Inner class '${classId.diagnosticString(session)}' cannot be a binding container."
  } else if (
    !alreadyCheckedAnnotation && this is FirClassSymbol<*> && !isBindingContainer(session)
  ) {
    "'${classId.diagnosticString(session)}' is not a binding container."
  } else {
    null
  }
}

@OptIn(ClassIdBasedLocality::class) // For compat
internal inline val FirClassSymbol<*>.isLocalClassOrAnonymousObject: Boolean
  get() = classId.isLocal || this is FirAnonymousObjectSymbol

// Compat to avoid the deprecation warning in new context-based overloads in 2.3+
internal fun ConeClassLikeLookupTag.toClassSymbolCompat(s: FirSession): FirClassSymbol<*>? {
  return toClassSymbol(s)
}

internal fun ConeKotlinType.toClassSymbolCompat(s: FirSession): FirClassSymbol<*>? {
  return toClassSymbol(s)
}

internal fun ConeClassLikeLookupTag.toSymbolCompat(s: FirSession): FirClassLikeSymbol<*>? {
  return toSymbol(s)
}

/**
 * Result of resolving a `@DefaultBinding<T>` annotation. Contains the resolved type ref and an
 * optional qualifier annotation (which may come from the type argument or the annotated class).
 */
internal data class FirRefTypeKey(
  val typeRef: FirTypeRef,
  val qualifier: MetroFirAnnotation? = null,
)

/**
 * Resolves the default binding type from a supertype's `@DefaultBinding<T>` annotation.
 *
 * For same-module classes, reads the type argument from the annotation directly. For cross-module
 * classes, reads the return type of the `defaultBinding()` function in the `DefaultBindingMirror`
 * nested class.
 *
 * Returns the [ConeKotlinType] of the default binding, or null if none found.
 */
// TODO lookup tracking?
internal fun FirClassSymbol<*>.resolveDefaultBindingType(session: FirSession): ConeKotlinType? =
  resolveDefaultBindingTypeKey(session)?.typeRef?.coneTypeOrNull

/**
 * Like [resolveDefaultBindingType] but returns the [FirTypeRef] so callers can also read type
 * annotations (e.g., qualifier or map key annotations on the default binding type).
 */
internal fun FirClassSymbol<*>.resolveDefaultBindingTypeRef(session: FirSession): FirTypeRef? =
  resolveDefaultBindingTypeKey(session)?.typeRef

/**
 * Like [resolveDefaultBindingTypeRef] but returns a [FirRefTypeKey] that includes both the type ref
 * and an optional qualifier annotation. The qualifier may come from type annotations on the
 * `@DefaultBinding<T>` type argument, or from the annotated class itself.
 */
internal fun FirClassSymbol<*>.resolveDefaultBindingTypeKey(session: FirSession): FirRefTypeKey? {
  val annotation =
    getAnnotationByClassId(session.classIds.defaultBindingAnnotation, session) ?: return null
  // Source annotations are `FirAnnotationCall` and carry the `@DefaultBinding<T>` type argument.
  // Deserialized annotations (from `Library`, `Precompiled`, and other non-source origins during
  // incremental compilation) are plain `FirAnnotation`s without type arguments. For those we have
  // to read the type from the generated `DefaultBindingMirror` nested class.
  if (annotation !is FirAnnotationCall) {
    return resolveExternalDefaultBindingTypeKey(session)
  }

  // Try to read from @DefaultBinding annotation directly (same-module)
  val typeArg = annotation.typeArguments.firstOrNull() ?: return null
  val typeRef =
    when (typeArg) {
      is FirPlaceholderProjection,
      is FirStarProjection -> return null // Checked separately
      is FirTypeProjectionWithVariance -> typeArg.typeRef
    }
  // Qualifier can be on the type arg or the @DefaultBinding-annotated class
  val qualifier =
    typeRef.annotations.qualifierAnnotation(session)
      ?: resolvedCompilerAnnotationsWithClassIds.qualifierAnnotation(session)
  return FirRefTypeKey(typeRef, qualifier)
}

internal fun FirClassSymbol<*>.resolveExternalDefaultBindingTypeKey(
  session: FirSession
): FirRefTypeKey? {
  val mirrorSymbol =
    nestedClasses(session).firstOrNull { it.name == Symbols.Names.DefaultBindingMirrorClass }
      ?: return null

  // Read the return type of the defaultBinding() function
  val holderFunction =
    mirrorSymbol.declaredFunctions(session).firstOrNull {
      it.name == Symbols.Names.defaultBindingFunction
    } ?: return null
  // Qualifier is stored as an annotation on the mirror function
  val qualifier = holderFunction.qualifierAnnotation(session)
  return FirRefTypeKey(holderFunction.resolvedReturnTypeRef, qualifier)
}

/** Builds a resolved FirGetClassCall for a given ClassId. */
internal fun buildClassReference(session: FirSession, classId: ClassId): FirGetClassCall {
  val classSymbol =
    session.symbolProvider.getClassLikeSymbolByClassId(classId) as FirRegularClassSymbol
  val classType = classSymbol.defaultType()
  return buildGetClassCall {
    argumentList = buildArgumentList {
      arguments += buildResolvedQualifier {
        packageFqName = classId.packageFqName
        relativeClassFqName = classId.relativeClassName
        symbol = classSymbol
        resolvedToCompanionObject = false
        isFullyQualified = true
        coneTypeOrNull = classType
      }
    }
    coneTypeOrNull =
      ConeClassLikeTypeImpl(
        StandardClassIds.KClass.toLookupTag(),
        arrayOf(classType),
        isMarkedNullable = false,
      )
  }
}

/**
 * Indirection for printing a diagnostic string for a given [ClassId]. In the IDE it's not super
 * helpful to show long, verbose messages since they see it in context anyway.
 */
context(context: CheckerContext)
internal val ClassId.diagnosticString: String
  get() {
    return diagnosticString(context.session)
  }

/**
 * Indirection for printing a diagnostic string for a given [ClassId]. In the IDE it's not super
 * helpful to show long, verbose messages since they see it in context anyway.
 */
internal fun ClassId.diagnosticString(session: FirSession): String {
  return if (session.isIde()) {
    relativeClassName.asString()
  } else {
    asFqNameString()
  }
}

internal fun ClassId?.isIntrinsicType(session: FirSession): Boolean {
  contract { returns(true) implies (this@isIntrinsicType != null) }
  val classIds = session.metroFirBuiltIns.classIds
  return when (this) {
    in classIds.providerTypes,
    in classIds.lazyTypes -> true
    else -> false
  }
}
