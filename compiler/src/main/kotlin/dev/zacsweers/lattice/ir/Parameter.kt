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
package dev.zacsweers.lattice.ir

import dev.zacsweers.lattice.LatticeSymbols
import dev.zacsweers.lattice.ir.Parameter.Kind
import dev.zacsweers.lattice.unsafeLazy
import kotlin.collections.count
import kotlin.collections.sumOf
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.remapTypeParameters
import org.jetbrains.kotlin.name.Name

internal sealed interface Parameter : Comparable<Parameter> {
  val kind: Kind
  val name: Name
  val originalName: Name
  val type: IrType
  val providerType: IrType
  val contextualTypeKey: ContextualTypeKey
  val lazyType: IrType
  val isWrappedInProvider: Boolean
  val isWrappedInLazy: Boolean
  val isLazyWrappedInProvider: Boolean
  val isAssisted: Boolean
  val assistedIdentifier: String
  val assistedParameterKey: AssistedParameterKey
  val symbols: LatticeSymbols
  val typeKey: TypeKey
  val isGraphInstance: Boolean
  val isBindsInstance: Boolean
  val hasDefault: Boolean
  val location: CompilerMessageSourceLocation?
  val ir: IrValueParameter

  val irFunction: IrFunction
    get() = ir.parent as IrFunction

  override fun compareTo(other: Parameter): Int = COMPARATOR.compare(this, other)

  // @Assisted parameters are equal, if the type and the identifier match. This subclass makes
  // diffing the parameters easier.
  data class AssistedParameterKey(val typeKey: TypeKey, val assistedIdentifier: String) {
    companion object {
      fun IrValueParameter.toAssistedParameterKey(
        symbols: LatticeSymbols,
        typeKey: TypeKey,
      ): AssistedParameterKey {
        return AssistedParameterKey(
          typeKey,
          annotationsIn(symbols.assistedAnnotations)
            .singleOrNull()
            ?.constArgumentOfTypeAt<String>(0)
            .orEmpty(),
        )
      }
    }
  }

  val originalType: IrType
    get() =
      when {
        isLazyWrappedInProvider -> lazyType.wrapInProvider(symbols.latticeProvider)
        isWrappedInProvider -> providerType
        isWrappedInLazy -> lazyType
        else -> type
      }

  enum class Kind {
    INSTANCE,
    EXTENSION_RECEIVER,
    VALUE,
    //    CONTEXT_PARAMETER, // Coming soon
  }

  companion object {
    private val COMPARATOR =
      compareBy<Parameter> { it.kind }
        .thenBy { it.name }
        .thenBy { it.originalName }
        .thenBy { it.typeKey }
        .thenBy { it.assistedIdentifier }
  }
}

/**
 * Returns a name which is unique when compared to the [Parameter.originalName] of the
 * [superParameters] argument.
 *
 * This is necessary for member-injected parameters, because a subclass may override a parameter
 * which is member-injected in the super. The `MembersInjector` corresponding to the subclass must
 * have unique constructor parameters for each declaration, so their names must be unique.
 *
 * This mimics Dagger's method of unique naming. If there are three parameters named "foo", the
 * unique parameter names will be [foo, foo2, foo3].
 */
internal fun Name.uniqueParameterName(vararg superParameters: List<Parameter>): Name {

  val numDuplicates = superParameters.sumOf { list -> list.count { it.originalName == this } }

  return if (numDuplicates == 0) {
    this
  } else {
    Name.identifier(asString() + (numDuplicates + 1))
  }
}

internal data class ConstructorParameter(
  override val kind: Kind,
  override val name: Name,
  override val contextualTypeKey: ContextualTypeKey,
  override val originalName: Name,
  override val providerType: IrType,
  override val lazyType: IrType,
  override val isAssisted: Boolean,
  override val assistedIdentifier: String,
  override val assistedParameterKey: Parameter.AssistedParameterKey =
    Parameter.AssistedParameterKey(contextualTypeKey.typeKey, assistedIdentifier),
  override val symbols: LatticeSymbols,
  override val isGraphInstance: Boolean,
  val bindingStackEntry: BindingStack.Entry,
  override val isBindsInstance: Boolean,
  override val hasDefault: Boolean,
  override val location: CompilerMessageSourceLocation?,
) : Parameter {
  override lateinit var ir: IrValueParameter
  override val typeKey: TypeKey = contextualTypeKey.typeKey
  override val type: IrType = contextualTypeKey.typeKey.type
  override val isWrappedInProvider: Boolean = contextualTypeKey.isWrappedInProvider
  override val isWrappedInLazy: Boolean = contextualTypeKey.isWrappedInLazy
  override val isLazyWrappedInProvider: Boolean = contextualTypeKey.isLazyWrappedInProvider
}

internal fun IrType.wrapInProvider(providerType: IrType): IrType {
  return wrapInProvider(providerType.classOrFail)
}

internal fun IrType.wrapInProvider(providerType: IrClassSymbol): IrType {
  return providerType.typeWith(this)
}

internal fun IrType.wrapInLazy(symbols: LatticeSymbols): IrType {
  return wrapIn(symbols.stdlibLazy)
}

private fun IrType.wrapIn(target: IrType): IrType {
  return wrapIn(target.classOrFail)
}

private fun IrType.wrapIn(target: IrClassSymbol): IrType {
  return target.typeWith(this)
}

internal sealed interface Parameters : Comparable<Parameters> {
  val instance: Parameter?
  val extensionReceiver: Parameter?
  val valueParameters: List<Parameter>
  val ir: IrFunction

  val nonInstanceParameters: List<Parameter>
  val allParameters: List<Parameter>

  fun with(ir: IrFunction): Parameters

  override fun compareTo(other: Parameters): Int = COMPARATOR.compare(this, other)

  companion object {
    val EMPTY: Parameters = ParametersImpl(null, null, emptyList())
    val COMPARATOR =
      compareBy<Parameters> { it.instance }
        .thenBy { it.extensionReceiver }
        .thenComparator { a, b -> compareValues(a, b) }

    operator fun invoke(
      instance: Parameter?,
      extensionReceiver: Parameter?,
      valueParameters: List<Parameter>,
      ir: IrFunction?,
    ): Parameters =
      ParametersImpl(instance, extensionReceiver, valueParameters).apply {
        ir?.let { this.ir = it }
      }
  }
}

private data class ParametersImpl(
  override val instance: Parameter?,
  override val extensionReceiver: Parameter?,
  override val valueParameters: List<Parameter>,
) : Parameters {
  override lateinit var ir: IrFunction

  override fun with(ir: IrFunction): Parameters {
    return copy().apply { this.ir = ir }
  }

  override val nonInstanceParameters: List<Parameter> by unsafeLazy {
    buildList {
      extensionReceiver?.let(::add)
      addAll(valueParameters)
    }
  }
  override val allParameters: List<Parameter> by unsafeLazy {
    buildList {
      instance?.let(::add)
      addAll(nonInstanceParameters)
    }
  }
}

internal fun IrFunction.parameters(
  context: LatticeTransformerContext,
  parentClass: IrClass? = parentClassOrNull,
  originClass: IrTypeParametersContainer? = null,
): Parameters {
  val mapper =
    if (this is IrConstructor && originClass != null && parentClass != null) {
      val typeParameters = parentClass.typeParameters
      val srcToDstParameterMap: Map<IrTypeParameter, IrTypeParameter> =
        originClass.typeParameters.zip(typeParameters).associate { (src, target) -> src to target }
      // Returning this inline breaks kotlinc for some reason
      val innerMapper: ((IrType) -> IrType) = { type ->
        type.remapTypeParameters(originClass, parentClass, srcToDstParameterMap)
      }
      innerMapper
    } else {
      null
    }

  return Parameters(
    instance =
      dispatchReceiverParameter?.toConstructorParameter(
        context,
        Kind.INSTANCE,
        typeParameterRemapper = mapper,
      ),
    extensionReceiver =
      extensionReceiverParameter?.toConstructorParameter(
        context,
        Kind.EXTENSION_RECEIVER,
        typeParameterRemapper = mapper,
      ),
    valueParameters = valueParameters.mapToConstructorParameters(context, mapper),
    ir = this,
  )
}

internal fun List<IrValueParameter>.mapToConstructorParameters(
  context: LatticeTransformerContext,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): List<ConstructorParameter> {
  return map { valueParameter ->
    valueParameter.toConstructorParameter(
      context,
      Kind.VALUE,
      valueParameter.name,
      typeParameterRemapper,
    )
  }
}

internal fun IrValueParameter.toConstructorParameter(
  context: LatticeTransformerContext,
  kind: Kind = Kind.VALUE,
  uniqueName: Name = this.name,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): ConstructorParameter {
  // Remap type parameters in underlying types to the new target container. This is important for
  // type mangling
  val declaredType =
    typeParameterRemapper?.invoke(this@toConstructorParameter.type)
      ?: this@toConstructorParameter.type

  val typeMetadata =
    declaredType.asContextualTypeKey(
      context,
      with(context) { qualifierAnnotation() },
      defaultValue != null,
    )

  val assistedAnnotation = annotationsIn(context.symbols.assistedAnnotations).singleOrNull()

  val isBindsInstance =
    annotationsIn(context.symbols.bindsInstanceAnnotations).singleOrNull() != null

  val assistedIdentifier = assistedAnnotation?.constArgumentOfTypeAt<String>(0).orEmpty()

  val ownerFunction = this.parent as IrFunction // TODO is this safe

  return ConstructorParameter(
      kind = kind,
      name = uniqueName,
      originalName = name,
      contextualTypeKey = typeMetadata,
      providerType = typeMetadata.typeKey.type.wrapInProvider(context.symbols.latticeProvider),
      lazyType = typeMetadata.typeKey.type.wrapInLazy(context.symbols),
      isAssisted = assistedAnnotation != null,
      assistedIdentifier = assistedIdentifier,
      symbols = context.symbols,
      isGraphInstance = false,
      bindingStackEntry = BindingStack.Entry.injectedAt(typeMetadata.typeKey, ownerFunction, this),
      isBindsInstance = isBindsInstance,
      hasDefault = defaultValue != null,
      location = locationOrNull(),
    )
    .apply { this.ir = this@toConstructorParameter }
}
