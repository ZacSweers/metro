// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.ir.ClassFactory
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.ParentContext
import dev.zacsweers.metro.compiler.ir.allowEmpty
import dev.zacsweers.metro.compiler.ir.asMemberOf
import dev.zacsweers.metro.compiler.ir.deepRemapperFor
import dev.zacsweers.metro.compiler.ir.mapKeyType
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.remapTypes
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.requireSimpleType
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer.MemberInjectClass
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import java.util.TreeSet
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.removeAnnotations
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.isObject

internal class BindingLookup(
  private val metroContext: IrMetroContext,
  private val sourceGraph: IrClass,
  private val findClassFactory: (IrClass) -> ClassFactory?,
  private val findMemberInjectors: (IrClass) -> List<MemberInjectClass>,
  private val parentContext: ParentContext?,
) {

  // Caches
  private val providedBindingsCache = mutableMapOf<IrTypeKey, IrBinding.Provided>()
  private val aliasBindingsCache = mutableMapOf<IrTypeKey, IrBinding.Alias>()
  private val membersInjectorBindingsCache = mutableMapOf<IrTypeKey, IrBinding.MembersInjected>()
  private val classBindingsCache = mutableMapOf<IrContextualTypeKey, Set<IrBinding>>()

  private data class ParentGraphDepKey(val owner: IrClass, val typeKey: IrTypeKey)

  private val parentGraphDepCache = mutableMapOf<ParentGraphDepKey, IrBinding.GraphDependency>()

  // Lazy parent key bindings - only created when actually accessed
  private val lazyParentKeys = mutableMapOf<IrTypeKey, Lazy<IrBinding>>()

  // Multibinding tracking
  // Key: multibinding type (Set<T> or Map<K, V>), Value: set of source binding keys
  private val multibindingContributions = mutableMapOf<IrTypeKey, MutableSet<IrTypeKey>>()
  // Cache for created multibindings
  private val multibindingsCache = mutableMapOf<IrTypeKey, IrBinding.Multibinding>()

  /** Information about an explicit @Multibinds declaration */
  private data class MultibindsDeclaration(
    val declaration: IrSimpleFunction,
    val annotation: IrAnnotation,
    val allowEmpty: Boolean,
  )

  // Explicit @Multibinds declarations
  private val multibindsDeclarations = mutableMapOf<IrTypeKey, MultibindsDeclaration>()

  /** Returns all static bindings for similarity checking. */
  fun getAvailableStaticBindings(): Map<IrTypeKey, IrBinding.StaticBinding> {
    return buildMap(providedBindingsCache.size + aliasBindingsCache.size) {
      putAll(providedBindingsCache)
      putAll(aliasBindingsCache)
    }
  }

  fun getStaticBinding(typeKey: IrTypeKey): IrBinding.StaticBinding? {
    return providedBindingsCache[typeKey] ?: aliasBindingsCache[typeKey]
  }

  fun getMembersInjectorBinding(typeKey: IrTypeKey): IrBinding.MembersInjected? {
    return membersInjectorBindingsCache[typeKey]
  }

  context(context: IrMetroContext)
  fun putBinding(binding: IrBinding.Provided) {
    providedBindingsCache[binding.typeKey] = binding

    // If this is a multibinding contributor, register it
    if (binding.annotations.isIntoMultibinding) {
      val multibindingKey =
        computeMultibindingKey(
          annotations = binding.annotations,
          contextKey = binding.contextualTypeKey,
          declaration = binding.providerFactory.function,
          originalQualifier = binding.providerFactory.rawTypeKey.qualifier,
        )
      registerMultibindingContribution(multibindingKey, binding.typeKey)
    }
  }

  context(context: IrMetroContext)
  fun putBinding(binding: IrBinding.Alias) {
    aliasBindingsCache[binding.typeKey] = binding

    // If this is a multibinding contributor, register it
    val bindsCallable = binding.bindsCallable
    if (bindsCallable != null && bindsCallable.callableMetadata.annotations.isIntoMultibinding) {
      val multibindingKey =
        computeMultibindingKey(
          annotations = bindsCallable.callableMetadata.annotations,
          contextKey = binding.contextualTypeKey,
          declaration = bindsCallable.function,
          originalQualifier = bindsCallable.callableMetadata.annotations.qualifier,
        )
      registerMultibindingContribution(multibindingKey, binding.typeKey)
    }
  }

  fun putBinding(binding: IrBinding.MembersInjected) {
    membersInjectorBindingsCache[binding.typeKey] = binding
  }

  fun removeProvidedBinding(typeKey: IrTypeKey) {
    providedBindingsCache.remove(typeKey)
  }

  fun removeAliasBinding(typeKey: IrTypeKey) {
    aliasBindingsCache.remove(typeKey)
  }

  fun addLazyParentKey(typeKey: IrTypeKey, bindingFactory: () -> IrBinding) {
    lazyParentKeys[typeKey] = memoize(bindingFactory)
  }

  /**
   * Registers a contribution to a multibinding. The contribution will be added to the multibinding
   * when it is looked up.
   *
   * @param multibindingKey The multibinding type key (Set<T> or Map<K, V>)
   * @param sourceBindingKey The source binding key that contributes to the multibinding
   */
  private fun registerMultibindingContribution(
    multibindingKey: IrTypeKey,
    sourceBindingKey: IrTypeKey,
  ) {
    multibindingContributions.getOrPut(multibindingKey, ::TreeSet).add(sourceBindingKey)
  }

  /**
   * Registers an explicit @Multibinds declaration.
   *
   * @param typeKey The multibinding type key (Set<T> or Map<K, V>)
   * @param declaration The getter function with @Multibinds
   * @param annotation The @Multibinds annotation
   */
  context(context: IrMetroContext)
  fun registerMultibindsDeclaration(
    typeKey: IrTypeKey,
    declaration: IrSimpleFunction,
    annotation: IrAnnotation,
  ) {
    val existing = multibindsDeclarations[typeKey]
    if (existing != null) {
      // Update existing declaration (e.g., from parent graph)
      // Prefer the one with allowEmpty = true if either has it
      val newAllowEmpty = existing.allowEmpty || annotation.allowEmpty()
      multibindsDeclarations[typeKey] =
        MultibindsDeclaration(declaration, annotation, newAllowEmpty)
    } else {
      multibindsDeclarations[typeKey] =
        MultibindsDeclaration(declaration, annotation, annotation.allowEmpty())
    }
  }

  /**
   * Returns all registered multibindings for similarity checking. This includes both cached
   * multibindings and those that have been registered but not yet created.
   */
  context(context: IrMetroContext)
  fun getAvailableMultibindings(): Map<IrTypeKey, IrBinding.Multibinding> {
    // Ensure all registered multibindings are created
    val allKeys = multibindingContributions.keys + multibindsDeclarations.keys
    for (key in allKeys) {
      getOrCreateMultibindingIfNeeded(key)
    }
    return multibindingsCache.toMap()
  }

  /**
   * Computes the multibinding type key (Set<T> or Map<K, V>) from the annotations of a contributor.
   *
   * @param annotations The annotations from the contributing binding (@IntoSet, @IntoMap, etc.)
   * @param contextKey The context key of the contributing binding
   * @param declaration The function that contributes to the multibinding
   * @param originalQualifier The original qualifier annotation (if any)
   * @return The multibinding type key
   */
  context(context: IrMetroContext)
  private fun computeMultibindingKey(
    annotations: MetroAnnotations<IrAnnotation>,
    contextKey: IrContextualTypeKey,
    declaration: IrSimpleFunction,
    originalQualifier: IrAnnotation?,
  ): IrTypeKey {
    return when {
      annotations.isIntoSet -> {
        val setType = metroContext.irBuiltIns.setClass.typeWith(contextKey.typeKey.type)
        contextKey.typeKey.copy(type = setType, qualifier = originalQualifier)
      }

      annotations.isElementsIntoSet -> {
        val elementType =
          contextKey.typeKey.type.requireSimpleType(declaration).arguments.single().typeOrFail
        val setType = metroContext.irBuiltIns.setClass.typeWith(elementType)
        contextKey.typeKey.copy(type = setType, qualifier = originalQualifier)
      }

      annotations.isIntoMap -> {
        val mapKey =
          annotations.mapKeys.firstOrNull()
            ?: run {
              // Hard error because the FIR checker should catch these, so this implies broken
              // FIR code gen
              reportCompilerBug(
                "Missing @MapKey for @IntoMap function: ${declaration.symbol.owner}"
              )
            }
        val keyType = mapKeyType(mapKey)
        val mapType =
          metroContext.irBuiltIns.mapClass.typeWith(
            // MapKey is the key type
            keyType,
            // Return type is the value type
            contextKey.typeKey.type.removeAnnotations(),
          )
        contextKey.typeKey.copy(type = mapType, qualifier = originalQualifier)
      }

      else -> {
        reportCompilerBug("Unrecognized provider: ${declaration.symbol.owner.dumpKotlinLike()}")
      }
    }
  }

  /**
   * Lazily creates a multibinding for the given type key if it has contributions or declarations.
   * Returns null if there are no contributions or declarations for this type key.
   */
  context(context: IrMetroContext)
  private fun getOrCreateMultibindingIfNeeded(typeKey: IrTypeKey): IrBinding.Multibinding? {
    // Check cache first
    multibindingsCache[typeKey]?.let {
      return it
    }

    // Check if we have contributions or declarations
    val contributions = multibindingContributions[typeKey]
    val declaration = multibindsDeclarations[typeKey]

    if (contributions == null && declaration == null) {
      return null
    }

    // Create the multibinding
    val multibinding =
      if (declaration != null) {
        IrBinding.Multibinding.fromMultibindsDeclaration(
          getter = declaration.declaration,
          multibinds = declaration.annotation,
          contextualTypeKey = IrContextualTypeKey(typeKey),
        )
      } else {
        IrBinding.Multibinding.fromContributor(typeKey)
      }

    // Add all source bindings
    contributions?.forEach { sourceKey -> multibinding.addSourceBinding(sourceKey) }

    // Cache and return
    multibindingsCache[typeKey] = multibinding

    // If it's a map, also cache under Map<K, Provider<V>> type key
    if (multibinding.isMap) {
      val keyType = (typeKey.type as IrSimpleType).arguments[0].typeOrNull!!
      val valueType =
        typeKey.type.arguments[1]
          .typeOrNull!!
          .wrapInProvider(metroContext.metroSymbols.metroProvider)
      val providerTypeKey =
        typeKey.copy(type = metroContext.irBuiltIns.mapClass.typeWith(keyType, valueType))
      multibindingsCache[providerTypeKey] = multibinding
    }

    return multibinding
  }

  context(context: IrMetroContext)
  private fun IrClass.computeMembersInjectorBindings(
    remapper: TypeRemapper
  ): Set<IrBinding.MembersInjected> {
    val bindings = mutableSetOf<IrBinding.MembersInjected>()
    for (generatedInjector in findMemberInjectors(this)) {
      val mappedTypeKey = generatedInjector.typeKey.remapTypes(remapper)
      // Get or create cached binding for this type key
      val binding =
        membersInjectorBindingsCache.getOrPut(mappedTypeKey) {
          val remappedParameters = generatedInjector.mergedParameters(remapper)
          val contextKey = IrContextualTypeKey(mappedTypeKey)

          IrBinding.MembersInjected(
            contextKey,
            // Need to look up the injector class and gather all params
            parameters = remappedParameters,
            reportableDeclaration = this,
            function = null,
            // Bindings created here are from class-based lookup, not injector functions
            // (injector function bindings are cached in BindingGraphGenerator)
            isFromInjectorFunction = false,
            // Unpack the target class from the type
            targetClassId =
              mappedTypeKey.type
                .requireSimpleType(this)
                .arguments[0]
                .typeOrFail
                .rawType()
                .classIdOrFail,
          )
        }
      bindings += binding
    }
    return bindings
  }

  /** Looks up bindings for the given [contextKey] or returns an empty set. */
  internal fun lookup(
    contextKey: IrContextualTypeKey,
    currentBindings: Set<IrTypeKey>,
    stack: IrBindingStack,
  ): Set<IrBinding> =
    context(metroContext) {
      val key = contextKey.typeKey

      // First check @Provides
      providedBindingsCache[key]?.let { providedBinding ->
        // Check if this is available from parent and is scoped
        if (providedBinding.scope != null && parentContext?.contains(key) == true) {
          val fieldAccess = parentContext.mark(key, providedBinding.scope!!)
          return setOf(createParentGraphDependency(key, fieldAccess!!))
        }
        return setOf(providedBinding)
      }

      // Then check @Binds
      // TODO if @Binds from a parent matches a parent accessor, which one wins?
      aliasBindingsCache[key]?.let {
        return setOf(it)
      }

      // Check for lazy parent keys
      lazyParentKeys[key]?.let { lazyBinding ->
        return setOf(lazyBinding.value)
      }

      // Check for multibindings (Set<T> or Map<K, V> with contributions)
      getOrCreateMultibindingIfNeeded(key)?.let { multibinding ->
        return setOf(multibinding)
      }

      // Finally, fall back to class-based lookup and cache the result
      val classBindings = lookupClassBinding(contextKey, currentBindings, stack)

      // Check if this class binding is available from parent and is scoped
      if (parentContext != null) {
        val remappedBindings = mutableSetOf<IrBinding>()
        for (binding in classBindings) {
          val scope = binding.scope
          if (scope != null) {
            val scopeInParent =
              key in parentContext ||
                // Discovered here but unused in the parents, mark it anyway so they include it
                parentContext.containsScope(scope)
            if (scopeInParent) {
              val propertyAccess = parentContext.mark(key, scope)
              remappedBindings += createParentGraphDependency(key, propertyAccess!!)
              continue
            }
          }
          remappedBindings += binding
        }
        return remappedBindings
      }

      return classBindings
    }

  private fun createParentGraphDependency(
    key: IrTypeKey,
    propertyAccess: ParentContext.PropertyAccess,
  ): IrBinding.GraphDependency {
    val parentGraph = parentContext!!.currentParentGraph
    val cacheKey = ParentGraphDepKey(parentGraph, key)
    return parentGraphDepCache.getOrPut(cacheKey) {
      val parentTypeKey = IrTypeKey(parentGraph.typeWith())

      IrBinding.GraphDependency(
        ownerKey = parentTypeKey,
        graph = sourceGraph,
        propertyAccess = propertyAccess,
        typeKey = key,
      )
    }
  }

  context(context: IrMetroContext)
  private fun lookupClassBinding(
    contextKey: IrContextualTypeKey,
    currentBindings: Set<IrTypeKey>,
    stack: IrBindingStack,
  ): Set<IrBinding> {
    return classBindingsCache.getOrPut(contextKey) {
      val key = contextKey.typeKey
      val irClass = key.type.rawType()

      if (irClass.classId == context.metroSymbols.metroMembersInjector.owner.classId) {
        // It's a members injector, just look up its bindings and return them
        val targetType = key.type.requireSimpleType().arguments.first().typeOrFail
        val targetClass = targetType.rawType()
        val remapper = targetClass.deepRemapperFor(targetType)
        // Filter out bindings that already exist to avoid duplicates
        return targetClass.computeMembersInjectorBindings(remapper).filterTo(mutableSetOf()) {
          it.typeKey !in currentBindings
        }
      }

      val classAnnotations = irClass.metroAnnotations(context.metroSymbols.classIds)

      if (irClass.isObject) {
        irClass.getSimpleFunction(Symbols.StringNames.MIRROR_FUNCTION)?.owner?.let {
          // We don't actually call this function but it stores information about qualifier/scope
          // annotations, so reference it here so IC triggers
          trackFunctionCall(sourceGraph, it)
        }
        return setOf(IrBinding.ObjectClass(irClass, classAnnotations, key))
      }

      val bindings = mutableSetOf<IrBinding>()
      val remapper by memoize { irClass.deepRemapperFor(key.type) }

      // Compute all member injector bindings (needed for injectedMembers field)
      // Only add new bindings (not in currentBindings) to the graph to avoid duplicates
      val membersInjectBindings = memoize {
        irClass.computeMembersInjectorBindings(remapper).also { allBindings ->
          bindings += allBindings.filterNot { it.typeKey in currentBindings }
        }
      }

      val classFactory = findClassFactory(irClass)
      if (classFactory != null) {
        // We don't actually call this function but it stores information about qualifier/scope
        // annotations, so reference it here so IC triggers
        trackFunctionCall(sourceGraph, classFactory.function)

        val mappedFactory = classFactory.remapTypes(remapper)

        // Not sure this can ever happen but report a detailed error in case.
        if (
          irClass.typeParameters.isNotEmpty() &&
            (key.type as? IrSimpleType)?.arguments.isNullOrEmpty()
        ) {
          val message = buildString {
            appendLine(
              "Class factory for type ${key.type} has type parameters but no type arguments provided at calling site."
            )
            appendBindingStack(stack)
          }
          context.reportCompat(irClass, MetroDiagnostics.METRO_ERROR, message)
          return@getOrPut emptySet()
        }

        val binding =
          IrBinding.ConstructorInjected(
            type = irClass,
            classFactory = mappedFactory,
            annotations = classAnnotations,
            typeKey = key,
            injectedMembers =
              membersInjectBindings.value.mapToSet { binding -> binding.contextualTypeKey },
          )
        bindings += binding

        // Record a lookup of the class in case its kind changes
        trackClassLookup(sourceGraph, classFactory.factoryClass)
        // Record a lookup of the signature in case its signature changes
        // Doesn't appear to be necessary but juuuuust in case
        trackFunctionCall(sourceGraph, classFactory.function)
      } else if (classAnnotations.isAssistedFactory) {
        val function = irClass.singleAbstractFunction().asMemberOf(key.type)
        // Mark as wrapped for convenience in graph resolution to note that this whole node is
        // inherently deferrable
        val targetContextualTypeKey = IrContextualTypeKey.from(function, wrapInProvider = true)
        bindings +=
          IrBinding.Assisted(
            type = irClass,
            function = function,
            annotations = classAnnotations,
            typeKey = key,
            parameters = function.parameters(),
            target = targetContextualTypeKey,
          )
      } else if (contextKey.hasDefault) {
        bindings += IrBinding.Absent(key)
      } else {
        // It's a regular class, not injected, not assisted. Initialize member injections still just
        // in case
        membersInjectBindings.value
      }
      bindings
    }
  }
}
