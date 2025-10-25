// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.ir.ClassFactory
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.ParentContext
import dev.zacsweers.metro.compiler.ir.asMemberOf
import dev.zacsweers.metro.compiler.ir.deepRemapperFor
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.requireSimpleType
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer.MemberInjectClass
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.metroAnnotations
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
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

  fun putBinding(binding: IrBinding.Provided) {
    providedBindingsCache[binding.typeKey] = binding
  }

  fun putBinding(binding: IrBinding.Alias) {
    aliasBindingsCache[binding.typeKey] = binding
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
            // TODO this isn't actually necessarily true?
            isFromInjectorFunction = true,
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
