// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import androidx.collection.MutableScatterMap
import androidx.collection.ScatterMap
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.compiler.diagnostics.buildText
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.getOrInit
import dev.zacsweers.metro.compiler.graph.DerivedCandidateSelection
import dev.zacsweers.metro.compiler.graph.MAX_DERIVED_BINDING_DEPTH
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.graph.explanation.BindingReason
import dev.zacsweers.metro.compiler.graph.isDivergentDerivation
import dev.zacsweers.metro.compiler.graph.selectBinding
import dev.zacsweers.metro.compiler.graph.selectMostSpecificCandidate
import dev.zacsweers.metro.compiler.graph.toText
import dev.zacsweers.metro.compiler.graph.toTraceSection
import dev.zacsweers.metro.compiler.ir.BindsCallable
import dev.zacsweers.metro.compiler.ir.BindsOptionalOfCallable
import dev.zacsweers.metro.compiler.ir.ClassFactory
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.NOOP_TYPE_REMAPPER
import dev.zacsweers.metro.compiler.ir.ParentContext
import dev.zacsweers.metro.compiler.ir.ParentContextReader
import dev.zacsweers.metro.compiler.ir.allowEmpty
import dev.zacsweers.metro.compiler.ir.asContextualTypeKey
import dev.zacsweers.metro.compiler.ir.asMemberOf
import dev.zacsweers.metro.compiler.ir.declarationMirrorFunctionOrNull
import dev.zacsweers.metro.compiler.ir.deepRemapperFor
import dev.zacsweers.metro.compiler.ir.graph.expressions.IrOptionalExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.expressions.optionalType
import dev.zacsweers.metro.compiler.ir.graph.reporting.BindingDecisionCapture
import dev.zacsweers.metro.compiler.ir.mapKeyType
import dev.zacsweers.metro.compiler.ir.padForConsole
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.remapTypes
import dev.zacsweers.metro.compiler.ir.render
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.requireSimpleType
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer.MemberInjectClass
import dev.zacsweers.metro.compiler.ir.wrapInProvider
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

internal class BindingLookup(
  private val metroContext: IrMetroContext,
  private val sourceGraph: IrClass,
  private val findClassFactory: (IrClass) -> ClassFactory?,
  private val findMemberInjectors: (IrClass) -> List<MemberInjectClass>,
  internal val parentContext: ParentContextReader?,
  private val bindingLookupCache: BindingLookupCache,
  private val decisionCapture: BindingDecisionCapture? = null,
) {

  // The latest binding registered for each key.
  private val bindingsCache = mutableMapOf<IrTypeKey, IrBinding>()
  // Every binding for a key that was registered more than once, kept for duplicate reporting.
  private val duplicateBindings = mutableMapOf<IrTypeKey, MutableSet<IrBinding>>()
  private val classBindingsCache = mutableMapOf<ClassBindingKey, Set<IrBinding>>()

  // Mutable remappers belong to this graph's lookup.
  private val typeRemappers = MutableScatterMap<Pair<IrClass, IrType>, TypeRemapper>()

  /** Reuses substitutions for the same source class and concrete type within this graph. */
  private fun IrClass.cachedDeepRemapperFor(subtype: IrType): TypeRemapper =
    typeRemappers.getOrPut(this to subtype) { deepRemapperFor(subtype) }

  /** Keeps optional misses separate because contextual-key equality ignores default values. */
  private data class ClassBindingKey(
    val contextKey: IrContextualTypeKey,
    val hasDefault: Boolean,
  )

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

  // Type keys for non-multibinding Map bindings, for targeted incompatible value type validation
  val directMapTypeKeys: Set<IrTypeKey>
    field: MutableSet<IrTypeKey> = mutableSetOf()

  // Tracks the actual requested contextual type when a direct Map binding is skipped
  // (e.g., the Map<K, Provider<V>> that was requested but couldn't be satisfied)
  val skippedDirectMapRequests: Map<IrTypeKey, IrContextualTypeKey>
    field: MutableMap<IrTypeKey, IrContextualTypeKey> = mutableMapOf()

  /** Information about a registered injector function for MembersInjected binding creation. */
  private data class InjectorFunctionInfo(
    val function: IrSimpleFunction,
    val callableId: CallableId,
  )

  // Registered injector functions - binding creation deferred to computeMembersInjectorBindings
  private val registeredInjectorFunctions = mutableMapOf<IrTypeKey, InjectorFunctionInfo>()

  /**
   * Generic providers and binds callables by the raw class and qualifier of the key they provide.
   * Their keys are patterns, so they never enter [bindingsCache]. Each list keeps registration
   * order, which keeps lookups deterministic.
   */
  private val genericBindingIndex =
    mutableMapOf<GenericBindingIndexKey, MutableList<GenericBindingCandidate>>()

  /** True if this graph collected no generic providers or binds callables. */
  val hasNoGenericBindingCandidates: Boolean
    get() = genericBindingIndex.isEmpty()

  /** All generic candidates in registration order. */
  val allGenericBindingCandidates: Sequence<GenericBindingCandidate>
    get() = genericBindingIndex.values.asSequence().flatten()

  /**
   * Returns the generic candidates whose provided key has the raw class [classId] and the
   * [qualifier], in registration order.
   */
  fun genericBindingCandidatesFor(
    classId: ClassId,
    qualifier: IrAnnotation?,
  ): List<GenericBindingCandidate> {
    return genericBindingIndex[GenericBindingIndexKey(classId, qualifier)].orEmpty()
  }

  /** Adds a generic provider or binds callable to the generic binding index. */
  fun registerGenericBindingCandidate(candidate: GenericBindingCandidate) {
    candidate.checkPatternKey()
    val key = GenericBindingIndexKey(candidate.rawClassId, candidate.patternKey.qualifier)
    genericBindingIndex.getAndAdd(key, candidate)
  }

  private data class GenericBindingIndexKey(val classId: ClassId, val qualifier: IrAnnotation?)

  /**
   * Removes generic candidates inherited from parent graphs when this graph declares its own
   * candidate for the same keys.
   *
   * A graph extension sees its parents' generic providers. Say a parent's provider returns
   * `Encoder<List<T>>` and the extension's own provider returns `Encoder<List<E>>`. Both match
   * `Encoder<List<String>>`, so that key would be ambiguous in the extension. Removing the parent's
   * candidate makes the extension's own provider win. An ordinary provider in an extension wins
   * over a parent's provider for the same key in the same way.
   *
   * Two candidates provide the same keys when [GenericBindingCandidate.isAlphaEquivalentTo] holds.
   */
  fun removeInheritedGenericCandidatesRedeclaredLocally() {
    for (candidates in genericBindingIndex.values) {
      // Only a candidate this graph declares can replace an inherited one.
      val local = candidates.filter { it.isLocallyDeclared }
      if (local.isEmpty()) {
        continue
      }
      candidates.removeAll { candidate ->
        val isInherited = !candidate.isLocallyDeclared
        isInherited && local.any { it.isAlphaEquivalentTo(candidate) }
      }
    }
  }

  /** Each key the derived stage resolved, with the candidate and type arguments it used. */
  val derivedBindings: Map<IrTypeKey, DerivedBinding>
    field: MutableMap<IrTypeKey, DerivedBinding> = mutableMapOf()

  /** Keys that several generic candidates matched with no most specific one. */
  val ambiguousDerivedBindings: Map<IrTypeKey, DerivedBindingConflict>
    field: MutableMap<IrTypeKey, DerivedBindingConflict> = mutableMapOf()

  /** Keys that several generic candidates matched where the most specific one won. */
  val overlappingDerivedBindings: Map<IrTypeKey, DerivedBindingConflict>
    field: MutableMap<IrTypeKey, DerivedBindingConflict> = mutableMapOf()

  /**
   * Keys the derived stage rejected because their derivation kept growing or passed the depth
   * limit. The derived stage stops at these keys, and the graph reports them instead of a missing
   * binding.
   */
  val divergentDerivedBindings: Map<IrTypeKey, DivergentDerivedBinding>
    field: MutableMap<IrTypeKey, DivergentDerivedBinding> = mutableMapOf()

  /**
   * The key of the binding that first requested each key this lookup resolved. Roots map to null.
   * The binding stack only holds the current request, so the derived stage walks these links to see
   * its whole chain. They're only recorded when the generic index isn't empty.
   */
  private val requesterKeys = mutableMapOf<IrTypeKey, IrTypeKey?>()

  /** Returns how the derived stage resolved [key], or null if it didn't. */
  fun derivedBindingFor(key: IrTypeKey): DerivedBinding? = derivedBindings[key]

  fun getAvailableKeys(): Set<IrTypeKey> {
    return bindingsCache.keys
  }

  /** Returns all bindings for similarity checking. */
  fun getAvailableBindings(): Map<IrTypeKey, IrBinding> = bindingsCache

  /** Returns the first binding for a given type key, or null if none exist. */
  operator fun get(typeKey: IrTypeKey): IrBinding? = bindingsCache[typeKey]

  operator fun contains(typeKey: IrTypeKey): Boolean = typeKey in bindingsCache

  private fun putBindingInner(binding: IrBinding) {
    val previous = bindingsCache.put(binding.typeKey, binding)

    if (previous != null) {
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

    // Track non-multibinding Map bindings for targeted validation.
    // Direct Map<K, V> bindings cannot satisfy Map<K, Provider<V>> requests.
    if (
      binding !is IrBinding.Multibinding && binding.contextualTypeKey.wrappedType is WrappedType.Map
    ) {
      directMapTypeKeys += binding.typeKey
    }

    // If this is a multibinding contributor, register it
    when (binding) {
      is IrBinding.BindingWithAnnotations if binding.annotations.isIntoMultibinding -> {
        // The contribution's qualifier and value type decide which multibinding it joins.
        val (qualifier, valueType) =
          when (binding) {
            is Provided ->
              binding.providerFactory.rawTypeKey.qualifier to binding.contextualTypeKey.typeKey.type

            is Alias ->
              binding.bindsCallable?.callableMetadata?.annotations?.qualifier to
                binding.contextualTypeKey.typeKey.type
          }
        val multibindingTypeKey =
          computeMultibindingTypeKey(
            annotations = binding.annotations,
            valueType = valueType,
            qualifier = qualifier,
          )
        registerMultibindingContribution(multibindingTypeKey, binding.typeKey)
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
    typeRemappers.clear()
    parentGraphDepCache.clear()
    lazyParentKeys.clear()
    multibindingsCache.clear()
    multibindingsByBindingId.clear()
    multibindsDeclarations.clear()
    materializedMultibindsDeclarationsSize = 0
    optionalBindingDeclarations.clear()
    optionalBindingsCache.clear()
    locallyDeclaredKeys.clear()
    directMapTypeKeys.clear()
    skippedDirectMapRequests.clear()
    registeredInjectorFunctions.clear()
    genericBindingIndex.clear()
    derivedBindings.clear()
    ambiguousDerivedBindings.clear()
    overlappingDerivedBindings.clear()
    divergentDerivedBindings.clear()
    requesterKeys.clear()
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

  /** Returns the set of graph-private keys from parent graphs, for missing binding hints. */
  fun getParentGraphPrivateKeys(): Set<IrTypeKey> = parentContext?.graphPrivateKeys() ?: emptySet()

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
          context.irBuiltIns.mapClass.typeWith(keyType, originalElementTypeKey.type),
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
   * The number of [multibindsDeclarations] entries that [getAvailableMultibindings] has already
   * materialized into [multibindingsCache]. Registering new declarations grows the map past this,
   * so the next call knows it has more to materialize.
   */
  private var materializedMultibindsDeclarationsSize = 0

  /**
   * Returns all registered multibindings for similarity checking. This includes both cached
   * multibindings (eagerly created when contributions are registered) and those from explicit
   * `@Multibinds` declarations.
   */
  context(context: IrMetroContext)
  fun getAvailableMultibindings(): Map<IrTypeKey, IrBinding.Multibinding> {
    // Ensure all @Multibinds declarations have their multibindings created. Skip the iteration
    // entirely once we've already processed every declaration — this is called per missing-binding
    // hint during error reporting where the inner cache lookups would otherwise be the dominant
    // cost.
    val declarations = multibindsDeclarations
    if (materializedMultibindsDeclarationsSize != declarations.size) {
      for (key in declarations.keys) {
        @Suppress("RETURN_VALUE_NOT_USED") getOrCreateMultibindingIfNeeded(key)
      }
      materializedMultibindsDeclarationsSize = declarations.size
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
          "Optional type not supported: ${typeKey.type.rawType().classIdOrFail.asSingleFqName()}",
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
    remapper: TypeRemapper,
  ): LinkedHashSet<IrBinding.MembersInjected> {
    val bindings = LinkedHashSet<IrBinding.MembersInjected>()

    // Track supertype injector keys as we iterate. The list from findMemberInjectors is in
    // base-to-derived order, so we accumulate previous entries as supertypes for later ones.
    val supertypeInjectorKeys = mutableListOf<IrContextualTypeKey>()

    // Accumulate parameters and dependencies from all ancestors as we iterate, base to derived.
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

      // Save the deps accumulated so far as this binding's supertype deps, then add this level's.
      val supertypeDeps = accumulatedDeps
      accumulatedDeps = accumulatedDeps + thisDeps

      // Accumulate parameters
      accumulatedParameters = accumulatedParameters.mergeValueParametersWith(remappedParameters)

      bindingsCache[mappedTypeKey]?.let { cached ->
        if (cached is IrBinding.MembersInjected) {
          bindings += cached
        } else {
          reportCompilerBug(
            "Found cached binding for $mappedTypeKey but wasn't a member injector! $cached",
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
   *
   * Each fallback runs only after earlier lookups miss. Caches and parent marks belong to this
   * graph.
   */
  internal fun lookup(
    contextKey: IrContextualTypeKey,
    currentBindings: ScatterMap<IrTypeKey, IrBinding>,
    stack: IrBindingStack,
    onDuplicateBindings: (IrTypeKey, List<IrBinding>) -> Unit,
  ): Set<IrBinding> =
    context(metroContext) {
      val key = contextKey.typeKey

      val selected =
        selectBinding<Set<IrBinding>>(
          registered = { lookupRegisteredBinding(contextKey, stack, onDuplicateBindings) },
          multibinding = { lookupMultibinding(contextKey, stack) },
          optional = { lookupOptionalBinding(contextKey, stack) },
          derived = { deriveBinding(contextKey, stack) },
          implicit = { lookupImplicitBinding(contextKey, currentBindings, stack) },
        )
      val resolved = selected ?: emptySet()

      // Only the derived stage reads requester links, so skip them when it can't run.
      if (!hasNoGenericBindingCandidates) {
        recordRequester(key, stack, resolved)
      }
      resolved
    }

  /**
   * Returns the binding already registered for [contextKey]'s key. Returns null if there isn't one
   * that can satisfy this request.
   */
  private fun lookupRegisteredBinding(
    contextKey: IrContextualTypeKey,
    stack: IrBindingStack,
    onDuplicateBindings: (IrTypeKey, List<IrBinding>) -> Unit,
  ): Set<IrBinding>? {
    val key = contextKey.typeKey
    val cached = bindingsCache[key]
    if (cached != null) {
      val binding = resolveCachedBinding(contextKey, stack, cached, onDuplicateBindings)
      if (binding != null) {
        return setOf(binding)
      }
    }

    // Parent keys create their bindings on first use.
    val lazyParentBinding = lazyParentKeys[key]?.value
    if (lazyParentBinding != null) {
      decisionCapture?.selected(contextKey, stack, lazyParentBinding)
      return setOf(lazyParentBinding)
    }
    return null
  }

  /**
   * Returns what a request for [contextKey] gets when [binding] is cached for its key. Returns null
   * when [binding] can't satisfy the request.
   */
  private fun resolveCachedBinding(
    contextKey: IrContextualTypeKey,
    stack: IrBindingStack,
    binding: IrBinding,
    onDuplicateBindings: (IrTypeKey, List<IrBinding>) -> Unit,
  ): IrBinding? {
    val key = contextKey.typeKey

    if (cannotSupplyWrappedMapValues(contextKey, binding)) {
      // Remember the request, then fall through to a missing binding.
      skippedDirectMapRequests[key] = contextKey
      decisionCapture?.rejected(contextKey, stack, binding, BindingReason.INCOMPATIBLE_MAP_VALUE)
      return null
    }

    // Report duplicates if there are multiple bindings
    duplicateBindings[key]?.let { onDuplicateBindings(key, it.toList()) }

    val parentToken = parentTokenForScopedBinding(key, binding)
    if (parentToken != null) {
      // A parent owns this scoped binding, so this graph depends on the parent for it.
      val parentBinding = createParentGraphDependency(key, parentToken)
      decisionCapture?.selected(
        contextKey,
        stack,
        binding,
        conflicts = duplicateBindings[key],
        ownerGraph = parentToken.ownerGraphKey,
        resolvedBinding = parentBinding,
      )
      return parentBinding
    }
    decisionCapture?.selected(contextKey, stack, binding, duplicateBindings[key])
    return binding
  }

  /**
   * Returns true if [contextKey] asks for wrapped map values that [binding] can't supply.
   *
   * A request like `Map<K, Provider<V>>` wraps each value in `Provider`, `Lazy`, or
   * `SuspendProvider`. Only multibinding contributions can supply wrapped map values. A directly
   * provided `Map<K, V>` can't. A directly provided `Map<K, Provider<V>>` still satisfies a `Map<K,
   * Provider<V>>` request, because the wrapping is explicit in its return type.
   */
  private fun cannotSupplyWrappedMapValues(
    contextKey: IrContextualTypeKey,
    binding: IrBinding,
  ): Boolean {
    val requestsWrappedMapValues =
      contextKey.isMapProvider || contextKey.isMapLazy || contextKey.isMapSuspendProvider
    val isDirectMapBinding = contextKey.typeKey in directMapTypeKeys
    val isWrappedRequestForDirectMap = requestsWrappedMapValues && isDirectMapBinding
    if (!isWrappedRequestForDirectMap) {
      return false
    }
    // A declaration whose own type already wraps the values can still satisfy the request.
    val declaresWrappedValues =
      when (binding) {
        is Provided -> binding.providerFactory.contextualTypeKey.isDeferrable
        is IrBinding.GraphDependency -> binding.contextualTypeKey.isDeferrable
        else -> false
      }
    return !declaresWrappedValues
  }

  /**
   * Returns the parent's token for [key] when a parent graph owns the scoped [binding]. Returns
   * null when this graph uses [binding] itself.
   *
   * A binding this graph declares stays here, even if a parent has the same key under a different
   * scope. If graph A provides `Logger` and graph B overrides it with its own `Logger`, graph C
   * must get B's.
   */
  private fun parentTokenForScopedBinding(
    key: IrTypeKey,
    binding: IrBinding,
  ): ParentContext.Token? {
    val scope = binding.scope ?: return null
    if (key in locallyDeclaredKeys) {
      return null
    }
    val parentContext = parentContext ?: return null
    if (key !in parentContext) {
      return null
    }
    return parentContext.mark(key, scope)
      ?: reportCompilerBug("Parent context has no owner for scoped key $key")
  }

  /** Returns the multibinding for [contextKey] if it has contributions or a declaration. */
  context(context: IrMetroContext)
  private fun lookupMultibinding(
    contextKey: IrContextualTypeKey,
    stack: IrBindingStack,
  ): Set<IrBinding>? {
    val multibinding = getOrCreateMultibindingIfNeeded(contextKey.typeKey) ?: return null
    decisionCapture?.selected(contextKey, stack, multibinding)
    return setOf(multibinding)
  }

  /** Returns the optional binding for [contextKey] if it has a `@BindsOptionalOf` declaration. */
  context(context: IrMetroContext)
  private fun lookupOptionalBinding(
    contextKey: IrContextualTypeKey,
    stack: IrBindingStack,
  ): Set<IrBinding>? {
    val optionalBinding = getOrCreateOptionalBindingIfNeeded(contextKey.typeKey) ?: return null
    decisionCapture?.selected(contextKey, stack, optionalBinding)
    return setOf(optionalBinding)
  }

  /**
   * Falls back to a binding the requested class provides itself, like an `@Inject` constructor.
   * Returns an empty set when the class can't satisfy [contextKey].
   */
  context(context: IrMetroContext)
  private fun lookupImplicitBinding(
    contextKey: IrContextualTypeKey,
    currentBindings: ScatterMap<IrTypeKey, IrBinding>,
    stack: IrBindingStack,
  ): Set<IrBinding> {
    val key = contextKey.typeKey
    if (key.type.isNullable()) {
      // An injected class never implicitly satisfies a nullable request for it.
      decisionCapture?.missing(contextKey, stack)
      return emptySet()
    }

    // Finally, fall back to the class-based lookup, which caches its result.
    val classBindings: Set<IrBinding> =
      lookupClassBinding(contextKey, currentBindings, stack)
        // Drop ancestor member injectors the graph already has, since several injected subclasses
        // can return the same ones.
        .filterNotTo(mutableSetOf()) {
          it is IrBinding.MembersInjected && it.typeKey in currentBindings
        }

    if (parentContext == null) {
      decisionCapture?.selected(contextKey, stack, classBindings)
      return classBindings
    }

    // A scoped class binding lives in the parent graph that owns its scope.
    val remappedBindings = mutableSetOf<IrBinding>()
    for (binding in classBindings) {
      val scope = binding.scope
      if (scope != null) {
        val parentBinding = parentGraphDependencyForScope(key, scope)
        if (parentBinding != null) {
          remappedBindings += parentBinding
          continue
        }
      }
      remappedBindings += binding
    }
    decisionCapture?.selected(contextKey, stack, remappedBindings, classBindings)
    return remappedBindings
  }

  /**
   * Records which binding first requested [key] once [resolved] has a binding for it. Population is
   * breadth-first, so the first requester is the discovery path.
   */
  private fun recordRequester(key: IrTypeKey, stack: IrBindingStack, resolved: Set<IrBinding>) {
    if (resolved.isEmpty()) {
      return
    }
    if (key in requesterKeys) {
      return
    }
    requesterKeys[key] = stack.requestingKeyFor(key)
  }

  /**
   * Returns a dependency on the ancestor graph that owns [scope], or null if no ancestor owns it. A
   * scoped binding lives in the graph that owns its scope. Marking the key there makes that graph
   * resolve and keep it.
   */
  private fun parentGraphDependencyForScope(
    key: IrTypeKey,
    scope: IrAnnotation,
  ): IrBinding.GraphDependency? {
    val parentContext = parentContext ?: return null
    val scopeInParent =
      key in parentContext ||
        // Discovered here but unused in the parents, mark it anyway so they include it
        parentContext.containsScope(scope)
    if (!scopeInParent) {
      return null
    }
    val token =
      parentContext.mark(key, scope)
        ?: reportCompilerBug("Parent context has no owner for scoped key $key")
    return createParentGraphDependency(key, token)
  }

  /**
   * Resolves [contextKey] through the generic candidates indexed under its raw class and qualifier.
   * Returns null when none match, so class lookup runs next.
   *
   * The most specific match wins. When none is most specific, the key is recorded as ambiguous and
   * the first match is used, so the graph doesn't also report a missing binding for it. The result
   * is an ordinary binding registered under the requested key, so later lookups reuse it.
   *
   * An inherited scoped candidate whose scope an ancestor owns resolves to a dependency on that
   * ancestor. The ancestor derives the binding itself, like it does for a scoped `@Inject` class.
   *
   * A derivation that keeps growing the key or passes [MAX_DERIVED_BINDING_DEPTH] is recorded in
   * [divergentDerivedBindings]. The result is then empty, so class lookup doesn't run for the key.
   */
  context(context: IrMetroContext)
  private fun deriveBinding(
    contextKey: IrContextualTypeKey,
    stack: IrBindingStack,
  ): Set<IrBinding>? {
    // Most graphs have no generic candidates, so they skip everything below.
    if (hasNoGenericBindingCandidates) {
      return null
    }
    val key = contextKey.typeKey
    // A key whose derivation was already rejected stays rejected.
    if (key in divergentDerivedBindings) {
      return emptySet()
    }
    // A skipped direct map binding falls through to here, but its key is already taken.
    if (key in bindingsCache) {
      return null
    }
    // Candidates are indexed by raw class, so a key without a class can't match one.
    val classId = key.classId ?: return null
    val candidates = genericBindingCandidatesFor(classId, key.qualifier)
    if (candidates.isEmpty()) {
      return null
    }
    // Keep the candidates whose return type matches the key and whose bounds hold.
    val matches = candidates.mapNotNull { candidate ->
      val typeArguments = candidate.inferTypeArguments(key) ?: return@mapNotNull null
      DerivedBinding(key, candidate, typeArguments)
    }
    val selection =
      selectMostSpecificCandidate(
        candidates = matches,
        patternOf = { it.candidate.patternIdentity },
        isVariableOf = { match, typeParameter -> match.candidate.isVariable(typeParameter) },
      )
    val selected =
      when (selection) {
        // Nothing matched, so the class lookup runs next.
        DerivedCandidateSelection.None -> return null
        is DerivedCandidateSelection.Single -> selection.candidate
        is DerivedCandidateSelection.MostSpecific -> {
          // Record the overlap for the report that `derivedBindingOverlapSeverity` turns on.
          overlappingDerivedBindings[key] =
            DerivedBindingConflict(selection.winner.candidate, matches.map { it.candidate })
          selection.winner
        }
        is DerivedCandidateSelection.Ambiguous -> {
          // Use the first match so the graph reports the ambiguity without a missing binding too.
          val first = selection.candidates.first()
          ambiguousDerivedBindings[key] =
            DerivedBindingConflict(first.candidate, matches.map { it.candidate })
          first
        }
      }

    // Apply the inferred type arguments to get an ordinary binding with a concrete key.
    val binding = selected.candidate.bindingFor(selected.typeArguments)
    if (binding.typeKey != key) {
      reportCompilerBug(
        "Derived binding from ${selected.candidate.function.name} has key ${binding.typeKey} " +
          "but was requested as $key",
      )
    }

    val conflicts =
      captureDerivedCandidateDecisions(contextKey, stack, selection, selected, binding)

    // An inherited scoped binding lives in the ancestor that owns its scope.
    val scope = binding.scope
    val isInheritedScoped = scope != null && !selected.candidate.isLocallyDeclared
    if (isInheritedScoped) {
      val parentBinding = parentGraphDependencyForScope(key, scope)
      if (parentBinding != null) {
        decisionCapture?.selected(
          contextKey,
          stack,
          binding,
          conflicts = conflicts,
          ownerGraph = parentBinding.token?.ownerGraphKey,
          resolvedBinding = parentBinding,
        )
        return setOf(parentBinding)
      }
    }

    // Stop here if this derivation keeps growing its key or its chain is too deep.
    val rejection = checkDerivationChain(key, selected, stack)
    if (rejection != null) {
      divergentDerivedBindings[key] = rejection
      decisionCapture?.missing(contextKey, stack)
      return emptySet()
    }

    // Register the binding under its key so later lookups find it as a registered binding.
    putBinding(binding, isLocallyDeclared = selected.candidate.isLocallyDeclared)
    derivedBindings[key] = selected
    decisionCapture?.selected(contextKey, stack, binding, conflicts)
    return setOf(binding)
  }

  /**
   * Records the other generic candidates that matched [contextKey] in the explanation report. When
   * one candidate was most specific, each other match is rejected as less specific. When none was,
   * every match is returned as a conflict, like duplicate exact bindings. Returns null when there
   * are no conflicts or nothing captures decisions.
   */
  context(context: IrMetroContext)
  private fun captureDerivedCandidateDecisions(
    contextKey: IrContextualTypeKey,
    stack: IrBindingStack,
    selection: DerivedCandidateSelection<DerivedBinding>,
    selected: DerivedBinding,
    binding: IrBinding,
  ): List<IrBinding>? {
    val capture = decisionCapture ?: return null
    return when (selection) {
      // With one match or none, there's no other candidate to record.
      DerivedCandidateSelection.None,
      is DerivedCandidateSelection.Single -> null
      is DerivedCandidateSelection.MostSpecific -> {
        // Each other match lost to the winner.
        for (match in selection.others) {
          capture.rejected(
            request = contextKey,
            stack = stack,
            key = contextKey.typeKey,
            declaration = match.candidate.function,
            reason = BindingReason.LESS_SPECIFIC,
            relatedDeclarations = listOf(selected.candidate.function),
          )
        }
        null
      }
      is DerivedCandidateSelection.Ambiguous -> {
        // Build each other match's binding so the report can list them all as conflicts.
        selection.candidates.map { match ->
          if (match === selected) {
            binding
          } else {
            match.candidate.bindingFor(match.typeArguments)
          }
        }
      }
    }
  }

  /**
   * Walks the requester links up from [key] and returns why deriving [key] through [selected] must
   * stop, or null if it can go ahead.
   *
   * It diverges when an earlier key on the chain came from the same generic function and
   * [isDivergentDerivation] says [key] grew from it. It passes the depth limit when the chain would
   * hold more than [MAX_DERIVED_BINDING_DEPTH] derived bindings.
   */
  private fun checkDerivationChain(
    key: IrTypeKey,
    selected: DerivedBinding,
    stack: IrBindingStack,
  ): DivergentDerivedBinding? {
    // Derived bindings on the chain, nearest requester first.
    val derivedOnChain = mutableListOf<DerivedBinding>()
    val visited = mutableSetOf(key)
    // Start at this key's requester and follow the recorded links up to a root.
    var requester = stack.requestingKeyFor(key)
    while (requester != null) {
      if (!visited.add(requester)) {
        reportCompilerBug("Requester links loop at $requester while deriving $key")
      }
      derivedBindings[requester]?.let(derivedOnChain::add)
      requester = requesterKeys[requester]
    }

    // Only keys the same function derived can show that it keeps growing its key.
    val function = selected.candidate.function
    val earlierFromSameFunction =
      derivedOnChain.filter { it.candidate.function == function }.asReversed()
    val isDivergent =
      isDivergentDerivation(
        newKey = key.identity.type,
        earlierKeysFromSameProvider = earlierFromSameFunction.map { it.key.identity.type },
      )
    if (isDivergent) {
      return DivergentDerivedBinding(
        key = key,
        candidate = selected.candidate,
        chain = earlierFromSameFunction.map { it.key } + key,
        reason = DivergentDerivedBinding.Reason.GROWING_KEY,
      )
    }

    // The new binding counts toward the chain's depth too.
    val depth = derivedOnChain.size + 1
    if (depth > MAX_DERIVED_BINDING_DEPTH) {
      return DivergentDerivedBinding(
        key = key,
        candidate = selected.candidate,
        chain = derivedOnChain.asReversed().map { it.key } + key,
        reason = DivergentDerivedBinding.Reason.DEPTH_LIMIT,
      )
    }
    return null
  }

  internal fun createExplicitConstructorInjectedBinding(
    bindsCallable: BindsCallable,
  ): IrBinding.ConstructorInjected =
    context(metroContext) {
      val key = bindsCallable.typeKey
      val irClass = key.type.rawType()
      val classFactory =
        findClassFactory(irClass)
          ?: reportCompilerBug(
            "Parameter-less @Binds target ${key.render(short = false)} was not validated before binding graph generation.",
          )

      trackFunctionCall(sourceGraph, classFactory.function)
      trackClassLookup(sourceGraph, classFactory.factoryClass)

      val remapper = irClass.cachedDeepRemapperFor(key.type)
      val injectedMembers =
        irClass.computeMembersInjectorBindings(remapper).mapToSet { binding ->
          binding.contextualTypeKey
        }

      IrBinding.ConstructorInjected(
        type = irClass,
        classFactory = classFactory.remapTypes(remapper),
        annotations = irClass.metroAnnotations(metroContext.metroSymbols.classIds),
        typeKey = key,
        injectedMembers = injectedMembers,
        explicitBinding = bindsCallable,
      )
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
    val remapper = targetClass.cachedDeepRemapperFor(targetType)
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
    val cacheKey = ClassBindingKey(contextKey, contextKey.hasDefault)
    return classBindingsCache.getOrPut(cacheKey) {
      val key = contextKey.typeKey
      val irClass = key.type.rawType()

      // A `MembersInjector<T>` request resolves through the member injectors of `T`.
      if (irClass.classId == context.metroSymbols.metroMembersInjector.owner.classId) {
        return hydrateMemberInjectionAncestors(key)
      }

      val classAnnotations = irClass.metroAnnotations(context.metroSymbols.classIds)

      // A class only satisfies a qualified key when it carries that same qualifier.
      val requestQualifier = key.qualifier
      val isQualifiedRequest = requestQualifier != null
      val qualifierMatches = requestQualifier == classAnnotations.qualifier
      if (isQualifiedRequest && !qualifierMatches) {
        if (contextKey.hasDefault) {
          return@getOrPut setOf(IrBinding.Absent(key))
        }
        return@getOrPut emptySet()
      }

      if (irClass.isObject) {
        irClass.declarationMirrorFunctionOrNull()?.let {
          // We don't actually call this function but it stores information about qualifier/scope
          // annotations, so reference it here so IC triggers
          trackFunctionCall(sourceGraph, it)
        }
        return setOf(IrBinding.ObjectClass(irClass, classAnnotations, key))
      }

      val bindings = mutableSetOf<IrBinding>()
      val remapper by memoize { irClass.cachedDeepRemapperFor(key.type) }

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
          val diagnostic =
            MetroDiagnostic(
              id = MetroDiagnosticId.GENERIC,
              severity = MetroSeverity.ERROR,
              title =
                buildText {
                  append("Class factory for type ")
                  append(key.toText())
                  append(" has type parameters but no type arguments provided at calling site")
                },
              sections = listOfNotNull(stack.toTraceSection()),
            )
          context.reportCompat(
            irClass,
            MetroDiagnostics.METRO_ERROR,
            context.render(diagnostic).padForConsole(),
          )
          return@getOrPut emptySet()
        }

        // The cache is keyed by class, so it only holds bindings with no type substitution.
        val binding =
          bindingLookupCache.getOrPutConstructorInjected(
            irClass.takeIf { remapper == NOOP_TYPE_REMAPPER },
          ) {
            IrBinding.ConstructorInjected(
              type = irClass,
              classFactory = mappedFactory,
              annotations = classAnnotations,
              typeKey = key,
              injectedMembers =
                membersInjectBindings.value.mapToSet { binding -> binding.contextualTypeKey },
            )
          }

        bindings += binding

        // Record a lookup of the class in case its kind changes
        trackClassLookup(sourceGraph, classFactory.factoryClass)
        // Record a lookup of the signature in case its signature changes
        // Doesn't appear to be necessary but juuuuust in case
        trackFunctionCall(sourceGraph, classFactory.function)
      } else if (classAnnotations.isAssistedFactory) {
        val function = irClass.singleAbstractFunction().asMemberOf(key.type)

        // Get the target assisted-inject class from the function's return type
        val targetType = function.returnType
        val targetClass = targetType.rawType()
        val targetClassFactory =
          findClassFactory(targetClass)
            ?: run {
              val message =
                "AssistedFactory ${irClass.classId} targets ${targetClass.classId} but no " +
                  "@AssistedInject constructor was found"
              context.reportCompat(irClass, MetroDiagnostics.METRO_ERROR, message)
              return@getOrPut emptySet()
            }

        // Record lookups of the target's generated factory class and function in case its
        // constructor params change.
        trackClassLookup(sourceGraph, targetClassFactory.factoryClass)
        trackFunctionCall(sourceGraph, targetClassFactory.function)

        val targetKey = IrTypeKey(targetType)
        val targetAnnotations = targetClass.metroAnnotations(context.metroSymbols.classIds)
        val targetRemapper = targetClass.cachedDeepRemapperFor(targetType)

        // Create the target's ConstructorInjected binding (NOT added to graph). Its @Inject members
        // are tracked so the assisted factory depends on them: the generated factory injects them
        // after construction, and graph validation can reject suspend member deps (which cannot be
        // awaited during member injection).
        val targetBinding =
          bindingLookupCache.getOrPutConstructorInjected(
            targetClass.takeIf { targetRemapper == NOOP_TYPE_REMAPPER },
          ) {
            IrBinding.ConstructorInjected(
              type = targetClass,
              classFactory = targetClassFactory.remapTypes(targetRemapper),
              annotations = targetAnnotations,
              typeKey = targetKey,
              injectedMembers =
                targetClass.computeMembersInjectorBindings(targetRemapper).mapToSet {
                  it.contextualTypeKey
                },
            )
          }

        // Wrap target's dependencies in Provider for proper cycle detection
        val wrappedDependencies = targetBinding.dependencies.map { dep -> dep.wrapInProvider() }

        bindings +=
          bindingLookupCache.getOrPutAssistedFactory(
            irClass.takeIf { remapper == NOOP_TYPE_REMAPPER },
          ) {
            IrBinding.AssistedFactory(
              type = irClass,
              targetBinding = targetBinding,
              function = function,
              annotations = classAnnotations,
              typeKey = key,
              parameters = function.parameters(),
              dependencies = wrappedDependencies,
            )
          }
      } else if (contextKey.hasDefault) {
        // The requesting parameter has a default value, so it can go without a binding.
        bindings += IrBinding.Absent(key)
      } else {
        // A regular class that's neither injected nor assisted still gets its member injections
        // initialized, just in case.
        @Suppress("RETURN_VALUE_NOT_USED") membersInjectBindings.value
      }
      bindings
    }
  }
}

/**
 * Reuses [IrBinding.ConstructorInjected] and [IrBinding.AssistedFactory] bindings by [IrClass].
 * Lookups stay on the main compiler thread. Class bindings live for the compilation. Inherited
 * graph data lives until its root finishes processing.
 */
internal class BindingLookupCache {
  private val constructorInjectedBindings =
    MutableScatterMap<IrClass, IrBinding.ConstructorInjected>()
  private val assistedFactoryBindings = MutableScatterMap<IrClass, IrBinding.AssistedFactory>()
  private val rawInheritedGraphData = MutableScatterMap<IrClass, Any>()

  /** Releases inherited data after the current root and its extensions finish processing. */
  fun clearGraphTree() {
    rawInheritedGraphData.clear()
  }

  /** Returns a cached binding or computes and caches it. If [irClass] is null, just computes. */
  fun getOrPutConstructorInjected(
    irClass: IrClass?,
    compute: () -> IrBinding.ConstructorInjected,
  ): IrBinding.ConstructorInjected =
    if (irClass != null) {
      constructorInjectedBindings.getOrPut(irClass, compute)
    } else {
      compute()
    }

  /** Returns a cached binding or computes and caches it. If [irClass] is null, just computes. */
  fun getOrPutAssistedFactory(
    irClass: IrClass?,
    compute: () -> IrBinding.AssistedFactory,
  ): IrBinding.AssistedFactory =
    if (irClass != null) {
      assistedFactoryBindings.getOrPut(irClass, compute)
    } else {
      compute()
    }

  /**
   * Cached unfiltered parent-aggregated graph data for child graphs sharing a parent. The opaque
   * `Any` value is materialised by [BindingGraphGenerator] to avoid leaking that internal type.
   */
  fun <T : Any> getOrPutRawInheritedGraphData(parentSourceGraph: IrClass, compute: () -> T): T {
    @Suppress("UNCHECKED_CAST")
    return rawInheritedGraphData.getOrPut(parentSourceGraph, compute) as T
  }
}

/**
 * How the derived stage resolved [key]. [typeArguments] were inferred for [candidate]'s function
 * type parameters, in declaration order.
 */
internal class DerivedBinding(
  val key: IrTypeKey,
  val candidate: GenericBindingCandidate,
  val typeArguments: List<IrType>,
)

/**
 * A key the derived stage rejected. [candidate] is the generic candidate it selected for [key].
 * [chain] ends with [key]. For [Reason.GROWING_KEY] it lists the keys [candidate] derived on the
 * chain, oldest first. For [Reason.DEPTH_LIMIT] it lists every derived key on the chain, oldest
 * first.
 */
internal class DivergentDerivedBinding(
  val key: IrTypeKey,
  val candidate: GenericBindingCandidate,
  val chain: List<IrTypeKey>,
  val reason: Reason,
) {
  enum class Reason {
    /** The same generic function kept requesting a larger key. */
    GROWING_KEY,
    /** The chain would hold more than [MAX_DERIVED_BINDING_DEPTH] derived bindings. */
    DEPTH_LIMIT,
  }
}

/**
 * Several generic candidates matched one key. [selected] is the one the graph uses. [candidates]
 * are all the matches, including [selected], in registration order.
 */
internal class DerivedBindingConflict(
  val selected: GenericBindingCandidate,
  val candidates: List<GenericBindingCandidate>,
)
