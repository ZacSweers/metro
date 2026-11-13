// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.graph.TypeVariableBinding
import dev.zacsweers.metro.compiler.graph.identity.Nullability
import dev.zacsweers.metro.compiler.graph.identity.TypeIdentity
import dev.zacsweers.metro.compiler.graph.matchTypePattern
import dev.zacsweers.metro.compiler.ir.BindsCallable
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.ProviderFactory
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.typeIdentity
import dev.zacsweers.metro.compiler.ir.typeRemapperFor
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isSubtypeOf
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.name.ClassId

/**
 * A generic `@Provides` or `@Binds` function that a graph collected. Its [patternKey] mentions the
 * function's own type parameters, so it isn't a real binding key. [BindingLookup] keeps these in a
 * separate index keyed by [rawClassId] and the pattern key's qualifier.
 */
internal sealed class GenericBindingCandidate {
  /** The key this candidate provides, with the function's type parameters left in place. */
  abstract val patternKey: IrTypeKey

  /** True if the graph declares this candidate itself. Inherited candidates are false. */
  abstract val isLocallyDeclared: Boolean

  /**
   * True if a dynamic graph got this candidate from a dynamic binding container. A dynamic
   * candidate replaces the other bindings for every key it matches.
   */
  abstract val isDynamic: Boolean

  /** The generic source function, used for diagnostics. */
  abstract val function: IrSimpleFunction

  /** The raw class of [patternKey]. FIR rejects bare type parameter return types. */
  val rawClassId: ClassId
    get() {
      val rawClass =
        patternKey.type.rawTypeOrNull()
          ?: reportCompilerBug("Generic binding $patternKey has no raw class")
      return rawClass.classIdOrFail
    }

  /** The function's own type parameters, which [patternKey] mentions. */
  abstract val functionTypeParameters: List<IrTypeParameter>

  /**
   * Substitutes container type parameters. It also applies to [functionTypeParameters]' bounds.
   * It's null when no container substitution happened.
   */
  abstract val containerTypeRemapper: TypeRemapper?

  /**
   * True if the container declares type parameters that nothing substituted. That happens when a
   * graph extends a generic interface that declares providers. Those keys keep mentioning the
   * container's type parameters, so ordinary providers there don't resolve either.
   */
  private val hasUnsubstitutedContainerTypeParameters: Boolean by memoize {
    val containerTypeParameters = function.parentClassOrNull?.typeParameters.orEmpty()
    containerTypeRemapper == null && containerTypeParameters.isNotEmpty()
  }

  /** The identity of [patternKey]'s type, which matching and specificity compare. */
  val patternIdentity: TypeIdentity by memoize { patternKey.identity.type }

  /**
   * The non-null identity of each of [functionTypeParameters], in the same order. Pattern
   * occurrences of a type parameter differ only in nullability, so lookups compare the non-null
   * form.
   */
  private val variableIdentities: List<TypeIdentity.TypeParameter> by memoize {
    functionTypeParameters.map { typeParameter ->
      val identity = typeParameter.defaultType.typeIdentity()
      identity as? TypeIdentity.TypeParameter
        ?: reportCompilerBug(
          "Type parameter ${typeParameter.name} of ${function.name} has identity $identity",
        )
    }
  }

  private val variableIdentitySet: Set<TypeIdentity.TypeParameter> by memoize {
    variableIdentities.toSet()
  }

  /** True if [typeParameter] is one of this candidate's own type parameters. */
  fun isVariable(typeParameter: TypeIdentity.TypeParameter): Boolean {
    return typeParameter.copy(nullability = Nullability.NotNull) in variableIdentitySet
  }

  /**
   * True if [other] provides the same keys as this candidate. Their qualifiers match and their
   * return types differ only in type parameter names. Type parameters in the same place also have
   * the same bounds.
   *
   * So `<T> Encoder<List<T>>` is alpha-equivalent to `<E> Encoder<List<E>>`. It isn't
   * alpha-equivalent to `<E : Number> Encoder<List<E>>`, which provides fewer keys.
   */
  fun isAlphaEquivalentTo(other: GenericBindingCandidate): Boolean {
    if (patternKey.qualifier != other.patternKey.qualifier) {
      return false
    }
    // Each return type has to match the other with only its own type parameters as variables.
    val otherVariables =
      matchTypePattern(
        pattern = other.patternIdentity,
        target = patternIdentity,
        isVariable = other::isVariable,
      )
    if (otherVariables == null) {
      return false
    }
    val thisCoversOther =
      matchTypePattern(
        pattern = patternIdentity,
        target = other.patternIdentity,
        isVariable = ::isVariable,
      ) != null
    if (!thisCoversOther) {
      return false
    }
    return hasSameBoundsAs(other, otherVariables)
  }

  /**
   * True if each of [other]'s type parameters has the same bounds as this candidate's type
   * parameter in the same place. [otherVariables] says where each of [other]'s type parameters sits
   * in this candidate's return type.
   */
  private fun hasSameBoundsAs(
    other: GenericBindingCandidate,
    otherVariables: Map<TypeIdentity.TypeParameter, TypeVariableBinding>,
  ): Boolean {
    // Find this candidate's type parameter that sits where each of the other's does.
    val counterparts =
      other.variableIdentities.map { identity ->
        val binding = otherVariables[identity] ?: return false
        val counterpartType = patternKey.type.typeArgumentAt(binding.path, patternKey)
        counterpartType.typeParameterOrNull() ?: return false
      }
    // Rename the other's type parameters to this candidate's so the bounds compare directly.
    val renamer =
      typeRemapperFor(
        other.functionTypeParameters.zip(counterparts).associate { (otherParameter, counterpart) ->
          otherParameter.symbol to counterpart.defaultType
        },
      )
    for ((index, otherParameter) in other.functionTypeParameters.withIndex()) {
      val otherBounds =
        otherParameter.superTypes.mapTo(mutableSetOf()) { bound ->
          other.substituteContainer(renamer.remapType(bound)).typeIdentity()
        }
      val bounds =
        counterparts[index].superTypes.mapTo(mutableSetOf()) { bound ->
          substituteContainer(bound).typeIdentity()
        }
      if (otherBounds != bounds) {
        return false
      }
    }
    return true
  }

  /** Substitutes the container's type parameters in [type] when the container has a remapper. */
  private fun substituteContainer(type: IrType): IrType {
    val containerRemapper = containerTypeRemapper ?: return type
    return containerRemapper.remapType(type)
  }

  private fun IrType.typeParameterOrNull(): IrTypeParameter? {
    val simpleType = this as? IrSimpleType ?: return null
    val classifier = simpleType.classifier as? IrTypeParameterSymbol ?: return null
    return classifier.owner
  }

  /**
   * Infers this candidate's type arguments for [target], in [functionTypeParameters] order. Returns
   * null when [patternKey]'s type doesn't match [target]'s type or an inferred argument breaks its
   * type parameter's bounds. Callers match qualifiers through the index.
   */
  context(context: IrMetroContext)
  fun inferTypeArguments(target: IrTypeKey): List<IrType>? {
    val arguments = matchTypeArguments(target) ?: return null
    if (firstUnsatisfiedBound(arguments) != null) {
      return null
    }
    return arguments
  }

  /**
   * Returns the bound that keeps this candidate from providing [target]. Returns null when
   * [patternKey]'s type doesn't match [target]'s type. It also returns null when the type matches
   * and every bound holds.
   */
  context(context: IrMetroContext)
  fun unsatisfiedBoundFor(target: IrTypeKey): UnsatisfiedBound? {
    val arguments = matchTypeArguments(target) ?: return null
    return firstUnsatisfiedBound(arguments)
  }

  /**
   * Reads this candidate's type arguments out of [target], in [functionTypeParameters] order. It
   * doesn't check bounds. Returns null when [patternKey]'s type doesn't match [target]'s type.
   */
  private fun matchTypeArguments(target: IrTypeKey): List<IrType>? {
    if (hasUnsubstitutedContainerTypeParameters) {
      return null
    }
    val variableBindings =
      matchTypePattern(
        pattern = patternIdentity,
        target = target.identity.type,
        isVariable = ::isVariable,
      )
    if (variableBindings == null) {
      return null
    }
    return variableIdentities.map { identity ->
      val binding =
        variableBindings[identity]
          ?: reportCompilerBug(
            "Generic binding ${function.name} matched $target without binding ${identity.name}",
          )
      // The matcher works on identities, so follow its path through the real type.
      val argument = target.type.typeArgumentAt(binding.path, target)
      if (binding.stripsNullability) {
        // The return type wrote `T?` here, so `T` is the non-null form.
        argument.makeNotNull()
      } else {
        argument
      }
    }
  }

  /**
   * An inferred type argument that breaks a bound. [bound] has the candidate's type arguments
   * substituted, so `B : A` with `A = Number` reads as `Number`.
   */
  class UnsatisfiedBound(
    val typeParameter: IrTypeParameter,
    val argument: IrType,
    val bound: IrType,
  )

  /**
   * Checks each argument against its type parameter's upper bounds and returns the first one that
   * fails. Returns null when every bound holds.
   */
  context(context: IrMetroContext)
  private fun firstUnsatisfiedBound(arguments: List<IrType>): UnsatisfiedBound? {
    // A bound can mention the function's type parameters, like `B : A` or `T : Comparable<T>`.
    val functionRemapper =
      typeRemapperFor(
        functionTypeParameters.zip(arguments).associate { (typeParameter, argument) ->
          typeParameter.symbol to argument
        },
      )
    for ((index, typeParameter) in functionTypeParameters.withIndex()) {
      // A type parameter's supertypes are its upper bounds.
      for (bound in typeParameter.superTypes) {
        // A bound can also mention the container's type parameters, which have their own remapper.
        val substitutedBound = substituteContainer(functionRemapper.remapType(bound))
        val argument = arguments[index]
        if (!argument.isSubtypeOf(substitutedBound, context.irTypeSystemContext)) {
          return UnsatisfiedBound(typeParameter, argument, substitutedBound)
        }
      }
    }
    return null
  }

  /** Follows [path] through type arguments. The matcher built [path] from this type's identity. */
  private fun IrType.typeArgumentAt(path: List<Int>, target: IrTypeKey): IrType {
    var current = this
    for (index in path) {
      val simpleType =
        current as? IrSimpleType
          ?: reportCompilerBug("Can't follow type argument path $path in $target at $current")
      val argument =
        simpleType.arguments.getOrNull(index) as? IrTypeProjection
          ?: reportCompilerBug("Can't follow type argument path $path in $target at $current")
      current = argument.type
    }
    return current
  }

  /**
   * Creates the binding for [arguments], in [functionTypeParameters] order. It's an ordinary
   * [IrBinding.Provided] or [IrBinding.Alias] whose key is concrete.
   */
  abstract fun bindingFor(arguments: List<IrType>): IrBinding

  /**
   * Checks that [patternKey] mentions every one of [functionTypeParameters]. FIR requires each to
   * appear in the return type. Signature carriers declare their own copies, so a missed remap shows
   * up here.
   */
  fun checkPatternKey() {
    val mentioned = mutableSetOf<IrTypeParameterSymbol>()
    patternKey.type.collectTypeParameters(mentioned)
    val missing = functionTypeParameters.filterNot { it.symbol in mentioned }
    if (missing.isNotEmpty()) {
      reportCompilerBug(
        "Generic binding ${function.name} has key $patternKey, which doesn't mention its type " +
          "parameters ${missing.joinToString { it.name.asString() }}",
      )
    }
  }

  private fun IrType.collectTypeParameters(into: MutableSet<IrTypeParameterSymbol>) {
    if (this !is IrSimpleType) {
      return
    }
    val classifier = classifier
    if (classifier is IrTypeParameterSymbol) {
      into += classifier
    }
    for (argument in arguments) {
      if (argument is IrTypeProjection) {
        argument.type.collectTypeParameters(into)
      }
    }
  }

  class Provider(
    val providerFactory: ProviderFactory.Metro,
    override val isLocallyDeclared: Boolean,
    override val isDynamic: Boolean,
  ) : GenericBindingCandidate() {
    override val patternKey: IrTypeKey
      get() = providerFactory.rawTypeKey

    override val function: IrSimpleFunction
      get() = providerFactory.function

    override val functionTypeParameters: List<IrTypeParameter>
      get() = providerFactory.functionTypeParameters

    override val containerTypeRemapper: TypeRemapper?
      get() = providerFactory.containerTypeRemapper

    override fun bindingFor(arguments: List<IrType>): IrBinding {
      return providerFactory.withFunctionTypeArguments(arguments).providedBinding
    }
  }

  class Binds(
    val bindsCallable: BindsCallable,
    override val isLocallyDeclared: Boolean,
    override val isDynamic: Boolean,
  ) : GenericBindingCandidate() {
    override val patternKey: IrTypeKey
      get() = bindsCallable.rawTarget

    override val function: IrSimpleFunction
      get() = bindsCallable.function

    override val functionTypeParameters: List<IrTypeParameter>
      get() = bindsCallable.functionTypeParameters

    override val containerTypeRemapper: TypeRemapper?
      get() = bindsCallable.containerTypeRemapper

    override fun bindingFor(arguments: List<IrType>): IrBinding {
      if (bindsCallable.source == null) {
        reportCompilerBug("Generic binds ${function.name} has no source parameter")
      }
      return bindsCallable.withFunctionTypeArguments(arguments).aliasBinding
    }
  }
}
