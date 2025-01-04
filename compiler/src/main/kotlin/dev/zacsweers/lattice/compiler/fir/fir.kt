/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.compiler.fir

import dev.zacsweers.lattice.compiler.LatticeClassIds
import dev.zacsweers.lattice.compiler.LatticeSymbols
import java.util.Objects
import kotlin.collections.set
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.constructors
import org.jetbrains.kotlin.fir.declarations.getDeprecationsProvider
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.declarations.utils.superConeTypes
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.deserialization.toQualifiedPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirEmptyArgumentList
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
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.references.builder.buildResolvedNamedReference
import org.jetbrains.kotlin.fir.references.toResolvedPropertySymbol
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.scopes.jvm.computeJvmDescriptor
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.coneTypeSafe
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.platform.isJs
import org.jetbrains.kotlin.types.ConstantValueKind
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull

internal object LatticeKeys {
  data object Default : GeneratedDeclarationKey() {
    override fun toString() = "Default"
  }

  data object InstanceParameter : GeneratedDeclarationKey() {
    override fun toString() = "InstanceParameter"
  }

  data object ReceiverParameter : GeneratedDeclarationKey() {
    override fun toString() = "ReceiverParameter"
  }

  data object ValueParameter : GeneratedDeclarationKey() {
    override fun toString() = "ValueParameter"
  }

  data object ProviderFactoryClassDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "ProviderFactoryClassDeclaration"
  }

  data object ProviderNewInstanceFunction : GeneratedDeclarationKey() {
    override fun toString() = "ProviderNewInstanceFunction"
  }
}


internal fun FirAnnotationContainer.isAnnotatedWithAny(
  session: FirSession,
  names: Collection<ClassId>,
): Boolean {
  return names.any { hasAnnotation(it, session) }
}

internal fun FirBasedSymbol<*>.annotationsIn(
  session: FirSession,
  names: Set<ClassId>,
): Sequence<FirAnnotation> {
  return resolvedAnnotationsWithClassIds.annotationsIn(session, names)
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
  return annotations.any { it.toAnnotationClassIdSafe(session) in names }
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

@OptIn(SymbolInternals::class) // TODO is there a non-internal API?
internal fun FirClass.allSuperTypeConeRefs(session: FirSession): Sequence<ConeClassLikeType> {
  return sequence {
    yieldAll(superConeTypes)
    for (supertype in superConeTypes) {
      val clazz = supertype.toClassSymbol(session)
      clazz?.resolvedSuperTypeRefs?.mapNotNull { it.coneTypeSafe() }
    }
  }
}

@OptIn(SymbolInternals::class) // TODO is there a non-internal API?
internal fun FirClass.allFunctions(session: FirSession): Sequence<FirFunction> {
  return sequence {
    yieldAll(declarations.filterIsInstance<FirFunction>())
    yieldAll(
      allSuperTypeConeRefs(session)
        .mapNotNull { it.toClassSymbol(session)?.fir }
        .flatMap { it.allFunctions(session) }
    )
  }
}

internal fun FirClass.abstractFunctions(session: FirSession): List<FirFunction> {
  return allFunctions(session)
    // Merge inherited functions with matching signatures
    .groupBy {
      // Don't include the return type because overrides may have different ones
      it.computeJvmDescriptor(includeReturnType = false)
    }
    .mapValues { (_, functions) ->
      val (abstract, implemented) =
        functions.partition {
          it.modality == Modality.ABSTRACT &&
            it.body == null &&
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
        abstract[0]
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
): FirFunction {
  val abstractFunctions = abstractFunctions(session)
  if (abstractFunctions.size != 1) {
    if (abstractFunctions.isEmpty()) {
      reporter.reportOn(
        source,
        FirLatticeErrors.FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION,
        type,
        "none",
        context,
      )
    } else {
      // Report each function
      for (abstractFunction in abstractFunctions) {
        reporter.reportOn(
          abstractFunction.source,
          FirLatticeErrors.FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION,
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
      FirLatticeErrors.LATTICE_DECLARATION_VISIBILITY_ERROR,
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

internal inline fun FirClassLikeDeclaration.findInjectConstructor(
  session: FirSession,
  latticeClassIds: LatticeClassIds,
  context: CheckerContext,
  reporter: DiagnosticReporter,
  onError: () -> Nothing,
): FirConstructorSymbol? {
  if (this !is FirClass) return null
  val constructorInjections =
    constructors(session).filter {
      it.annotations.isAnnotatedWithAny(session, latticeClassIds.injectAnnotations)
    }
  return when (constructorInjections.size) {
    0 -> null
    1 -> {
      constructorInjections[0].also {
        val isAssisted =
          it.annotations.isAnnotatedWithAny(session, latticeClassIds.assistedAnnotations)
        if (!isAssisted && it.valueParameterSymbols.isEmpty()) {
          reporter.reportOn(
            it.annotations
              .annotationsIn(session, latticeClassIds.injectAnnotations)
              .single()
              .source,
            FirLatticeErrors.SUGGEST_CLASS_INJECTION_IF_NO_PARAMS,
            context,
          )
        }
      }
    }
    else -> {
      reporter.reportOn(
        constructorInjections[0]
          .annotations
          .annotationsIn(session, latticeClassIds.injectAnnotations)
          .single()
          .source,
        FirLatticeErrors.CANNOT_HAVE_MULTIPLE_INJECTED_CONSTRUCTORS,
        context,
      )
      onError()
    }
  }
}

internal inline fun FirClass.validateInjectedClass(
  context: CheckerContext,
  reporter: DiagnosticReporter,
  onError: () -> Nothing,
) {
  if (isLocal) {
    reporter.reportOn(source, FirLatticeErrors.LOCAL_CLASSES_CANNOT_BE_INJECTED, context)
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
          reporter.reportOn(source, FirLatticeErrors.ONLY_FINAL_CLASSES_CAN_BE_INJECTED, context)
          onError()
        }
      }
    }
    else -> {
      reporter.reportOn(source, FirLatticeErrors.ONLY_CLASSES_CAN_BE_INJECTED, context)
      onError()
    }
  }

  checkVisibility { source ->
    reporter.reportOn(source, FirLatticeErrors.INJECTED_CLASSES_MUST_BE_VISIBLE, context)
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
      FirLatticeErrors.LATTICE_DECLARATION_ERROR,
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
            FirLatticeErrors.LATTICE_DECLARATION_ERROR,
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
            FirLatticeErrors.LATTICE_DECLARATION_ERROR,
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
        FirLatticeErrors.LATTICE_DECLARATION_ERROR,
        "$type classes should be non-sealed abstract classes or interfaces.",
        context,
      )
      onError()
    }
  }

  checkVisibility { source ->
    reporter.reportOn(source, FirLatticeErrors.LATTICE_DECLARATION_VISIBILITY_ERROR, type, context)
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
    reporter.reportOn(source, FirLatticeErrors.LATTICE_DECLARATION_VISIBILITY_ERROR, type, context)
    onError()
  }
}

internal fun List<FirAnnotation>.qualifierAnnotation(session: FirSession): LatticeFirAnnotation? =
  asSequence().annotationAnnotatedWithAny(session, session.latticeClassIds.qualifierAnnotations)

internal fun List<FirAnnotation>.scopeAnnotation(session: FirSession): LatticeFirAnnotation? =
  asSequence().scopeAnnotation(session)

internal fun Sequence<FirAnnotation>.scopeAnnotation(session: FirSession): LatticeFirAnnotation? =
  annotationAnnotatedWithAny(session, session.latticeClassIds.scopeAnnotations)

// TODO add a single = true|false param? How would we propagate errors
internal fun Sequence<FirAnnotation>.annotationAnnotatedWithAny(
  session: FirSession,
  names: Set<ClassId>,
): LatticeFirAnnotation? {
  return filterIsInstance<FirAnnotationCall>()
    .firstOrNull { annotationCall -> annotationCall.isAnnotatedWithAny(session, names) }
    ?.let { LatticeFirAnnotation(it) }
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
  val jsExportIgnore = session.symbolProvider.getClassLikeSymbolByClassId(
    LatticeSymbols.ClassIds.jsExportIgnore
  )
  val jsExportIgnoreAnnotation = jsExportIgnore as? FirRegularClassSymbol ?: return
  val jsExportIgnoreConstructor = jsExportIgnoreAnnotation.declarationSymbols.firstIsInstanceOrNull<FirConstructorSymbol>() ?: return

  val jsExportIgnoreAnnotationCall = buildAnnotationCall {
    argumentList = FirEmptyArgumentList
    annotationTypeRef = buildResolvedTypeRef {
      coneType = jsExportIgnoreAnnotation.defaultType()
    }
    calleeReference = buildResolvedNamedReference {
      name = jsExportIgnoreAnnotation.name
      resolvedSymbol = jsExportIgnoreConstructor
    }

    containingDeclarationSymbol = this@excludeFromJsExport.symbol
  }

  replaceAnnotations(annotations + jsExportIgnoreAnnotationCall)
}

internal fun createDeprecatedHiddenAnnotation(session: FirSession): FirAnnotation = buildAnnotation {
  val deprecatedAnno =
    session.symbolProvider.getClassLikeSymbolByClassId(StandardClassIds.Annotations.Deprecated) as FirRegularClassSymbol

  annotationTypeRef = deprecatedAnno.defaultType().toFirResolvedTypeRef()

  argumentMapping = buildAnnotationArgumentMapping {
    mapping[Name.identifier("message")] = buildLiteralExpression(
      null,
      ConstantValueKind.String,
      "This synthesized declaration should not be used directly",
      setType = true
    )

    // It has nothing to do with enums deserialization, but it is simply easier to build it this way.
    mapping[Name.identifier("level")] = buildEnumEntryDeserializedAccessExpression {
      enumClassId = StandardClassIds.DeprecationLevel
      enumEntryName = Name.identifier("HIDDEN")
    }.toQualifiedPropertyAccessExpression(session)
  }
}

internal fun FirClassLikeDeclaration.markAsDeprecatedHidden(session: FirSession) {
  replaceAnnotations(annotations + listOf(createDeprecatedHiddenAnnotation(session)))
  replaceDeprecationsProvider(this.getDeprecationsProvider(session))
}

internal fun ConeTypeProjection.wrapInProvider() = LatticeSymbols.ClassIds.latticeProvider.constructClassLikeType(arrayOf(this))