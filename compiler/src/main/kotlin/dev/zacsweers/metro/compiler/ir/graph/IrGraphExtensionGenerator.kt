// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import androidx.collection.MutableScatterMap
import dev.zacsweers.metro.compiler.NESTED_CLASS_BINARY_NAME_LIMIT
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.generatedNameIdentity
import dev.zacsweers.metro.compiler.ir.IrBindingContainerResolver
import dev.zacsweers.metro.compiler.ir.IrContributionMerger
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.linkDeclarationsInCompilation
import dev.zacsweers.metro.compiler.ir.lookupClass
import dev.zacsweers.metro.compiler.ir.nestedClassOrNull
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import dev.zacsweers.metro.compiler.ir.typeRemapperFor
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.safeNestedSimpleName
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.toSafeIdentifier
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import okio.utf8Size
import org.jetbrains.kotlin.backend.common.serialization.cityHash64
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.remapTypes
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal class IrGraphExtensionGenerator(
  context: IrMetroContext,
  private val contributionMerger: IrContributionMerger,
  private val bindingContainerResolver: IrBindingContainerResolver,
  private val parentGraph: IrClass,
) : IrMetroContext by context {

  // The main compiler thread builds and caches extensions for this parent graph.
  private val generatedClassesCache = MutableScatterMap<CacheKey, IrClass>()

  private data class CacheKey(val typeKey: IrTypeKey, val parentGraph: ClassId)

  context(traceScope: TraceScope)
  fun getOrBuildGraphExtensionImpl(
    typeKey: IrTypeKey,
    parentGraph: IrClass,
    contributedAccessor: MetroSimpleFunction,
  ): IrClass {
    return generatedClassesCache.getOrPut(CacheKey(typeKey, parentGraph.classIdOrFail)) {
      val sourceGraph = typeKey.type.rawType()
      linkDeclarationsInCompilation(parentGraph, sourceGraph)

      val sourceSamFunction =
        contributedAccessor.ir
          .overriddenSymbolsSequence()
          .firstOrNull {
            it.owner.parentAsClass.isAnnotatedWithAny(
              metroSymbols.classIds.graphExtensionFactoryAnnotations,
            )
          }
          ?.owner ?: contributedAccessor.ir

      val parent = sourceSamFunction.parentClassOrNull ?: reportCompilerBug("No parent class found")
      val isFactorySAM =
        parent.isAnnotatedWithAny(metroSymbols.classIds.graphExtensionFactoryAnnotations)
      if (isFactorySAM) {
        generateImplFromFactory(sourceSamFunction, typeKey)
      } else {
        val returnType = contributedAccessor.ir.returnType.rawType()
        val returnIsGraphExtensionFactory =
          returnType.isAnnotatedWithAny(metroSymbols.classIds.graphExtensionFactoryAnnotations)
        val returnIsGraphExtension =
          returnType.isAnnotatedWithAny(metroSymbols.classIds.graphExtensionAnnotations)
        if (returnIsGraphExtensionFactory) {
          val samFunction =
            returnType.singleAbstractFunction().apply {
              remapTypes(sourceSamFunction.typeRemapperFor(contributedAccessor.ir.returnType))
            }
          generateImplFromFactory(samFunction, typeKey)
        } else if (returnIsGraphExtension) {
          // Simple case with no creator
          generateImpl(returnType, creatorFunction = null, typeKey)
        } else {
          reportCompilerBug("Not a graph extension: ${returnType.kotlinFqName}")
        }
      }
    }
  }

  context(traceScope: TraceScope)
  private fun generateImplFromFactory(
    factoryFunction: IrSimpleFunction,
    typeKey: IrTypeKey,
  ): IrClass {
    val sourceFactory = factoryFunction.parentAsClass
    val sourceGraph = sourceFactory.parentAsClass
    return trace("Generate graph extension ${sourceGraph.name}") {
      generateImpl(sourceGraph = sourceGraph, creatorFunction = factoryFunction, typeKey = typeKey)
    }
  }

  context(traceScope: TraceScope)
  private fun generateImpl(
    sourceGraph: IrClass,
    creatorFunction: IrSimpleFunction?,
    typeKey: IrTypeKey,
  ): IrClass {
    val graphExtensionAnno =
      sourceGraph.annotationsIn(metroSymbols.classIds.graphExtensionAnnotations).firstOrNull()
    val extensionAnno =
      graphExtensionAnno
        ?: reportCompilerBug("Expected @GraphExtension on ${sourceGraph.kotlinFqName}")

    val (owner, name) = allocateExtensionName(sourceGraph, typeKey)
    val syntheticGraphGenerator =
      SyntheticGraphGenerator(
        metroContext = metroContext,
        contributionMerger = contributionMerger,
        bindingContainerResolver = bindingContainerResolver,
        sourceAnnotation = extensionAnno,
        parentGraph = parentGraph,
        originDeclaration = parentGraph,
        parentExclusionDeclaration = parentGraph,
        containerToAddTo = owner,
        traceScope = traceScope,
      )

    val chainDepth = generateSequence(parentGraph) { it.graphParentOrOuterClass }.count() - 1
    if (chainDepth >= GRAPH_EXTENSION_CHAIN_DEPTH_WARNING) {
      reportCompat(
        sourceGraph,
        MetroDiagnostics.METRO_WARNING,
        "Graph extension chain is ${chainDepth + 1} levels deep at " +
          "'${sourceGraph.kotlinFqName}'. Generated nested class file names grow with chain " +
          "depth and can exceed the per-segment file name limit (255 bytes) on most " +
          "filesystems. Metro substitutes a shortened hash-based name at this depth, but " +
          "consider flattening the hierarchy.",
      )
    }

    // Source is a `@GraphExtension`-annotated class, we want to generate a header impl class
    val (_, graphImpl, factoryImpl) =
      syntheticGraphGenerator.generateImpl(
        name = name,
        origin = Origins.GeneratedGraphExtension,
        supertype = sourceGraph.defaultType,
        creatorFunction = creatorFunction,
      )

    graphImpl.generatedGraphExtensionData =
      GeneratedGraphExtensionData(
        typeKey = typeKey,
        parentGraph = parentGraph,
        factoryImpl = factoryImpl,
      )

    return graphImpl
  }

  /** Uses the closest enclosing class that leaves room for the extension's factory and helpers. */
  private fun allocateExtensionName(
    sourceGraph: IrClass,
    typeKey: IrTypeKey,
  ): Pair<IrDeclarationContainer, Name> {
    val parentId = parentGraph.classIdOrFail
    val preferred = "${sourceGraph.name.asString().capitalizeUS()}${Symbols.StringNames.IMPL}"
    val hint = parentId.safeNestedSimpleName(preferred, sourceGraph.classIdOrFail)
    val name = parentGraph.availableNestedName(toSafeIdentifier(hint).asName())
    if (name != null) {
      return parentGraph to name
    }

    // A moved extension keeps the identity of its logical parent in every destination.
    val identity = generatedNameIdentity(parentId.asString(), typeKey.identity.encoded)
    val base = toSafeIdentifier(identity.cityHash64().toHexString()).asName()
    for (owner in generateSequence(parentGraph) { it.parentClassOrNull }) {
      val compact = owner.availableNestedName(base)
      if (compact != null) {
        return owner to compact
      }
    }

    // A long source class can leave room for the extension only at file scope.
    val file = parentGraph.file
    val compact =
      firstUnusedName(base) { parentGraph.lookupClass(ClassId(file.packageFqName, it)) != null }
    return file to compact
  }
}

private const val GRAPH_EXTENSION_CHAIN_DEPTH_WARNING = 10

/** The JVM joins the enclosing name and the generated simple name with one '$' byte. */
private fun ClassId.nestedNameBudget(): Long =
  NESTED_CLASS_BINARY_NAME_LIMIT - relativeClassName.asString().utf8Size() - 1

/** A numbered collision suffix can use up the last bytes under this owner. */
private fun IrClass.availableNestedName(base: Name): Name? {
  val budget = classIdOrFail.nestedNameBudget()
  return firstUnusedName(base) { candidate ->
    if (candidate.asString().length > budget) {
      return null
    }
    nestedClassOrNull(candidate) != null
  }
}

/** Tries the base name before allocating a numbered alternative. */
private inline fun firstUnusedName(base: Name, isTaken: (Name) -> Boolean): Name {
  var name = base
  var suffix = 2
  while (isTaken(name)) {
    name = "${base.asString()}$suffix".asName()
    suffix++
  }
  return name
}

/** [parentGraph] supplies inherited bindings and exclusions even when the implementation moves. */
internal class GeneratedGraphExtensionData(
  val typeKey: IrTypeKey,
  val parentGraph: IrClass,
  val factoryImpl: IrClass? = null,
)

internal var IrClass.generatedGraphExtensionData: GeneratedGraphExtensionData? by
  irAttribute(copyByDefault = false)

/** Follows the logical extension chain before walking the enclosing source declarations. */
internal val IrClass.graphParentOrOuterClass: IrClass?
  get() = generatedGraphExtensionData?.parentGraph ?: parentClassOrNull
