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
package dev.zacsweers.lattice.transformers

import dev.zacsweers.lattice.LatticeSymbols
import dev.zacsweers.lattice.expectAs
import dev.zacsweers.lattice.ir.IrAnnotation
import dev.zacsweers.lattice.ir.annotationsIn
import dev.zacsweers.lattice.ir.constArgumentOfTypeAt
import dev.zacsweers.lattice.ir.rawTypeOrNull
import dev.zacsweers.lattice.transformers.Parameter.Kind
import kotlin.collections.count
import kotlin.collections.sumOf
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.remapTypeParameters
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.Name

internal sealed interface Parameter {
  val kind: Kind
  val name: Name
  val originalName: Name
  val type: IrType
  val providerType: IrType
  val lazyType: IrType
  val isWrappedInProvider: Boolean
  val isWrappedInLazy: Boolean
  val isLazyWrappedInProvider: Boolean
  val isAssisted: Boolean
  val assistedIdentifier: String
  val assistedParameterKey: AssistedParameterKey
  val symbols: LatticeSymbols
  val typeKey: TypeKey
  val isComponentInstance: Boolean
  val isBindsInstance: Boolean

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
  val typeMetadata: TypeMetadata,
  override val originalName: Name,
  override val providerType: IrType,
  override val lazyType: IrType,
  override val isAssisted: Boolean,
  override val assistedIdentifier: String,
  override val assistedParameterKey: Parameter.AssistedParameterKey =
    Parameter.AssistedParameterKey(typeMetadata.typeKey, assistedIdentifier),
  override val symbols: LatticeSymbols,
  override val isComponentInstance: Boolean,
  val bindingStackEntry: BindingStackEntry,
  override val isBindsInstance: Boolean,
) : Parameter {
  override val typeKey: TypeKey = typeMetadata.typeKey
  override val type: IrType = typeMetadata.typeKey.type
  override val isWrappedInProvider: Boolean = typeMetadata.isWrappedInProvider
  override val isWrappedInLazy: Boolean = typeMetadata.isWrappedInLazy
  override val isLazyWrappedInProvider: Boolean = typeMetadata.isLazyWrappedInProvider
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

internal data class Parameters(
  val instance: Parameter?,
  val extensionReceiver: Parameter?,
  val valueParameters: List<Parameter>,
) {
  val nonInstanceParameters: List<Parameter> = buildList {
    extensionReceiver?.let(::add)
    addAll(valueParameters)
  }
  val allParameters: List<Parameter> = buildList {
    instance?.let(::add)
    addAll(nonInstanceParameters)
  }

  companion object {
    val EMPTY = Parameters(null, null, emptyList())
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

internal fun IrType.asTypeMetadata(
  context: LatticeTransformerContext,
  qualifierAnnotation: IrAnnotation?,
): TypeMetadata {
  check(this is IrSimpleType) { "Unrecognized IrType '${javaClass}': ${render()}" }

  val declaredType = this
  val rawTypeClass = declaredType.rawTypeOrNull()
  val rawType = rawTypeClass?.classId

  val isWrappedInProvider = rawType in context.symbols.providerTypes
  val isWrappedInLazy = rawType in context.symbols.lazyTypes
  val isLazyWrappedInProvider =
    isWrappedInProvider &&
      declaredType.arguments[0].typeOrFail.rawTypeOrNull()?.classId in context.symbols.lazyTypes

  val type =
    when {
      isLazyWrappedInProvider ->
        declaredType.arguments
          .single()
          .typeOrFail
          .expectAs<IrSimpleType>()
          .arguments
          .single()
          .typeOrFail
      isWrappedInProvider || isWrappedInLazy -> declaredType.arguments.single().typeOrFail
      else -> declaredType
    }
  val typeKey = TypeKey(type, qualifierAnnotation)
  return TypeMetadata(
    typeKey = typeKey,
    isWrappedInProvider = isWrappedInProvider,
    isWrappedInLazy = isWrappedInLazy,
    isLazyWrappedInProvider = isLazyWrappedInProvider,
  )
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
  val typeMetadata = declaredType.asTypeMetadata(context, with(context) { qualifierAnnotation() })

  val assistedAnnotation = annotationsIn(context.symbols.assistedAnnotations).singleOrNull()

  val isBindsInstance =
    annotationsIn(context.symbols.bindsInstanceAnnotations).singleOrNull() != null

  val assistedIdentifier = assistedAnnotation?.constArgumentOfTypeAt<String>(0).orEmpty()

  val ownerFunction = this.parent as IrFunction // TODO is this safe

  return ConstructorParameter(
    kind = kind,
    name = uniqueName,
    originalName = name,
    typeMetadata = typeMetadata,
    providerType = typeMetadata.typeKey.type.wrapInProvider(context.symbols.latticeProvider),
    lazyType = typeMetadata.typeKey.type.wrapInLazy(context.symbols),
    isAssisted = assistedAnnotation != null,
    assistedIdentifier = assistedIdentifier,
    symbols = context.symbols,
    isComponentInstance = false,
    bindingStackEntry = BindingStackEntry.injectedAt(typeMetadata.typeKey, ownerFunction, this),
    isBindsInstance = isBindsInstance,
  )
}
