// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.mapToArray
import java.util.Objects
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
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
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.deserialization.toQualifiedPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirArrayLiteral
import org.jetbrains.kotlin.fir.expressions.FirClassReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirEmptyArgumentList
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationCall
import org.jetbrains.kotlin.fir.expressions.builder.buildEnumEntryDeserializedAccessExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildLiteralExpression
import org.jetbrains.kotlin.fir.expressions.unexpandedClassId
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension.TypeResolveService
import org.jetbrains.kotlin.fir.extensions.buildUserTypeFromQualifierParts
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.references.builder.buildResolvedNamedReference
import org.jetbrains.kotlin.fir.references.impl.FirSimpleNamedReference
import org.jetbrains.kotlin.fir.references.toResolvedPropertySymbol
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.getSuperTypes
import org.jetbrains.kotlin.fir.resolve.lookupSuperTypes
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.scopes.jvm.computeJvmDescriptor
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirBackingFieldSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertyAccessorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.types.isResolved
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.platform.isJs
import org.jetbrains.kotlin.types.ConstantValueKind
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull

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

internal fun FirBasedSymbol<*>.isAnnotatedWithAny(
  session: FirSession,
  names: Set<ClassId>,
): Boolean {
  return annotations.filter { it.isResolved }.any { it.toAnnotationClassIdSafe(session) in names }
}

internal fun FirBasedSymbol<*>.findAnnotation(
  session: FirSession,
  names: Set<ClassId>,
): FirAnnotation? {
  return annotations.filter { it.isResolved }.find { it.toAnnotationClassIdSafe(session) in names }
}

internal fun List<FirAnnotation>.isAnnotatedWithAny(
  session: FirSession,
  names: Set<ClassId>,
): Boolean {
  return annotationsIn(session, names).any()
}

internal inline fun FirMemberDeclaration.checkVisibility(
  onError: (source: KtSourceElement?) -> Nothing
) {
  visibility.checkVisibility(source, onError)
}

internal inline fun FirCallableSymbol<*>.checkVisibility(
  onError: (source: KtSourceElement?) -> Nothing
) {
  visibility.checkVisibility(source, onError)
}

internal inline fun Visibility.checkVisibility(
  source: KtSourceElement?,
  onError: (source: KtSourceElement?) -> Nothing,
) {
  // TODO what about expect/actual/protected
  when (this) {
    Visibilities.Public,
    Visibilities.Internal -> {
      // These are fine
      // TODO what about across modules? Is internal really ok? Or PublishedApi?
    }
    else -> {
      onError(source)
    }
  }
}

internal fun FirClassSymbol<*>.allFunctions(session: FirSession): Sequence<FirNamedFunctionSymbol> {
  return sequence {
    yieldAll(declarationSymbols.filterIsInstance<FirNamedFunctionSymbol>())
    yieldAll(
      lookupSuperTypes(
          symbol = this@allFunctions,
          lookupInterfaces = true,
          deep = true,
          useSiteSession = session,
        )
        .mapNotNull { it.toClassSymbol(session) }
        .flatMap { it.allFunctions(session) }
    )
  }
}

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
      yieldAll(
        getSuperTypes(session)
          .asSequence()
          .mapNotNull { it.toClassSymbol(session) }
          .flatMap {
            // If we're recursing up, we no longer want to include ancestors because we're handling
            // that here
            it.callableDeclarations(
              session = session,
              includeSelf = true,
              includeAncestors = false,
              yieldAncestorsFirst = yieldAncestorsFirst,
            )
          }
      )
    }
    if (includeSelf && yieldAncestorsFirst) {
      yieldAll(declaredMembers)
    }
  }
}

@OptIn(SymbolInternals::class) // TODO is there a non-internal API?
internal fun FirClassSymbol<*>.abstractFunctions(
  session: FirSession
): List<FirNamedFunctionSymbol> {
  return allFunctions(session)
    // Merge inherited functions with matching signatures
    .groupBy {
      // Don't include the return type because overrides may have different ones
      it.fir.computeJvmDescriptor(includeReturnType = false)
    }
    .mapValues { (_, functions) ->
      val (abstract, implemented) =
        functions.partition {
          it.modality == Modality.ABSTRACT &&
            it.fir.body == null &&
            (it.visibility == Visibilities.Public || it.visibility == Visibilities.Protected)
        }
      if (abstract.isEmpty()) {
        // All implemented, nothing to do
        null
      } else if (implemented.isNotEmpty()) {
        // If there's one implemented one, it's not abstract anymore in our materialized type
        null
      } else {
        // Only need one for the rest of this
        abstract.first {
          // If it's declared in our class, grab that one. Otherwise grab the first non-overridden
          // one
          it.getContainingClassSymbol() == this || !it.isOverride
        }
      }
    }
    .values
    .filterNotNull()
}

internal inline fun FirClass.singleAbstractFunction(
  session: FirSession,
  context: CheckerContext,
  reporter: DiagnosticReporter,
  type: String,
  onError: () -> Nothing,
): FirNamedFunctionSymbol {
  val abstractFunctions = symbol.abstractFunctions(session)
  if (abstractFunctions.size != 1) {
    if (abstractFunctions.isEmpty()) {
      reporter.reportOn(
        source,
        FirMetroErrors.FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION,
        type,
        "none",
        context,
      )
    } else {
      // Report each function
      for (abstractFunction in abstractFunctions) {
        reporter.reportOn(
          abstractFunction.source,
          FirMetroErrors.FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION,
          type,
          abstractFunctions.size.toString(),
          context,
        )
      }
    }
    onError()
  }

  val function = abstractFunctions.single()
  function.checkVisibility { source ->
    reporter.reportOn(
      source,
      FirMetroErrors.METRO_DECLARATION_VISIBILITY_ERROR,
      "$type classes' single abstract functions",
      context,
    )
    onError()
  }
  return function
}

/**
 * Computes a hash key for this annotation instance composed of its underlying type and value
 * arguments.
 */
internal fun FirAnnotationCall.computeAnnotationHash(): Int {
  return Objects.hash(
    resolvedType.classId,
    arguments
      .map {
        when (it) {
          is FirLiteralExpression -> it.value
          is FirGetClassCall -> {
            val argument = it.argument
            if (argument is FirResolvedQualifier) {
              argument.classId
            } else {
              argument.resolvedType.classId
            }
          }
          // Enum entry reference
          is FirPropertyAccessExpression -> {
            it.calleeReference
              .toResolvedPropertySymbol()
              ?.receiverParameter
              ?.typeRef
              ?.coneTypeOrNull
              ?.classId
          }
          else -> {
            error("Unexpected annotation argument type: ${it::class.java} - ${it.render()}")
          }
        }
      }
      .toTypedArray()
      .contentDeepHashCode(),
  )
}

internal inline fun FirClassSymbol<*>.findInjectConstructor(
  session: FirSession,
  context: CheckerContext,
  reporter: DiagnosticReporter,
  checkClass: Boolean,
  onError: () -> Nothing,
): FirConstructorSymbol? {
  val constructorInjections = findInjectConstructors(session, checkClass = checkClass)
  return when (constructorInjections.size) {
    0 -> null
    1 -> {
      constructorInjections[0].also {
        if (it.isPrimary) {
          val isAssisted =
            it.annotations.isAnnotatedWithAny(session, session.classIds.assistedAnnotations)
          if (!isAssisted && it.valueParameterSymbols.isEmpty()) {
            reporter.reportOn(
              it.annotations
                .annotationsIn(session, session.classIds.injectAnnotations)
                .single()
                .source,
              FirMetroErrors.SUGGEST_CLASS_INJECTION_IF_NO_PARAMS,
              context,
            )
          }
        }
      }
    }
    else -> {
      reporter.reportOn(
        constructorInjections[0]
          .annotations
          .annotationsIn(session, session.classIds.injectAnnotations)
          .single()
          .source,
        FirMetroErrors.CANNOT_HAVE_MULTIPLE_INJECTED_CONSTRUCTORS,
        context,
      )
      onError()
    }
  }
}

internal fun FirClassLikeSymbol<*>.findInjectConstructors(
  session: FirSession,
  checkClass: Boolean = true,
): List<FirConstructorSymbol> {
  if (this !is FirClassSymbol<*>) return emptyList()
  if (classKind != ClassKind.CLASS) return emptyList()
  rawStatus.modality?.let { if (it.ordinal > 0) return emptyList() }
  return if (checkClass && isAnnotatedInject(session)) {
    declarationSymbols.filterIsInstance<FirConstructorSymbol>().filter { it.isPrimary }
  } else {
    declarationSymbols.filterIsInstance<FirConstructorSymbol>().filter {
      it.annotations.isAnnotatedWithAny(session, session.classIds.injectAnnotations)
    }
  }
}

internal inline fun FirClass.validateInjectedClass(
  context: CheckerContext,
  reporter: DiagnosticReporter,
  onError: () -> Nothing,
) {
  if (isLocal) {
    reporter.reportOn(source, FirMetroErrors.LOCAL_CLASSES_CANNOT_BE_INJECTED, context)
    onError()
  }

  when (classKind) {
    ClassKind.CLASS -> {
      when (modality) {
        Modality.FINAL -> {
          // This is fine
        }
        else -> {
          // open/sealed/abstract
          reporter.reportOn(source, FirMetroErrors.ONLY_FINAL_CLASSES_CAN_BE_INJECTED, context)
          onError()
        }
      }
    }
    else -> {
      reporter.reportOn(source, FirMetroErrors.ONLY_CLASSES_CAN_BE_INJECTED, context)
      onError()
    }
  }

  checkVisibility { source ->
    reporter.reportOn(source, FirMetroErrors.INJECTED_CLASSES_MUST_BE_VISIBLE, context)
    onError()
  }
}

internal fun FirCallableDeclaration.allAnnotations(): Sequence<FirAnnotation> {
  return sequence {
    yieldAll(annotations)
    if (this@allAnnotations is FirProperty) {
      yieldAll(backingField?.annotations.orEmpty())
      getter?.annotations?.let { yieldAll(it) }
      setter?.annotations?.let { yieldAll(it) }
    }
  }
}

internal inline fun FirClass.validateApiDeclaration(
  context: CheckerContext,
  reporter: DiagnosticReporter,
  type: String,
  onError: () -> Nothing,
) {
  if (isLocal) {
    reporter.reportOn(
      source,
      FirMetroErrors.METRO_DECLARATION_ERROR,
      "$type classes cannot be local classes.",
      context,
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
            FirMetroErrors.METRO_DECLARATION_ERROR,
            "$type classes should be non-sealed abstract classes or interfaces.",
            context,
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
            FirMetroErrors.METRO_DECLARATION_ERROR,
            "$type classes should be non-sealed abstract classes or interfaces.",
            context,
          )
          onError()
        }
      }
    }
    else -> {
      reporter.reportOn(
        source,
        FirMetroErrors.METRO_DECLARATION_ERROR,
        "$type classes should be non-sealed abstract classes or interfaces.",
        context,
      )
      onError()
    }
  }

  checkVisibility { source ->
    reporter.reportOn(source, FirMetroErrors.METRO_DECLARATION_VISIBILITY_ERROR, type, context)
    onError()
  }
  if (isAbstract && classKind == ClassKind.CLASS) {
    primaryConstructorIfAny(context.session)?.validateVisibility(
      context,
      reporter,
      "$type classes' primary constructor",
    ) {
      onError()
    }
  }
}

internal inline fun FirConstructorSymbol.validateVisibility(
  context: CheckerContext,
  reporter: DiagnosticReporter,
  type: String,
  onError: () -> Nothing,
) {
  checkVisibility { source ->
    reporter.reportOn(source, FirMetroErrors.METRO_DECLARATION_VISIBILITY_ERROR, type, context)
    onError()
  }
}

internal fun FirBasedSymbol<*>.qualifierAnnotation(session: FirSession): MetroFirAnnotation? =
  annotations.qualifierAnnotation(session)

internal fun List<FirAnnotation>.qualifierAnnotation(session: FirSession): MetroFirAnnotation? =
  asSequence().annotationAnnotatedWithAny(session, session.classIds.qualifierAnnotations)

internal fun FirBasedSymbol<*>.mapKeyAnnotation(session: FirSession): MetroFirAnnotation? =
  annotations.mapKeyAnnotation(session)

internal fun List<FirAnnotation>.mapKeyAnnotation(session: FirSession): MetroFirAnnotation? =
  asSequence().annotationAnnotatedWithAny(session, session.classIds.mapKeyAnnotations)

internal fun List<FirAnnotation>.scopeAnnotation(session: FirSession): MetroFirAnnotation? =
  asSequence().scopeAnnotation(session)

internal fun Sequence<FirAnnotation>.scopeAnnotation(session: FirSession): MetroFirAnnotation? =
  annotationAnnotatedWithAny(session, session.classIds.scopeAnnotations)

// TODO add a single = true|false param? How would we propagate errors
internal fun Sequence<FirAnnotation>.annotationAnnotatedWithAny(
  session: FirSession,
  names: Set<ClassId>,
): MetroFirAnnotation? {
  return filter { it.isResolved }
    .filterIsInstance<FirAnnotationCall>()
    .firstOrNull { annotationCall -> annotationCall.isAnnotatedWithAny(session, names) }
    ?.let { MetroFirAnnotation(it) }
}

internal fun FirAnnotationCall.isQualifier(session: FirSession): Boolean {
  return isAnnotatedWithAny(session, session.classIds.qualifierAnnotations)
}

internal fun FirAnnotationCall.isMapKey(session: FirSession): Boolean {
  return isAnnotatedWithAny(session, session.classIds.mapKeyAnnotations)
}

internal fun FirAnnotationCall.isAnnotatedWithAny(
  session: FirSession,
  names: Set<ClassId>,
): Boolean {
  val annotationType = resolvedType as? ConeClassLikeType ?: return false
  val annotationClass = annotationType.toClassSymbol(session) ?: return false
  return annotationClass.annotations.isAnnotatedWithAny(session, names)
}

internal fun FirDeclaration.excludeFromJsExport(session: FirSession) {
  if (!session.moduleData.platform.isJs()) {
    return
  }
  val jsExportIgnore =
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.jsExportIgnore)
  val jsExportIgnoreAnnotation = jsExportIgnore as? FirRegularClassSymbol ?: return
  val jsExportIgnoreConstructor =
    jsExportIgnoreAnnotation.declarationSymbols.firstIsInstanceOrNull<FirConstructorSymbol>()
      ?: return

  val jsExportIgnoreAnnotationCall = buildAnnotationCall {
    argumentList = FirEmptyArgumentList
    annotationTypeRef = buildResolvedTypeRef { coneType = jsExportIgnoreAnnotation.defaultType() }
    calleeReference = buildResolvedNamedReference {
      name = jsExportIgnoreAnnotation.name
      resolvedSymbol = jsExportIgnoreConstructor
    }

    containingDeclarationSymbol = this@excludeFromJsExport.symbol
  }

  replaceAnnotations(annotations + jsExportIgnoreAnnotationCall)
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

internal fun FirClassLikeDeclaration.markAsDeprecatedHidden(session: FirSession) {
  replaceAnnotations(annotations + listOf(createDeprecatedHiddenAnnotation(session)))
  replaceDeprecationsProvider(this.getDeprecationsProvider(session))
}

internal fun ConeTypeProjection.wrapInProviderIfNecessary(session: FirSession): ConeClassLikeType {
  val type = this.type
  if (type is ConeClassLikeType) {
    val classId = type.lookupTag.classId
    if (classId in session.classIds.providerTypes) {
      // Already a provider
      return type
    }
  }
  return Symbols.ClassIds.metroProvider.constructClassLikeType(arrayOf(this))
}

internal fun ConeTypeProjection.wrapInLazyIfNecessary(session: FirSession): ConeClassLikeType {
  val type = this.type
  if (type is ConeClassLikeType) {
    val classId = type.lookupTag.classId
    if (classId in session.classIds.lazyTypes) {
      // Already a lazy
      return type
    }
  }
  return Symbols.ClassIds.lazy.constructClassLikeType(arrayOf(this))
}

internal fun FirClassSymbol<*>.constructType(
  typeParameterRefs: List<FirTypeParameterRef>
): ConeClassLikeType {
  return constructType(typeParameterRefs.mapToArray { it.symbol.toConeType() })
}

// Annoyingly, FirDeclarationOrigin.Plugin does not implement equals()
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

internal fun FirBasedSymbol<*>.requireContainingClassSymbol(): FirClassLikeSymbol<*> =
  getContainingClassSymbol() ?: error("No containing class symbol found for $this")

internal val ClassId.hintCallableId: CallableId
  get() {
    val simpleName =
      sequence {
          yieldAll(packageFqName.pathSegments())
          yieldAll(relativeClassName.pathSegments())
        }
        .joinToString(separator = "") { it.asString().capitalizeUS() }
        .decapitalizeUS()
        .asName()
    return CallableId(Symbols.FqNames.metroHintsPackage, simpleName)
  }

private val FirPropertyAccessExpression.qualifierName: Name?
  get() = (calleeReference as? FirSimpleNamedReference)?.name

internal fun FirAnnotation.scopeArgument() = classArgument("scope".asName(), index = 0)

internal fun FirAnnotation.additionalScopesArgument() =
  argumentAsOrNull<FirArrayLiteral>("additionalScopes".asName(), index = 1)

internal fun FirAnnotation.excludesArgument() =
  argumentAsOrNull<FirArrayLiteral>("excludes".asName(), index = 2)

internal fun FirAnnotation.replacesArgument() =
  argumentAsOrNull<FirArrayLiteral>("replaces".asName(), index = 1)

internal fun FirAnnotation.boundTypeArgument() = annotationArgument("boundType".asName(), index = 2)

internal fun FirAnnotation.resolvedBoundType(session: FirSession): FirTypeRef? {
  // Return a BoundType defined using metro api's
  boundTypeArgument()?.let { boundType ->
    return boundType.typeArguments[0].expectAsOrNull<FirTypeProjectionWithVariance>()?.typeRef
  }
  // Return a boundType defined using anvil KClass
  return kClassBoundTypeArgument(session)
}

internal fun FirAnnotation.kClassBoundTypeArgument(session: FirSession): FirTypeRef? {
  return getAnnotationKClassArgument("boundType".asName(), session)?.toFirResolvedTypeRef()
}

internal fun FirAnnotation.getAnnotationKClassArgument(
  name: Name,
  session: FirSession,
): ConeKotlinType? {
  val argument = findArgumentByName(name) ?: return null
  val getClassCall = argument.evaluateAs<FirGetClassCall>(session) ?: return null
  return getClassCall.getTargetType()
}

internal fun FirAnnotation.resolvedScopeClassId() = scopeArgument()?.resolvedClassId()

internal fun FirAnnotation.resolvedScopeClassId(typeResolver: TypeResolveService): ClassId? {
  val scopeArgument = scopeArgument() ?: return null
  // Try to resolve it normally first. If this fails,
  // try to resolve within the enclosing scope
  return scopeArgument.resolvedClassId()
    ?: scopeArgument.resolvedClassArgumentTarget(typeResolver)?.classId
}

internal fun FirAnnotation.resolvedAdditionalScopesClassIds() =
  additionalScopesArgument()?.argumentList?.arguments?.mapNotNull {
    it.expectAsOrNull<FirGetClassCall>()?.resolvedClassId()
  }

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
    ?: additionalScopes.mapNotNull { it.resolvedClassArgumentTarget(typeResolver)?.classId }
}

internal fun FirAnnotation.resolvedExcludedClassIds(
  typeResolver: TypeResolveService
): Set<ClassId> {
  val excludesArgument =
    excludesArgument()?.argumentList?.arguments?.mapNotNull { it.expectAsOrNull<FirGetClassCall>() }
      ?: return emptySet()
  // Try to resolve it normally first. If this fails,
  // try to resolve within the enclosing scope
  val excluded =
    excludesArgument.mapNotNull { it.resolvedClassId() }.takeUnless { it.isEmpty() }
      ?: excludesArgument.mapNotNull { it.resolvedClassArgumentTarget(typeResolver)?.classId }
  return excluded.toSet()
}

internal fun FirAnnotation.resolvedReplacedClassIds(
  typeResolver: TypeResolveService
): Set<ClassId> {
  val replacesArgument =
    replacesArgument()?.argumentList?.arguments?.mapNotNull { it.expectAsOrNull<FirGetClassCall>() }
      ?: return emptySet()
  // Try to resolve it normally first. If this fails,
  // try to resolve within the enclosing scope
  val replaced =
    replacesArgument.mapNotNull { it.resolvedClassId() }.takeUnless { it.isEmpty() }
      ?: replacesArgument.mapNotNull { it.resolvedClassArgumentTarget(typeResolver)?.classId }
  return replaced.toSet()
}

internal fun FirGetClassCall.resolvedClassId() = (argument as? FirResolvedQualifier)?.classId

internal fun FirAnnotation.resolvedClassArgumentTarget(
  name: Name,
  index: Int,
  typeResolver: TypeResolveService,
): ConeKotlinType? {
  // TODO if the annotation is resolved we can skip ahead
  val getClassCall = argumentAsOrNull<FirGetClassCall>(name, index) ?: return null
  return getClassCall.resolvedClassArgumentTarget(typeResolver)
}

internal fun FirGetClassCall.resolvedClassArgumentTarget(
  typeResolver: TypeResolveService
): ConeKotlinType? {
  if (isResolved) {
    return (argument as? FirClassReferenceExpression?)?.classTypeRef?.coneTypeOrNull
  }

  val typeToResolve =
    buildUserTypeFromQualifierParts(isMarkedNullable = false) {
      fun visitQualifiers(expression: FirExpression) {
        if (expression !is FirPropertyAccessExpression) return
        expression.explicitReceiver?.let { visitQualifiers(it) }
        expression.qualifierName?.let { part(it) }
      }
      visitQualifiers(argument)
    }

  val resolvedArgument = typeResolver.resolveUserType(typeToResolve).coneType
  return resolvedArgument
}

internal fun FirAnnotation.classArgument(name: Name, index: Int) =
  argumentAsOrNull<FirGetClassCall>(name, index)

internal fun FirAnnotation.annotationArgument(name: Name, index: Int) =
  argumentAsOrNull<FirFunctionCall>(name, index)

internal inline fun <reified T> FirAnnotation.argumentAsOrNull(name: Name, index: Int): T? {
  findArgumentByName(name)?.let {
    return it as? T?
  }
  if (this !is FirAnnotationCall) return null
  // Fall back to the index if necessary
  return arguments.getOrNull(index) as? T?
}

internal fun List<FirElement>.joinToRender(separator: String = ", "): String {
  return joinToString(separator) {
    buildString {
      append(it.render())
      if (it is FirAnnotation) {
        append(" resolved=${it.isResolved}")
        append(" unexpandedClassId=${it.unexpandedClassId}")
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
