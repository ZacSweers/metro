// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

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
   * Infers this candidate's type arguments for [target], in [functionTypeParameters] order. Returns
   * null when [patternKey]'s type doesn't match [target]'s type or an inferred argument breaks its
   * type parameter's bounds. Callers match qualifiers through the index.
   */
  context(context: IrMetroContext)
  fun inferTypeArguments(target: IrTypeKey): List<IrType>? {
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
    val arguments = variableIdentities.map { identity ->
      val binding =
        variableBindings[identity]
          ?: reportCompilerBug(
            "Generic binding ${function.name} matched $target without binding ${identity.name}",
          )
      val argument = target.type.typeArgumentAt(binding.path, target)
      if (binding.stripsNullability) {
        argument.makeNotNull()
      } else {
        argument
      }
    }
    if (!boundsHold(arguments)) {
      return null
    }
    return arguments
  }

  /**
   * Checks each argument against its type parameter's upper bounds. Bounds can mention any of
   * [functionTypeParameters] and the container's type parameters, so both get substituted first.
   */
  context(context: IrMetroContext)
  private fun boundsHold(arguments: List<IrType>): Boolean {
    val functionRemapper =
      typeRemapperFor(
        functionTypeParameters.zip(arguments).associate { (typeParameter, argument) ->
          typeParameter.symbol to argument
        },
      )
    return functionTypeParameters.withIndex().all { (index, typeParameter) ->
      typeParameter.superTypes.all { bound ->
        val functionSubstitutedBound = functionRemapper.remapType(bound)
        val containerRemapper = containerTypeRemapper
        val substitutedBound =
          if (containerRemapper == null) {
            functionSubstitutedBound
          } else {
            containerRemapper.remapType(functionSubstitutedBound)
          }
        arguments[index].isSubtypeOf(substitutedBound, context.irTypeSystemContext)
      }
    }
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

  class Binds(val bindsCallable: BindsCallable, override val isLocallyDeclared: Boolean) :
    GenericBindingCandidate() {
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
