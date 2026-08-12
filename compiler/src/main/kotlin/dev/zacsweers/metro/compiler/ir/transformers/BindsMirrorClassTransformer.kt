// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.compat.propertyIfAccessorCompat
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.ir.BindsCallable
import dev.zacsweers.metro.compiler.ir.BindsOptionalOfCallable
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrScope
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.MultibindsCallable
import dev.zacsweers.metro.compiler.ir.addAnnotationCompat
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.getOrCreateMetadataVisibleHiddenNestedClass
import dev.zacsweers.metro.compiler.ir.isEffectivelyPublic
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.metroFunctionOf
import dev.zacsweers.metro.compiler.ir.nestedClassOrNull
import dev.zacsweers.metro.compiler.ir.replaceAnnotationsCompat
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.ir.withPopulatedImplicitClassKey
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.mirrorIrConstructorCalls
import dev.zacsweers.metro.compiler.symbols.Symbols
import java.util.EnumSet
import java.util.Optional
import kotlin.jvm.optionals.getOrNull
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyParametersFrom
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.platform.jvm.isJvm

/**
 * Collects `@Binds`-like declarations and generates metadata carriers for declarations that are not
 * directly readable from another compilation.
 */
@Inject
@ContributesIntoSet(IrScope::class, binding<Lockable>())
internal class BindsMirrorClassTransformer(context: IrMetroContext) :
  IrMetroContext by context, Lockable by Lockable() {
  private val cache = mutableMapOf<ClassId, Optional<BindsMirror>>()

  // When we generate binds/providers we need to generate a mirror class too
  fun getOrComputeBindsMirror(declaration: IrClass): BindsMirror? {
    return cache
      .getOrPut(declaration.classIdOrFail) {
        val isExternal = declaration.isExternalParent
        val useDirectDeclarations = declaration.shouldUseDirectBindingDeclarations()
        val createMirror =
          if (isExternal) {
            false
          } else if (useDirectDeclarations) {
            declaration.hasInaccessibleBindingCallables()
          } else {
            declaration.hasBindingCallables()
          }
        val mirrorClass = declaration.bindsMirrorClassOrNull(createIfMissing = createMirror)

        // External declarations may have been produced with redundant mirrors omitted even when
        // the current compilation still generates them. Read their visible originals alongside
        // any mirror functions so modules using either setting can be mixed.
        if (mirrorClass == null && !useDirectDeclarations && !isExternal) {
          // If there's no mirror class, there's no bindings.
          // TODO what if they forgot to run the metro compiler? Should we put something in
          //  metadata?
          return@getOrPut Optional.empty()
        }

        if (!declaration.isExternalParent) {
          checkNotLocked()
        }
        val mirror =
          transformBindingDeclarations(
            parentClass = declaration,
            mirrorClass = mirrorClass,
            useDirectDeclarations = useDirectDeclarations,
          )
        Optional.ofNullable(mirror)
      }
      .getOrNull()
  }

  private fun IrClass.shouldUseDirectBindingDeclarations(): Boolean {
    if (isExternalParent) return false
    return icCapabilities.annotationArgumentInvalidation &&
      icCapabilities.readableAnnotationMetadata
  }

  private fun IrClass.bindsMirrorClassOrNull(createIfMissing: Boolean): IrClass? {
    nestedClassOrNull(Symbols.Names.BindsMirrorClass)?.let {
      return it
    }

    if (!options.generateClassesInIr || !createIfMissing) return null

    // Avoid empty mirror noise and mirrors inside Metro's own IR-generated classes (e.g. assisted
    // factory impls and their companions), which aren't metadata-visible themselves and so can't
    // be resolved back to FIR when registering the mirror.

    return getOrCreateMetadataVisibleHiddenNestedClass(
        name = Symbols.Names.BindsMirrorClass,
        origin = Origins.BindingMirrorClassDeclaration,
        copyTypeParameters = false,
      )
      .apply { modality = Modality.ABSTRACT }
  }

  private fun IrClass.hasBindingCallables(): Boolean {
    return hasBindingCallables(inaccessibleOnly = false)
  }

  private fun IrClass.hasInaccessibleBindingCallables(): Boolean {
    return hasBindingCallables(inaccessibleOnly = true)
  }

  private fun IrClass.hasBindingCallables(inaccessibleOnly: Boolean): Boolean {
    return declarations.any { declaration ->
      if (declaration !is IrSimpleFunction && declaration !is IrProperty) return@any false
      if (declaration.isFakeOverride) return@any false
      val function =
        when (declaration) {
          is IrProperty -> declaration.getter ?: return@any false
          is IrSimpleFunction -> declaration
        }
      if (inaccessibleOnly && function.canBeReadDirectly()) return@any false
      val annotations =
        declaration.metroAnnotations(
          metroSymbols.classIds,
          kinds =
            EnumSet.of(
              MetroAnnotations.Kind.Binds,
              MetroAnnotations.Kind.Multibinds,
              MetroAnnotations.Kind.BindsOptionalOf,
            ),
        )
      annotations.isBinds || annotations.isMultibinds || annotations.isBindsOptionalOf
    }
  }
}

private fun IrSimpleFunction.canBeReadDirectly(): Boolean {
  return isEffectivelyPublic()
}

internal data class BindsMirror(
  val ir: IrClass,
  /** Set of binds callables by their [CallableId]. */
  val bindsCallables: Set<BindsCallable>,
  /** Set of multibinds callables by their [BindsCallable]. */
  val multibindsCallables: Set<MultibindsCallable>,
  /**
   * Interoped optional types from `@BindsOptionalOf`. Only present if Dagger interop is enabled.
   */
  val optionalKeys: Set<BindsOptionalOfCallable>,
) {
  fun isEmpty() =
    bindsCallables.isEmpty() && multibindsCallables.isEmpty() && optionalKeys.isEmpty()
}

context(context: IrMetroContext)
private fun transformBindingDeclarations(
  parentClass: IrClass,
  mirrorClass: IrClass?,
  useDirectDeclarations: Boolean,
): BindsMirror {
  val isExternal = parentClass.isExternalParent
  val collector = BindsMirrorCollector(isInterop = false)

  if (!isExternal && mirrorClass != null) {
    mirrorClass.patchDeclarationParents(parentClass)
  }

  // On JVM, annotate with @ComptimeOnly so R8 can remove these
  val comptimeOnlyConstructor =
    if (!isExternal && context.pluginContext.platform.isJvm()) {
      context.metroSymbols.comptimeOnlyAnnotationConstructor
    } else {
      null
    }

  // Annotate the mirror class with @ComptimeOnly
  comptimeOnlyConstructor?.let { ctor ->
    mirrorClass?.addAnnotationCompat(buildAnnotation(mirrorClass.symbol, ctor))
  }

  fun processOriginalFunction(declaration: IrSimpleFunction) {
    if (!declaration.isFakeOverride) {
      val originalFunction = metroFunctionOf(declaration)
      if (
        originalFunction.annotations.isBinds ||
          originalFunction.annotations.isMultibinds ||
          originalFunction.annotations.isBindsOptionalOf
      ) {
        // For @Binds with an implicit class key map key, resolve the sentinel to the bound
        // source type (the single non-dispatch parameter) once, so it flows into both the
        // mirror class's IR annotations and the BindsCallable's MetroAnnotations that
        // IrBinding.Alias ultimately reads from.
        var metroFunction = originalFunction
        if (originalFunction.annotations.isBinds && originalFunction.annotations.mapKey != null) {
          declaration.nonDispatchParameters.singleOrNull()?.type?.let { sourceType ->
            val newAnnotations =
              originalFunction.annotations.withPopulatedImplicitClassKey(sourceType)
            if (newAnnotations !== originalFunction.annotations) {
              metroFunction =
                MetroSimpleFunction(
                  ir = declaration,
                  annotations = newAnnotations,
                  callableId = originalFunction.callableId,
                )
            }
          }
        }

        // Add stub body and @ComptimeOnly annotation to the original binds declaration
        // This provides a default implementation so graph impl classes don't need to
        // implement fake overrides
        if (!isExternal && !metroFunction.annotations.isMultibinds) {
          declaration.apply {
            body = stubExpressionBody()
            modality = Modality.OPEN
            if (origin == Origins.FirstParty.DEFAULT_PROPERTY_ACCESSOR) {
              origin = Origins.Default
            }
            comptimeOnlyConstructor?.let { ctor ->
              addAnnotationCompat(buildAnnotation(symbol, ctor))
            }
          }
        }

        if (declaration.canBeReadDirectly() && (useDirectDeclarations || isExternal)) {
          collector.addDirect(metroFunction)
        } else if (!isExternal) {
          val requiredMirrorClass = checkNotNull(mirrorClass)
          collector += generateMirrorFunction(requiredMirrorClass, metroFunction)
        }
      }
    }
  }

  fun processOriginalDeclarations() {
    for (declaration in parentClass.declarations) {
      when (declaration) {
        is IrProperty -> {
          val getter = declaration.getter ?: continue
          processOriginalFunction(getter)
        }
        is IrSimpleFunction -> processOriginalFunction(declaration)
      }
    }
  }

  fun collectExternalMirrorFunction(declaration: IrSimpleFunction) {
    if (declaration.isFakeOverride) return

    val function = metroFunctionOf(declaration)
    val annotations = function.annotations
    if (annotations.isBinds || annotations.isMultibinds || annotations.isBindsOptionalOf) {
      collector += function
    }
  }

  if (isExternal && mirrorClass != null) {
    for (declaration in mirrorClass.declarations) {
      when (declaration) {
        is IrProperty -> {
          val getter = declaration.getter ?: continue
          collectExternalMirrorFunction(getter)
        }
        is IrSimpleFunction -> collectExternalMirrorFunction(declaration)
      }
    }
  }

  processOriginalDeclarations()

  return collector.buildMirror(mirrorClass ?: parentClass)
}

context(context: IrMetroContext)
private fun generateMirrorFunction(
  mirrorClass: IrClass,
  targetFunction: MetroSimpleFunction,
): MetroSimpleFunction {
  // Create a unique name for this mirror function based on the target function name
  // and qualifier + map key annotations
  val annotations = targetFunction.annotations
  val canReadChangedAnnotations =
    context.icCapabilities.annotationArgumentInvalidation &&
      context.icCapabilities.readableAnnotationMetadata
  val includeAnnotationHashes = !canReadChangedAnnotations
  val mirrorFunctionName = buildString {
    val sourceDeclaration = targetFunction.ir.propertyIfAccessorCompat
    append(sourceDeclaration.expectAs<IrDeclarationWithName>().name)
    if (sourceDeclaration is IrProperty) {
      append("_property")
    }
    if (includeAnnotationHashes) {
      annotations.qualifier?.hashCode()?.toUInt()?.let(::append)
      annotations.mapKey?.hashCode()?.toUInt()?.let(::append)
      annotations.multibinds?.hashCode()?.toUInt()?.let(::append)
    }

    if (annotations.isBindsOptionalOf) {
      append("_opt")
    }

    if (annotations.isIntoSet) {
      append("_intoset")
    } else if (annotations.isElementsIntoSet) {
      append("_elementsintoset")
    } else if (annotations.isIntoMap) {
      append("_intomap")
    }
  }
    .asName()

  val mirrorFunction =
    mirrorClass
      .addFunction {
        updateFrom(targetFunction.ir)
        name = mirrorFunctionName
        visibility = DescriptorVisibilities.PUBLIC
        returnType = targetFunction.ir.returnType
        origin = Origins.Default
        modality = Modality.FINAL
      }
      .apply {
        copyParametersFrom(targetFunction.ir)
        body = stubExpressionBody()
        replaceAnnotationsCompat(annotations.mirrorIrConstructorCalls(symbol))
      }

  val callableMetadata =
    buildAnnotation(
      mirrorFunction.symbol,
      context.metroSymbols.callableMetadataAnnotationConstructor,
    ) {
      with(it) {
        // callableName
        arguments[0] = irString(targetFunction.callableId.callableName.asString())
        // propertyName
        arguments[1] =
          irString(
            targetFunction.ir.propertyIfAccessorCompat
              .expectAsOrNull<IrProperty>()
              ?.name
              ?.asString() ?: ""
          )

        // TODO these locations are bogus in generated binding functions. Report origin class
        //  instead somewhere?
        // startOffset
        arguments[2] = irInt(targetFunction.ir.propertyIfAccessorCompat.startOffset)
        // endOffset
        arguments[3] = irInt(targetFunction.ir.propertyIfAccessorCompat.endOffset)
      }
    }

  mirrorFunction.addAnnotationCompat(callableMetadata)

  // Register as metadata visible
  context.metadataDeclarationRegistrarCompat.registerFunctionAsMetadataVisible(mirrorFunction)
  return metroFunctionOf(mirrorFunction)
}
