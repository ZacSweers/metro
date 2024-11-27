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
import dev.zacsweers.lattice.ir.annotationsIn
import dev.zacsweers.lattice.ir.constArgumentOfTypeAt
import dev.zacsweers.lattice.ir.rawTypeOrNull
import kotlin.collections.count
import kotlin.collections.sumOf
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.Name

internal sealed interface Parameter {
  val name: Name
  val originalName: Name
  val typeName: IrType
  val providerTypeName: IrType
  val lazyTypeName: IrType
  val isWrappedInProvider: Boolean
  val isWrappedInLazy: Boolean
  val isLazyWrappedInProvider: Boolean
  val isAssisted: Boolean
  val assistedIdentifier: Name
  val assistedParameterKey: AssistedParameterKey
  val symbols: LatticeSymbols
  val typeKey: TypeKey
  val isComponentInstance: Boolean

  // @Assisted parameters are equal, if the type and the identifier match. This subclass makes
  // diffing the parameters easier.
  data class AssistedParameterKey(
    private val typeName: IrType,
    private val assistedIdentifier: Name,
  )

  val originalTypeName: IrType
    get() =
      when {
        isLazyWrappedInProvider -> lazyTypeName.wrapInProvider(symbols.latticeProvider)
        isWrappedInProvider -> providerTypeName
        isWrappedInLazy -> lazyTypeName
        else -> typeName
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
  override val name: Name,
  override val typeKey: TypeKey,
  override val originalName: Name,
  override val typeName: IrType,
  override val providerTypeName: IrType,
  override val lazyTypeName: IrType,
  override val isWrappedInProvider: Boolean,
  override val isWrappedInLazy: Boolean,
  override val isLazyWrappedInProvider: Boolean,
  override val isAssisted: Boolean,
  override val assistedIdentifier: Name,
  override val assistedParameterKey: Parameter.AssistedParameterKey =
    Parameter.AssistedParameterKey(typeName, assistedIdentifier),
  override val symbols: LatticeSymbols,
  override val isComponentInstance: Boolean,
) : Parameter

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

internal fun List<IrValueParameter>.mapToConstructorParameters(
  context: LatticeTransformerContext,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): List<ConstructorParameter> {
  return map { valueParameter ->
    valueParameter.toConstructorParameter(context, valueParameter.name, typeParameterRemapper)
  }
}

internal fun IrValueParameter.toConstructorParameter(
  context: LatticeTransformerContext,
  uniqueName: Name = this.name,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): ConstructorParameter {
  // Remap type parameters in underlying types to the new target container. This is important for
  // type mangling
  val type = typeParameterRemapper?.invoke(type) ?: type
  check(type is IrSimpleType) { "Unrecognized parameter type '${type.javaClass}': ${render()}" }
  val rawTypeClass = type.rawTypeOrNull()
  val rawType = rawTypeClass?.classId

  val isWrappedInProvider = rawType in context.symbols.providerTypes
  val isWrappedInLazy = rawType in context.symbols.lazyTypes
  val isLazyWrappedInProvider =
    isWrappedInProvider &&
      type.arguments[0].typeOrFail.rawTypeOrNull()?.classId in context.symbols.lazyTypes

  val typeName =
    when {
      isLazyWrappedInProvider ->
        type.arguments.single().typeOrFail.expectAs<IrSimpleType>().arguments.single().typeOrFail
      isWrappedInProvider || isWrappedInLazy -> type.arguments.single().typeOrFail
      else -> type
    }

  // TODO FIR better error message
  val assistedAnnotation = annotationsIn(context.symbols.assistedAnnotations).singleOrNull()

  val assistedIdentifier =
    Name.identifier(assistedAnnotation?.constArgumentOfTypeAt<String>(0).orEmpty())

  return ConstructorParameter(
    name = uniqueName,
    typeKey = TypeKey.from(context, this, type = typeName),
    originalName = name,
    typeName = typeName,
    providerTypeName = typeName.wrapInProvider(context.symbols.latticeProvider),
    lazyTypeName = typeName.wrapInLazy(context.symbols),
    isWrappedInProvider = isWrappedInProvider,
    isWrappedInLazy = isWrappedInLazy,
    isLazyWrappedInProvider = isLazyWrappedInProvider,
    isAssisted = assistedAnnotation != null,
    assistedIdentifier = assistedIdentifier,
    symbols = context.symbols,
    isComponentInstance = false,
  )
}
