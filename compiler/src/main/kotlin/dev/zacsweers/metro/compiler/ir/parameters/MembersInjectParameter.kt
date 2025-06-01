// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.parameters

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.ir.IrBindingStack
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.asContextualTypeKey
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.isPropertyAccessor
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.remapTypeParameters
import org.jetbrains.kotlin.name.Name

@Poko
internal class MembersInjectParameter(
  override val kind: IrParameterKind,
  override val name: Name,
  override val contextualTypeKey: IrContextualTypeKey,
  @Poko.Skip override val bindingStackEntry: IrBindingStack.Entry,
  @Poko.Skip override val originalName: Name,
  @Poko.Skip override val ir: IrValueParameter,
) : Parameter {
  override val isAssisted: Boolean = false
  override val assistedIdentifier: String = ""
  override val assistedParameterKey: Parameter.AssistedParameterKey =
    Parameter.AssistedParameterKey(contextualTypeKey.typeKey, assistedIdentifier)
  override val isBindsInstance: Boolean = false
  override val isGraphInstance: Boolean = false
  override val isIncludes: Boolean = false
  override val isExtends: Boolean = false
  private val cachedToString by unsafeLazy {
    buildString {
      contextualTypeKey.typeKey.qualifier?.let {
        append(it)
        append(' ')
      }
      append(name)
      append(':')
      append(' ')
      append(contextualTypeKey.render(short = true, includeQualifier = false))
    }
  }

  override fun toString(): String = cachedToString
}

internal fun List<IrValueParameter>.mapToMemberInjectParameters(
  context: IrMetroContext,
  nameAllocator: NameAllocator,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): List<MembersInjectParameter> {
  return map { valueParameter ->
    valueParameter.toMemberInjectParameter(
      context = context,
      uniqueName = nameAllocator.newName(valueParameter.name.asString()).asName(),
      kind = IrParameterKind.Regular,
      typeParameterRemapper = typeParameterRemapper,
    )
  }
}

internal fun IrProperty.toMemberInjectParameter(
  context: IrMetroContext,
  uniqueName: Name,
  kind: IrParameterKind = IrParameterKind.Regular,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): MembersInjectParameter {
  val propertyType =
    getter?.returnType ?: backingField?.type ?: error("No getter or backing field!")

  val setterParam = setter?.regularParameters?.singleOrNull()

  // Remap type parameters in underlying types to the new target container. This is important for
  // type mangling
  val declaredType = typeParameterRemapper?.invoke(propertyType) ?: propertyType

  // TODO warn if it's anything other than null for now?
  // Check lateinit because they will report having a getter/body even though they're not actually
  // implemented for our needs
  val defaultValue =
    if (isLateinit) {
      null
    } else {
      getter?.body ?: backingField?.initializer
    }
  val contextKey =
    declaredType.asContextualTypeKey(
      context,
      with(context) { qualifierAnnotation() },
      defaultValue != null,
    )

  return MembersInjectParameter(
    kind = kind,
    name = uniqueName,
    originalName = name,
    contextualTypeKey = contextKey,
    bindingStackEntry = IrBindingStack.Entry.memberInjectedAt(contextKey, this),
    ir = setterParam!!,
  )
}

internal fun IrValueParameter.toMemberInjectParameter(
  context: IrMetroContext,
  uniqueName: Name,
  kind: IrParameterKind = IrParameterKind.Regular,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): MembersInjectParameter {
  // Remap type parameters in underlying types to the new target container. This is important for
  // type mangling
  val declaredType =
    typeParameterRemapper?.invoke(this@toMemberInjectParameter.type)
      ?: this@toMemberInjectParameter.type

  val contextKey =
    declaredType.asContextualTypeKey(
      context,
      with(context) { qualifierAnnotation() },
      defaultValue != null,
    )

  return MembersInjectParameter(
    kind = kind,
    name = uniqueName,
    originalName = name,
    contextualTypeKey = contextKey,
    bindingStackEntry =
      IrBindingStack.Entry.injectedAt(
        contextKey,
        this.parent as IrFunction,
        param = this,
        declaration = (this.parent as IrFunction).propertyIfAccessor,
      ),
    ir = this,
  )
}

context(context: IrMetroContext)
internal fun IrFunction.memberInjectParameters(
  nameAllocator: NameAllocator,
  parentClass: IrClass = parentClassOrNull!!,
  originClass: IrTypeParametersContainer? = null,
): Parameters<MembersInjectParameter> {
  val mapper =
    if (originClass != null) {
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

  val valueParams =
    if (isPropertyAccessor) {
      val property = propertyIfAccessor as IrProperty
      listOf(
        property.toMemberInjectParameter(
          context = context,
          uniqueName = nameAllocator.newName(property.name.asString()).asName(),
          kind = IrParameterKind.Regular,
          typeParameterRemapper = mapper,
        )
      )
    } else {
      regularParameters.mapToMemberInjectParameters(
        context = context,
        nameAllocator = nameAllocator,
        typeParameterRemapper = mapper,
      )
    }

  return Parameters(
    callableId = callableId,
    instance = null,
    regularParameters = valueParams,
    // TODO not supported for now
    extensionReceiver = null,
    contextParameters = emptyList(),
    ir = this,
  )
}