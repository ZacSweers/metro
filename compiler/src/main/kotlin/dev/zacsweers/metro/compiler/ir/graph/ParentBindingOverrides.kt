// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.compiler.graph.LocationDiagnostic
import dev.zacsweers.metro.compiler.graph.parentBindingOverrideDiagnostic
import dev.zacsweers.metro.compiler.ir.BindsCallable
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.ProviderFactory
import dev.zacsweers.metro.compiler.ir.findInjectableConstructor
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.renderForDiagnostic
import dev.zacsweers.metro.compiler.ir.renderLocationDiagnostic
import dev.zacsweers.metro.compiler.ir.renderSourceLocation
import dev.zacsweers.metro.compiler.ir.toDiagnosticSpan
import dev.zacsweers.metro.compiler.ir.toUnknownLocationContext
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isObject

/**
 * One explicit declaration of a regular binding in a graph. Parent binding override detection
 * compares a child's declarations with an ancestor's through this type.
 */
internal sealed interface GraphBindingDeclaration {
  /** The declaration that diagnostics point at. It may have no source, like an external stub. */
  val reportTarget: IrDeclaration

  /** True for a generic provider. Those stay under `derivedBindingOverlapSeverity`. */
  val isGeneric: Boolean

  /**
   * Returns true when both refer to the same source declaration. That's the case when one binding
   * container is reachable from both graph levels.
   */
  fun isSameDeclarationAs(other: GraphBindingDeclaration): Boolean

  context(context: IrMetroContext)
  fun renderLocation(): LocationDiagnostic

  /** A `@Provides` declaration. */
  class Provider(val factory: ProviderFactory) : GraphBindingDeclaration {
    override val reportTarget: IrDeclaration
      get() = factory.realDeclaration as? IrDeclarationWithName ?: factory.function

    override val isGeneric: Boolean
      get() = factory is ProviderFactory.Metro && factory.isGeneric

    override fun isSameDeclarationAs(other: GraphBindingDeclaration): Boolean {
      if (other !is Provider) {
        return false
      }

      return factory == other.factory || factory.callableId == other.factory.callableId
    }

    context(context: IrMetroContext)
    override fun renderLocation(): LocationDiagnostic {
      return factory.providedBinding.renderLocationDiagnostic(underlineTypeKey = false)
    }
  }

  /** A `@Binds` declaration, including the ones generated for contributed classes. */
  class Binds(val callable: BindsCallable) : GraphBindingDeclaration {
    override val reportTarget: IrDeclaration
      get() = callable.resolveSourceDeclaration().first

    override val isGeneric: Boolean
      get() = callable.isGeneric

    override fun isSameDeclarationAs(other: GraphBindingDeclaration): Boolean {
      if (other !is Binds) {
        return false
      }

      return callable == other.callable || callable.callableId == other.callable.callableId
    }

    context(context: IrMetroContext)
    override fun renderLocation(): LocationDiagnostic {
      val shortLocation = MetroOptions.SystemProperties.SHORTEN_LOCATIONS
      if (callable.source == null) {
        return callable.renderLocationDiagnostic(
          short = false,
          shortLocation = shortLocation,
          parameters = Parameters.empty(),
        )
      }

      return callable.aliasBinding.renderLocationDiagnostic(underlineTypeKey = false)
    }
  }

  /**
   * A `@Provides` graph factory parameter. [declaration] is the user's parameter. For a graph
   * extension it comes from the original factory, since that's the one with a source location.
   */
  class CreatorParameter(val parameter: Parameter, val declaration: IrDeclarationWithName) :
    GraphBindingDeclaration {
    override val reportTarget: IrDeclaration
      get() = declaration

    override val isGeneric: Boolean
      get() = false

    override fun isSameDeclarationAs(other: GraphBindingDeclaration): Boolean {
      return other is CreatorParameter && declaration == other.declaration
    }

    context(context: IrMetroContext)
    override fun renderLocation(): LocationDiagnostic {
      return IrBinding.BoundInstance(
          parameter = parameter,
          reportableLocation = declaration,
          isGraphInput = true,
        )
        .renderLocationDiagnostic(underlineTypeKey = false)
    }
  }

  /** An accessor of a graph that the declaring graph gets through `@Includes`. */
  class IncludedAccessor(val accessor: GraphAccessor) : GraphBindingDeclaration {
    override val reportTarget: IrDeclaration
      get() = accessor.metroFunction.ir

    override val isGeneric: Boolean
      get() = false

    override fun isSameDeclarationAs(other: GraphBindingDeclaration): Boolean {
      if (other !is IncludedAccessor) {
        return false
      }

      return accessor.metroFunction.callableId == other.accessor.metroFunction.callableId
    }

    context(context: IrMetroContext)
    override fun renderLocation(): LocationDiagnostic {
      return accessor.metroFunction.ir.renderLocationDiagnostic()
    }
  }

  /**
   * A scoped `@Inject` class. No graph declares it. The ancestor that owns its scope keeps the one
   * instance, so it's inherited like that ancestor's scoped `@Provides`.
   */
  class ScopedInjectedClass(
    val irClass: IrClass,
    val annotations: MetroAnnotations<IrAnnotation>,
  ) : GraphBindingDeclaration {
    override val reportTarget: IrDeclaration
      get() = irClass

    override val isGeneric: Boolean
      get() = false

    override fun isSameDeclarationAs(other: GraphBindingDeclaration): Boolean {
      return other is ScopedInjectedClass && irClass == other.irClass
    }

    context(context: IrMetroContext)
    override fun renderLocation(): LocationDiagnostic {
      val shortLocation = MetroOptions.SystemProperties.SHORTEN_LOCATIONS
      val location = irClass.renderSourceLocation(short = shortLocation)
      val unknownLocationContext =
        if (location == null) {
          irClass.toUnknownLocationContext()
        } else {
          null
        }

      val description = buildString {
        irClass.renderForDiagnostic(
          short = false,
          annotations = annotations,
          underlineTypeKey = false,
        )
      }

      return LocationDiagnostic(
        location = location ?: LocationDiagnostic.NO_SOURCE_LOCATION,
        description = description,
        span = irClass.toDiagnosticSpan(shortDisplayPath = shortLocation),
        locationContext = unknownLocationContext?.description,
        notes = unknownLocationContext?.notes.orEmpty(),
      )
    }
  }
}

/** An inherited declaration that a child graph dropped because the child declares the same key. */
internal data class InheritedBindingOverride(
  val typeKey: IrTypeKey,
  /** The key of the ancestor graph that declares [declaration]. */
  val ownerKey: IrTypeKey,
  val declaration: GraphBindingDeclaration,
)

/**
 * A child graph binding that replaces a binding the child inherits from [owner].
 *
 * [declarations] are the child's winning declarations for [typeKey]. [inheritedDeclarations] are
 * [owner]'s declarations for it. [owner] is the nearest ancestor that declares the key. For a
 * scoped `@Inject` class, it's the nearest ancestor that owns the class's scope.
 */
internal class ParentBindingOverride(
  val typeKey: IrTypeKey,
  val owner: GraphNode,
  val declarations: List<GraphBindingDeclaration>,
  val inheritedDeclarations: List<GraphBindingDeclaration>,
) {
  /** Builds the diagnostic for this override in [node] at [severity]. */
  context(context: IrMetroContext)
  fun toDiagnostic(node: GraphNode, severity: MetroSeverity): MetroDiagnostic {
    val locations = declarations.map { it.renderLocation() }
    val inheritedLocations = inheritedDeclarations.map { it.renderLocation() }
    val notes = (locations + inheritedLocations).flatMap { it.notes }.distinct()
    val inheritsInjectedClass = inheritedDeclarations.any {
      it is GraphBindingDeclaration.ScopedInjectedClass
    }

    return parentBindingOverrideDiagnostic(
      typeKey = typeKey,
      severity = severity,
      graphName = node.originalTypeKey.render(short = true),
      ancestorGraphName = owner.originalTypeKey.render(short = true),
      locations = locations.map { it.toLocatedItem() },
      inheritedLocations = inheritedLocations.map { it.toLocatedItem() },
      extraNotes = notes,
      ancestorBindingCanBePrivate = !inheritsInjectedClass,
    )
  }
}

/**
 * Finds the bindings in [node] that override bindings from its ancestor graphs.
 *
 * Callers collect candidate keys from both inheritance paths in [BindingGraphGenerator]. This class
 * resolves each key's nearest declaring ancestor and applies the exclusions. It also finds the
 * scoped `@Inject` classes that [node]'s declarations replace. It finds nothing when the
 * `parentBindingOverrideSeverity` option is `NONE`. [lowerPriorityDeclarations] are local
 * declarations that lost to a higher priority contribution. They never win, so the report leaves
 * them out.
 */
internal class ParentBindingOverrideResolver(
  private val context: IrMetroContext,
  private val node: GraphNode.Local,
  private val lowerPriorityDeclarations: Set<Any>,
) {
  // IR runs in CLI-only contexts, so IDE-only severities resolve to NONE here.
  private val severity = context.options.parentBindingOverrideSeverity.resolve(isIde = false)

  /**
   * Ancestors nearest first. [GraphNode.allParentGraphs] keeps insertion order from the parent up.
   */
  private val ancestors: List<GraphNode> = node.allParentGraphs.values.toList()

  /**
   * Resolves overrides from [droppedInheritedBindings] (inherited declarations the child dropped)
   * and [parentContextKeys] (parent context keys that the child also declares). Returns them sorted
   * by key. Keys that aren't overrides are left out.
   */
  fun resolve(
    droppedInheritedBindings: List<InheritedBindingOverride>,
    parentContextKeys: Collection<IrTypeKey>,
  ): List<ParentBindingOverride> {
    if (severity == MetroOptions.DiagnosticSeverity.NONE) {
      return emptyList()
    }

    val inheritedByKey = mergeInheritedDeclarations(droppedInheritedBindings, parentContextKeys)
    addScopedInjectedClasses(inheritedByKey)

    return inheritedByKey.entries
      .sortedBy { it.key }
      .mapNotNull { (key, inherited) -> resolveOverride(key, inherited) }
  }

  /**
   * Merges both inheritance paths by key. Each key keeps the declarations of its nearest owner, so
   * it's reported once.
   */
  private fun mergeInheritedDeclarations(
    droppedInheritedBindings: List<InheritedBindingOverride>,
    parentContextKeys: Collection<IrTypeKey>,
  ): MutableMap<IrTypeKey, InheritedDeclarations> {
    val result = mutableMapOf<IrTypeKey, InheritedDeclarations>()

    fun merge(key: IrTypeKey, candidate: InheritedDeclarations) {
      val existing = result[key]
      if (existing == null || candidate.ownerIndex < existing.ownerIndex) {
        result[key] = candidate
      } else if (candidate.ownerIndex == existing.ownerIndex) {
        val combined = (existing.declarations + candidate.declarations).distinctBy { it.identity() }
        result[key] = existing.copy(declarations = combined)
      }
    }

    for ((key, ownerKey, declaration) in droppedInheritedBindings) {
      val ownerIndex = ancestors.indexOfFirst { it.typeKey == ownerKey }
      if (ownerIndex == -1) {
        reportCompilerBug("No ancestor graph $ownerKey for inherited binding $key")
      }

      merge(key, InheritedDeclarations(ownerIndex, ancestors[ownerIndex], listOf(declaration)))
    }

    for (key in parentContextKeys) {
      val inherited = findNearestInheritedDeclarations(key) ?: continue
      merge(key, inherited)
    }

    return result
  }

  /**
   * Walks ancestors nearest first and returns the first one that explicitly declares [key] and
   * doesn't keep it private. Returns null when no ancestor declares it, like a type that an
   * ancestor only offers through an `@Inject` constructor. [findScopedInjectedClass] handles the
   * scoped ones. Also returns null when the declaring ancestor replaces the key with a dynamic
   * binding.
   */
  private fun findNearestInheritedDeclarations(key: IrTypeKey): InheritedDeclarations? {
    for ((index, ancestor) in ancestors.withIndex()) {
      if (key in ancestor.graphPrivateKeys) {
        continue
      }

      val declarations = explicitDeclarationsFor(ancestor, key)
      if (declarations.isEmpty()) {
        continue
      }

      val isDynamicInAncestor = ancestor is GraphNode.Local && key in ancestor.dynamicTypeKeys
      if (isDynamicInAncestor) {
        return null
      }

      return InheritedDeclarations(index, ancestor, declarations)
    }

    return null
  }

  /**
   * Adds the scoped `@Inject` classes that this graph's own declarations replace. A key that
   * already has an explicit ancestor declaration keeps it.
   */
  private fun addScopedInjectedClasses(
    inheritedByKey: MutableMap<IrTypeKey, InheritedDeclarations>,
  ) {
    for (key in node.directlyProvidedKeys) {
      // Multibinding contributions never override, so their classes aren't looked up.
      val isMultibindingKey = key.multibindingKeyData != null
      if (isMultibindingKey || key in inheritedByKey) {
        continue
      }

      val inherited = findScopedInjectedClass(key) ?: continue
      inheritedByKey[key] = inherited
    }
  }

  /**
   * Returns the scoped `@Inject` class behind [key] with the nearest ancestor that owns its scope.
   *
   * Returns null for an unscoped class, since every graph builds its own instances of it. Also
   * returns null when this graph owns the scope, or when a nearer ancestor declares [key] itself.
   */
  private fun findScopedInjectedClass(key: IrTypeKey): InheritedDeclarations? {
    val injectedClass = with(context) { scopedInjectedClassFor(key, node.sourceGraph) }
    val scope = injectedClass?.annotations?.scope
    if (injectedClass == null || scope == null) {
      return null
    }

    if (scope in node.scopes) {
      return null
    }

    // A parameter-less @Binds declares the class's own binding in this graph. It replaces nothing.
    val onlyRestatesClass = explicitDeclarationsFor(node, key).all { it.restatesInjectedClass() }
    if (onlyRestatesClass) {
      return null
    }

    for ((index, ancestor) in ancestors.withIndex()) {
      // An explicit declaration wins over the class in its own graph, so the class isn't inherited.
      val declaresKey = explicitDeclarationsFor(ancestor, key).isNotEmpty()
      if (declaresKey) {
        return null
      }

      if (scope in ancestor.scopes) {
        return InheritedDeclarations(index, ancestor, listOf(injectedClass))
      }
    }

    return null
  }

  private fun resolveOverride(
    key: IrTypeKey,
    inherited: InheritedDeclarations,
  ): ParentBindingOverride? {
    // Map and set contributions have their own override rules.
    if (key.multibindingKeyData != null) {
      return null
    }

    // A dynamic replacement wins over both levels.
    if (key in node.dynamicTypeKeys) {
      return null
    }

    val localDeclarations = explicitDeclarationsFor(node, key)
    if (localDeclarations.isEmpty()) {
      return null
    }

    // Generic providers stay under derivedBindingOverlapSeverity.
    val hasGenericDeclaration =
      localDeclarations.any { it.isGeneric } || inherited.declarations.any { it.isGeneric }
    if (hasGenericDeclaration) {
      return null
    }

    // The same declaration at both levels, like a shared binding container, isn't an override.
    val isSharedDeclaration = localDeclarations.any { local ->
      inherited.declarations.any { inheritedDeclaration ->
        local.isSameDeclarationAs(inheritedDeclaration)
      }
    }
    if (isSharedDeclaration) {
      return null
    }

    // Lower priority contributions never win. When several remain, the graph also reports a
    // duplicate binding.
    val remainingDeclarations = localDeclarations.filterNot {
      it.identity() in lowerPriorityDeclarations
    }
    val winningDeclarations =
      if (remainingDeclarations.isEmpty()) {
        localDeclarations
      } else {
        remainingDeclarations
      }

    return ParentBindingOverride(
      typeKey = key,
      owner = inherited.owner,
      declarations = winningDeclarations,
      inheritedDeclarations = inherited.declarations,
    )
  }

  /**
   * Returns [graph]'s own explicit declarations for [key]. These are provider factories, binds
   * callables, `@Provides` graph factory parameters, and accessors of included graphs.
   */
  private fun explicitDeclarationsFor(
    graph: GraphNode,
    key: IrTypeKey,
  ): List<GraphBindingDeclaration> {
    return buildList {
      graph.providerFactories[key]?.forEach { add(GraphBindingDeclaration.Provider(it)) }
      graph.bindsCallables[key]?.forEach { add(GraphBindingDeclaration.Binds(it)) }

      if (graph is GraphNode.Local) {
        addCreatorParameterDeclarations(graph, key)
      }

      // Only an ancestor's @Includes accessors count. The child's own are a known gap because
      // they aren't in directlyProvidedKeys.
      val isAncestor = graph !== node
      if (isAncestor) {
        for (includedNode in graph.includedGraphNodes.values) {
          for (accessor in includedNode.accessors) {
            if (accessor.contextKey.typeKey == key) {
              add(GraphBindingDeclaration.IncludedAccessor(accessor))
            }
          }
        }
      }
    }
  }

  private fun MutableList<GraphBindingDeclaration>.addCreatorParameterDeclarations(
    graph: GraphNode.Local,
    key: IrTypeKey,
  ) {
    val parameters = graph.creator?.parameters?.regularParameters.orEmpty()
    // Graph extensions keep the user's parameters on the original factory.
    val originalCreator = graph.originalCreator ?: graph.creator

    for (parameter in parameters) {
      val isProvidedParameter = parameter.isBindsInstance && parameter.typeKey == key
      if (!isProvidedParameter) {
        continue
      }

      val originalDeclaration = originalCreator?.parametersByTypeKey?.get(key)?.ir
      val declaration =
        if (originalDeclaration != null) {
          originalDeclaration
        } else {
          parameter.ir
        }
      if (declaration == null) {
        reportCompilerBug("No declaration for graph factory parameter $parameter")
      }

      add(GraphBindingDeclaration.CreatorParameter(parameter, declaration))
    }
  }

  /** True for a parameter-less `@Binds`, like `@Binds fun bindFoo(): Foo` for an `@Inject` Foo. */
  private fun GraphBindingDeclaration.restatesInjectedClass(): Boolean {
    return this is GraphBindingDeclaration.Binds && callable.source == null
  }

  /** The object that [lowerPriorityDeclarations] and dedup compare against. */
  private fun GraphBindingDeclaration.identity(): Any {
    return when (this) {
      is GraphBindingDeclaration.Provider -> factory
      is GraphBindingDeclaration.Binds -> callable
      is GraphBindingDeclaration.CreatorParameter -> declaration
      is GraphBindingDeclaration.IncludedAccessor -> accessor
      is GraphBindingDeclaration.ScopedInjectedClass -> irClass
    }
  }

  private data class InheritedDeclarations(
    val ownerIndex: Int,
    val owner: GraphNode,
    val declarations: List<GraphBindingDeclaration>,
  )
}

/**
 * Returns [key]'s class when it's a scoped `@Inject` class that provides the key itself. This
 * applies the same rules as [BindingLookup]'s class lookup, and it registers no binding.
 *
 * [callingGraph] is the graph whose diagnostics depend on the answer.
 */
context(context: IrMetroContext)
private fun scopedInjectedClassFor(
  key: IrTypeKey,
  callingGraph: IrClass,
): GraphBindingDeclaration.ScopedInjectedClass? {
  // An injected class never satisfies a nullable request for it.
  if (key.type.isNullable()) {
    return null
  }

  val irClass = key.type.rawTypeOrNull()
  if (irClass == null || irClass.isObject) {
    return null
  }

  // The answer depends on the class's annotations, so the graph recompiles when they change.
  val classId = irClass.classId
  if (classId != null) {
    trackClassLookup(callingGraph, classId)
  }

  val classIds = context.metroSymbols.classIds
  val annotations = irClass.metroAnnotations(classIds)
  if (annotations.scope == null) {
    return null
  }

  // A class only provides a qualified key when it carries that same qualifier.
  val requestQualifier = key.qualifier
  val hasOtherQualifier = requestQualifier != null && requestQualifier != annotations.qualifier
  if (hasOtherQualifier) {
    return null
  }

  val constructor = irClass.findInjectableConstructor(onlyUsePrimaryConstructor = false)
  if (constructor == null) {
    return null
  }

  // An assisted-inject class is only reachable through its factory.
  val isAssistedInject = constructor.isAnnotatedWithAny(classIds.assistedInjectAnnotations)
  val hasAssistedParameters =
    constructor.regularParameters.any { it.isAnnotatedWithAny(classIds.assistedAnnotations) }
  if (isAssistedInject || hasAssistedParameters) {
    return null
  }

  return GraphBindingDeclaration.ScopedInjectedClass(irClass, annotations)
}
