// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import androidx.collection.ScatterMap
import dev.zacsweers.metro.compiler.alsoIf
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.getOrInit
import dev.zacsweers.metro.compiler.ir.BindsOptionalOfCallable
import dev.zacsweers.metro.compiler.ir.ClassFactory
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.NOOP_TYPE_REMAPPER
import dev.zacsweers.metro.compiler.ir.ParentContext
import dev.zacsweers.metro.compiler.ir.allowEmpty
import dev.zacsweers.metro.compiler.ir.asContextualTypeKey
import dev.zacsweers.metro.compiler.ir.asMemberOf
import dev.zacsweers.metro.compiler.ir.deepRemapperFor
import dev.zacsweers.metro.compiler.ir.graph.expressions.IrOptionalExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.expressions.optionalType
import dev.zacsweers.metro.compiler.ir.mapKeyType
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
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
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.name.CallableId

internal class BindingLookup(
  private val metroContext: IrMetroContext,
  private val sourceGraph: IrClass,
  private val findClassFactory: (IrClass) -> ClassFactory?,
  private val findMemberInjectors: (IrClass) -> List<MemberInjectClass>,
  private val parentContext: ParentContext?,
) {

  // Single cache for all bindings, storing lists to track duplicates naturally
  private val bindingsCache = mutableMapOf<IrTypeKey, IrBinding>()
  private val duplicateBindings = mutableMapOf<IrTypeKey, MutableSet<IrBinding>>()
  private val classBindingsCache = mutableMapOf<IrContextualTypeKey, Set<IrBinding>>()

  private data class ParentGraphDepKey(val owner: IrClass, val typeKey: IrTypeKey)

  private val parentGraphDepCache = mutableMapOf<ParentGraphDepKey, IrBinding.GraphDependency>()

  // Lazy parent key bindings - only created when actually accessed
  private val lazyParentKeys = mutableMapOf<IrTypeKey, Lazy<IrBinding>>()

  // Cache for created multibindings, keyed by type key (Set<T> or Map<K, V>)
  private val multibindingsCache = mutableMapOf<IrTypeKey, IrBinding.Multibinding>()

  // Index from bindingId to multibinding for lookup when registering contributions
  private val multibindingsByBindingId = mutableMapOf<String, IrBinding.Multibinding>()

  /** Information about an explicit @Multibinds declaration */
  private data class MultibindsDeclaration(
    val declaration: IrSimpleFunction,
    val annotation: IrAnnotation,
    val allowEmpty: Boolean,
  )

  // Explicit @Multibinds declarations
  private val multibindsDeclarations = mutableMapOf<IrTypeKey, MultibindsDeclaration>()

  // Optional binding declarations (@BindsOptionalOf)
  // Key: Optional<T> type, Value: set of @BindsOptionalOf callables
  private val optionalBindingDeclarations =
    mutableMapOf<IrTypeKey, MutableSet<BindsOptionalOfCallable>>()
  // Cache for created optional bindings
  private val optionalBindingsCache = mutableMapOf<IrTypeKey, IrBinding.CustomWrapper>()
  // Keys explicitly declared in this graph (for unused key reporting)
  private val locallyDeclaredKeys = mutableSetOf<IrTypeKey>()

  /** Information about a registered injector function for MembersInjected binding creation. */
  private data class InjectorFunctionInfo(
    val function: IrSimpleFunction,
    val callableId: CallableId,
  )

  // Registered injector functions - binding creation deferred to computeMembersInjectorBindings
  private val registeredInjectorFunctions = mutableMapOf<IrTypeKey, InjectorFunctionInfo>()

  fun getAvailableKeys(): Set<IrTypeKey> {
    return bindingsCache.keys
  }

  /** Returns all bindings for similarity checking. */
  fun getAvailableBindings(): Map<IrTypeKey, IrBinding> {
    return bindingsCache.mapValues { it.value }
  }

  /** Returns the first binding for a given type key, or null if none exist. */
  operator fun get(typeKey: IrTypeKey): IrBinding? = bindingsCache[typeKey]

  operator fun contains(typeKey: IrTypeKey): Boolean = typeKey in bindingsCache

  private fun putBindingInner(binding: IrBinding) {
    val previous = bindingsCache.put(binding.typeKey, binding)

    /**
     * Only track as duplicate if it's a different binding instance. The same binding instance can
     * be added through multiple paths (e.g., inherited from parent and also directly contributed),
     * which is not a true duplicate.
     */
    if (previous != null && previous !== binding) {
      duplicateBindings.getOrInit(binding.typeKey).run {
        add(previous)
        add(binding)
      }
    }
  }

  /**
   * Adds a binding to the cache. Multiple bindings for the same key are tracked as duplicates.
   *
   * @param isLocallyDeclared If true, this binding is declared in the current graph (not inherited
   *   from parents). Used for unused key reporting.
   */
  context(context: IrMetroContext)
  fun putBinding(binding: IrBinding, isLocallyDeclared: Boolean = false) {
    putBindingInner(binding)

    if (isLocallyDeclared) {
      locallyDeclaredKeys += binding.typeKey
    }

    // If this is a multibinding contributor, register it
    when (binding) {
      is IrBinding.BindingWithAnnotations if binding.annotations.isIntoMultibinding -> {
        val (qualifier, valueType) =
          when (binding) {
            is Provided ->
              binding.providerFactory.rawTypeKey.qualifier to binding.contextualTypeKey.typeKey.type

            is Alias ->
              binding.bindsCallable?.callableMetadata?.annotations?.qualifier to
                binding.contextualTypeKey.typeKey.type

            else -> null to null
          }
        if (valueType != null) {
          val multibindingTypeKey =
            computeMultibindingTypeKey(
              annotations = binding.annotations,
              valueType = valueType,
              qualifier = qualifier,
            )
          registerMultibindingContribution(multibindingTypeKey, binding.typeKey)
        }
      }

      is IrBinding.MembersInjected -> {
        @Suppress("RETURN_VALUE_NOT_USED") hydrateMemberInjectionAncestors(binding.typeKey)
      }

      else -> {
        // Nothing
      }
    }
  }

  /** Clears all bindings for a given type key. */
  fun clearBindings(typeKey: IrTypeKey) {
    bindingsCache.remove(typeKey)
  }

  // Nukes all the caches and declarations here
  fun clear() {
    bindingsCache.clear()
    duplicateBindings.clear()
    classBindingsCache.clear()
    parentGraphDepCache.clear()
    lazyParentKeys.clear()
    multibindingsCache.clear()
    multibindingsByBindingId.clear()
    multibindsDeclarations.clear()
    optionalBindingDeclarations.clear()
    optionalBindingsCache.clear()
    locallyDeclaredKeys.clear()
    registeredInjectorFunctions.clear()
  }

  fun addLazyParentKey(typeKey: IrTypeKey, bindingFactory: () -> IrBinding) {
    lazyParentKeys[typeKey] = memoize(bindingFactory)
  }

  /**
   * Registers an injector function for deferred MembersInjected binding creation. The actual
   * binding will be created in [computeMembersInjectorBindings] when the type is looked up.
   */
  fun registerInjectorFunction(
    typeKey: IrTypeKey,
    function: IrSimpleFunction,
    callableId: CallableId,
  ) {
    registeredInjectorFunctions[typeKey] = InjectorFunctionInfo(function, callableId)
    // Track as locally declared for unused key reporting
    locallyDeclaredKeys += typeKey
  }

  /** Keys explicitly declared in this graph (used for unused key reporting). */
  fun getDeclaredKeys(): Set<IrTypeKey> = locallyDeclaredKeys

  /** Tracks a key as locally declared without adding a binding to the cache. */
  fun trackDeclaredKey(typeKey: IrTypeKey) {
    locallyDeclaredKeys += typeKey
  }

  /**
   * Computes the multibinding type key (Set<T> or Map<K, V>) from the annotations of a contributor.
   */
  context(context: IrMetroContext)
  private fun computeMultibindingTypeKey(
    annotations: dev.zacsweers.metro.compiler.MetroAnnotations<IrAnnotation>,
    valueType: IrType,
    qualifier: IrAnnotation?,
  ): IrTypeKey {
    return when {
      annotations.isIntoSet -> {
        val setType = metroContext.irBuiltIns.setClass.typeWith(valueType)
        IrTypeKey(setType, qualifier)
      }
      annotations.isElementsIntoSet -> {
        val elementType = (valueType as IrSimpleType).arguments.single().typeOrFail
        val setType = metroContext.irBuiltIns.setClass.typeWith(elementType)
        IrTypeKey(setType, qualifier)
      }
      annotations.isIntoMap -> {
        val mapKey = annotations.mapKey ?: reportCompilerBug("Missing @MapKey for @IntoMap binding")
        val keyType = mapKeyType(mapKey)
        val mapType = metroContext.irBuiltIns.mapClass.typeWith(keyType, valueType)
        IrTypeKey(mapType, qualifier)
      }
      else -> reportCompilerBug("Unknown multibinding type")
    }
  }

  /**
   * Registers a contribution to a multibinding. Eagerly creates the multibinding if it doesn't
   * exist yet.
   *
   * @param multibindingTypeKey The multibinding type key (Set<T> or Map<K, V>)
   * @param sourceBindingKey The source binding key that contributes to the multibinding (must have
   *   `@MultibindingElement` qualifier)
   */
  context(context: IrMetroContext)
  private fun registerMultibindingContribution(
    multibindingTypeKey: IrTypeKey,
    sourceBindingKey: IrTypeKey,
  ) {
    val bindingId = sourceBindingKey.multibindingBindingId ?: return

    // Get or create the multibinding
    val multibinding =
      multibindingsByBindingId.getOrPut(bindingId) {
        val newMultibinding = IrBinding.Multibinding.fromContributor(multibindingTypeKey)
        multibindingsCache[multibindingTypeKey] = newMultibinding
        newMultibinding
      }

    multibinding.addSourceBinding(sourceBindingKey)
  }

  /**
   * Registers a contribution to a multibinding by its bindingId. This is used for contributions
   * from parent graphs that come with `@MultibindingElement` qualifier. If a multibinding with this
   * bindingId already exists, the contribution is added directly to it. Otherwise, the multibinding
   * is created using the multibindingTypeKey from the source binding.
   *
   * @param sourceBindingKey The source binding key that contributes to the multibinding (must have
   *   `@MultibindingElement` qualifier and multibindingTypeKey)
   */
  context(context: IrMetroContext)
  fun registerMultibindingContributionByBindingId(sourceBindingKey: IrTypeKey) {
    val bindingId = sourceBindingKey.multibindingBindingId ?: return
    val multibindingKeyData = sourceBindingKey.multibindingKeyData ?: return
    val originalElementTypeKey = multibindingKeyData.multibindingTypeKey ?: return

    val multibindingTypeKey =
      if (multibindingKeyData.mapKey == null) {
        // It's a Set
        val elementType =
          if (multibindingKeyData.isElementsIntoSet) {
            // It's a collection type Collection<AuthInterceptor>, pull out the element type
            originalElementTypeKey.type.requireSimpleType().arguments[0].typeOrFail
          } else {
            originalElementTypeKey.type
          }
        originalElementTypeKey.copy(context.irBuiltIns.setClass.typeWith(elementType))
      } else {
        // It's a map
        val keyType = mapKeyType(multibindingKeyData.mapKey)
        originalElementTypeKey.copy(
          context.irBuiltIns.mapClass.typeWith(keyType, originalElementTypeKey.type)
        )
      }

    // Get or create the multibinding using the type key from the source binding
    val multibinding =
      multibindingsByBindingId.getOrPut(bindingId) {
        val newMultibinding = IrBinding.Multibinding.fromContributor(multibindingTypeKey)
        multibindingsCache[multibindingTypeKey] = newMultibinding
        newMultibinding
      }

    multibinding.addSourceBinding(sourceBindingKey)
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
   * Registers a @BindsOptionalOf declaration.
   *
   * @param typeKey The Optional<T> type key
   * @param callable The @BindsOptionalOf callable
   */
  fun registerOptionalBinding(typeKey: IrTypeKey, callable: BindsOptionalOfCallable) {
    optionalBindingDeclarations.getAndAdd(typeKey, callable)
  }

  /**
   * Returns all registered multibindings for similarity checking. This includes both cached
   * multibindings (eagerly created when contributions are registered) and those from explicit
   *
   * @Multibinds declarations.
   */
  context(context: IrMetroContext)
  fun getAvailableMultibindings(): Map<IrTypeKey, IrBinding.Multibinding> {
    // Ensure all @Multibinds declarations have their multibindings created
    for (key in multibindsDeclarations.keys) {
      @Suppress("RETURN_VALUE_NOT_USED") getOrCreateMultibindingIfNeeded(key)
    }
    return multibindingsCache
  }

  /**
   * Gets or creates a multibinding for the given type key. For @Multibinds declarations, creates
   * from the declaration. Otherwise, checks if one was already created from contributions. Returns
   * null if there are no contributions or declarations for this type key.
   */
  context(context: IrMetroContext)
  private fun getOrCreateMultibindingIfNeeded(typeKey: IrTypeKey): IrBinding.Multibinding? {
    // Check cache first
    multibindingsCache[typeKey]?.let {
      return it
    }

    // Check if we have a @Multibinds declaration
    val declaration = multibindsDeclarations[typeKey] ?: return null

    // Create multibinding from the declaration
    val multibinding =
      IrBinding.Multibinding.fromMultibindsDeclaration(
        getter = declaration.declaration,
        multibinds = declaration.annotation,
        contextualTypeKey = IrContextualTypeKey(typeKey),
      )

    // Check if a multibinding was already created from contributions with the same bindingId
    val existingFromContributions = multibindingsByBindingId[multibinding.bindingId]
    if (existingFromContributions != null) {
      // Use the existing one but update declaration info if needed
      // The source bindings are already registered on it
      multibindingsCache[typeKey] = existingFromContributions
      return existingFromContributions
    }

    // Cache the new multibinding
    multibindingsCache[typeKey] = multibinding
    multibindingsByBindingId[multibinding.bindingId] = multibinding

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

  /**
   * Lazily creates an optional binding for the given type key if it has a @BindsOptionalOf
   * declaration. Returns null if there is no declaration for this type key.
   */
  context(context: IrMetroContext)
  private fun getOrCreateOptionalBindingIfNeeded(typeKey: IrTypeKey): IrBinding.CustomWrapper? {
    // Check cache first
    optionalBindingsCache[typeKey]?.let {
      return it
    }

    // Check if we have a @BindsOptionalOf declaration
    val callables = optionalBindingDeclarations[typeKey] ?: return null

    // Get the first callable for metadata
    val callable = callables.first()
    val declaration = callable.function

    // Extract the wrapped type from Optional<T>
    val wrappedType =
      typeKey.type.optionalType(declaration)
        ?: reportCompilerBug(
          "Optional type not supported: ${typeKey.type.rawType().classIdOrFail.asSingleFqName()}"
        )

    // Create the context key with hasDefault=true to allow absence
    val contextKey =
      wrappedType.asContextualTypeKey(
        qualifierAnnotation = typeKey.qualifier,
        hasDefault = true,
        patchMutableCollections = true,
        declaration = null,
      )

    val binding =
      IrBinding.CustomWrapper(
        typeKey = typeKey,
        wrapperKey = IrOptionalExpressionGenerator.key,
        allowsAbsent = true,
        declaration = declaration,
        wrappedType = wrappedType,
        wrappedContextKey = contextKey,
      )

    // Cache and return
    optionalBindingsCache[typeKey] = binding
    return binding
  }

  context(context: IrMetroContext)
  private fun IrClass.computeMembersInjectorBindings(
    remapper: TypeRemapper
  ): LinkedHashSet<IrBinding.MembersInjected> {
    val bindings = LinkedHashSet<IrBinding.MembersInjected>()

    // Track supertype injector keys as we iterate. The list from findMemberInjectors is in
    // base-to-derived order, so we accumulate previous entries as supertypes for later ones.
    val supertypeInjectorKeys = mutableListOf<IrContextualTypeKey>()

    // Accumulate parameters and dependencies from all ancestors as we iterate (base-to-derived
    // order)
    var accumulatedParameters = Parameters.empty()
    var accumulatedDeps = emptySet<IrContextualTypeKey>()

    for (generatedInjector in findMemberInjectors(this)) {
      val mappedTypeKey = generatedInjector.typeKey.remapTypes(remapper)

      // Get this injector's parameters and compute its dependencies
      val remappedParameters = generatedInjector.mergedParameters(remapper)
      val thisDeps =
        remappedParameters.nonDispatchParameters
          .filterNot { it.isAssisted }
          .mapToSet { it.contextualTypeKey }

      // Save current accumulated deps as supertype deps for this binding, then add this level's
      // deps
      val supertypeDeps = accumulatedDeps
      accumulatedDeps = accumulatedDeps + thisDeps

      // Accumulate parameters
      accumulatedParameters = accumulatedParameters.mergeValueParametersWith(remappedParameters)

      bindingsCache[mappedTypeKey]?.let { cached ->
        if (cached is IrBinding.MembersInjected) {
          bindings += cached
        } else {
          reportCompilerBug(
            "Found cached binding for $mappedTypeKey but wasn't a member injector! $cached"
          )
        }
        supertypeInjectorKeys += IrContextualTypeKey(mappedTypeKey)
        continue
      }

      val contextKey = IrContextualTypeKey(mappedTypeKey)

      // Copy the current supertype keys before adding this one
      val currentSupertypeKeys = supertypeInjectorKeys.toList()

      // Check if there's a registered injector function for this type
      val injectorInfo = registeredInjectorFunctions[mappedTypeKey]

      val binding =
        IrBinding.MembersInjected(
          contextKey,
          // Use accumulated parameters from all ancestors
          parameters =
            if (injectorInfo != null) {
              accumulatedParameters.withCallableId(injectorInfo.callableId)
            } else {
              accumulatedParameters
            },
          reportableDeclaration = injectorInfo?.function ?: this,
          function = injectorInfo?.function,
          isFromInjectorFunction = injectorInfo != null,
          // Unpack the target class from the type
          targetClassId =
            mappedTypeKey.type
              .requireSimpleType(this)
              .arguments[0]
              .typeOrFail
              .rawType()
              .classIdOrFail,
          supertypeMembersInjectorKeys = currentSupertypeKeys,
          supertypeDependencies = supertypeDeps,
        )

      bindings += binding

      // Add this injector's key as a supertype for subsequent iterations
      supertypeInjectorKeys += contextKey

      putBindingInner(binding)
    }
    return bindings
  }

  /**
   * Looks up bindings for the given [contextKey] or returns an empty set. If multiple bindings
   * exist for the same key, reports a duplicate binding error.
   */
  internal fun lookup(
    contextKey: IrContextualTypeKey,
    currentBindings: ScatterMap<IrTypeKey, IrBinding>,
    stack: IrBindingStack,
    onDuplicateBindings: (IrTypeKey, List<IrBinding>) -> Unit,
  ): Set<IrBinding> =
    context(metroContext) {
      val key = contextKey.typeKey

      // First check cached bindings
      bindingsCache[key]?.let { binding ->
        // Report duplicates if there are multiple bindings
        duplicateBindings[key]?.let { onDuplicateBindings(key, it.toList()) }

        // Check if this is available from parent and is scoped
        if (binding.scope != null && parentContext?.contains(key) == true) {
          val token = parentContext.mark(key, binding.scope!!)
          return setOf(createParentGraphDependency(key, token!!))
        }
        return setOf(binding)
      }

      // Check for lazy parent keys
      lazyParentKeys[key]?.let { lazyBinding ->
        return setOf(lazyBinding.value)
      }

      // Check for multibindings (Set<T> or Map<K, V> with contributions)
      getOrCreateMultibindingIfNeeded(key)?.let { multibinding ->
        return setOf(multibinding)
      }

      // Check for optional bindings (Optional<T>)
      getOrCreateOptionalBindingIfNeeded(key)?.let { optionalBinding ->
        return setOf(optionalBinding)
      }

      if (contextKey.typeKey.type.isNullable()) {
        // If we reach here, do not try to proceed to class lookups. We don't implicitly make an
        // injected class satisfy a nullable binding of it
        return emptySet()
      }

      // Finally, fall back to class-based lookup and cache the result
      val classBindings: Set<IrBinding> =
        lookupClassBinding(contextKey, currentBindings, stack)
          // Filter out previously-seen ancestor member injectors as we don't need to re-add them
          // but may come with the lookup if there are multiple injected subclasses
          .filterNotTo(mutableSetOf()) {
            it is IrBinding.MembersInjected && it.typeKey in currentBindings
          }

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
              val token = parentContext.mark(key, scope)
              remappedBindings += createParentGraphDependency(key, token!!)
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
    token: ParentContext.Token,
  ): IrBinding.GraphDependency {
    val parentGraph = parentContext!!.currentParentGraph
    val cacheKey = ParentGraphDepKey(parentGraph, key)
    return parentGraphDepCache.getOrPut(cacheKey) {
      val parentTypeKey = IrTypeKey(parentGraph.typeWith())

      IrBinding.GraphDependency(
        ownerKey = parentTypeKey,
        graph = sourceGraph,
        token = token,
        typeKey = key,
      )
    }
  }

  context(context: IrMetroContext)
  private fun hydrateMemberInjectionAncestors(key: IrTypeKey): Set<IrBinding.MembersInjected> {
    val targetType = key.type.requireSimpleType().arguments.first().typeOrFail
    val targetClass = targetType.rawType()
    val remapper = targetClass.deepRemapperFor(targetType)
    val bindings = targetClass.computeMembersInjectorBindings(remapper)

    // Check if we have a binding for this exact key (cached by computeMembersInjectorBindings)
    if (key in bindingsCache) {
      return bindings
    }

    // No exact match - this happens with wildcard types like Base<*>
    // Check if there's a registered injector function for this key
    val injectorInfo = registeredInjectorFunctions[key] ?: return bindings

    if (bindings.isEmpty()) {
      // No member injectors exist, create a minimal binding for the injector function
      val binding =
        IrBinding.MembersInjected(
          IrContextualTypeKey(key),
          parameters = Parameters.empty().withCallableId(injectorInfo.callableId),
          reportableDeclaration = injectorInfo.function,
          function = injectorInfo.function,
          isFromInjectorFunction = true,
          targetClassId = targetClass.classIdOrFail,
          supertypeMembersInjectorKeys = emptyList(),
          supertypeDependencies = emptySet(),
        )

      putBindingInner(binding)
      return bindings + binding
    }

    // Use the most derived binding's data but with the wildcard lookup key
    val mostDerivedBinding = bindings.removeLast()
    val supertypeKeys = bindings.map { it.contextualTypeKey }

    val binding =
      IrBinding.MembersInjected(
        IrContextualTypeKey(key),
        parameters = mostDerivedBinding.parameters.withCallableId(injectorInfo.callableId),
        reportableDeclaration = injectorInfo.function,
        function = injectorInfo.function,
        isFromInjectorFunction = true,
        targetClassId = targetClass.classIdOrFail,
        supertypeMembersInjectorKeys = supertypeKeys,
        supertypeDependencies = mostDerivedBinding.supertypeDependencies,
      )
    putBindingInner(binding)
    return bindings + binding
  }

  context(context: IrMetroContext)
  private fun lookupClassBinding(
    contextKey: IrContextualTypeKey,
    currentBindings: ScatterMap<IrTypeKey, IrBinding>,
    stack: IrBindingStack,
  ): Set<IrBinding> {
    return classBindingsCache.getOrPut(contextKey) {
      val key = contextKey.typeKey
      val irClass = key.type.rawType()

      if (irClass.classId == context.metroSymbols.metroMembersInjector.owner.classId) {
        return hydrateMemberInjectionAncestors(key)
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
          irClass.cachedConstructorInjectedBinding.takeIf {
            // Allow use of cached instances if no generics
            remapper == NOOP_TYPE_REMAPPER
          }
            ?: IrBinding.ConstructorInjected(
                type = irClass,
                classFactory = mappedFactory,
                annotations = classAnnotations,
                typeKey = key,
                injectedMembers =
                  membersInjectBindings.value.mapToSet { binding -> binding.contextualTypeKey },
              )
              .alsoIf(remapper == NOOP_TYPE_REMAPPER) {
                irClass.cachedConstructorInjectedBinding = it
              }

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
          irClass.cacheAssistedBinding.takeIf {
            // Allow use of cached instances if no generics
            remapper == NOOP_TYPE_REMAPPER
          }
            ?: IrBinding.Assisted(
                type = irClass,
                function = function,
                annotations = classAnnotations,
                typeKey = key,
                parameters = function.parameters(),
                target = targetContextualTypeKey,
              )
              .alsoIf(remapper == NOOP_TYPE_REMAPPER) { irClass.cacheAssistedBinding = it }
      } else if (contextKey.hasDefault) {
        bindings += IrBinding.Absent(key)
      } else {
        // It's a regular class, not injected, not assisted. Initialize member injections still just
        // in case
        @Suppress("RETURN_VALUE_NOT_USED") membersInjectBindings.value
      }
      bindings
    }
  }
}

/** Cached [IrBinding.ConstructorInjected] binding for this class factory. */
internal var IrClass.cachedConstructorInjectedBinding: IrBinding.ConstructorInjected? by
  irAttribute(copyByDefault = false)

/** Cached [IrBinding.ConstructorInjected] binding for this class factory. */
internal var IrClass.cacheAssistedBinding: IrBinding.Assisted? by irAttribute(copyByDefault = false)
