package dev.zacsweers.lattice.ir.parameters

import dev.zacsweers.lattice.LatticeSymbols
import dev.zacsweers.lattice.NameAllocator
import dev.zacsweers.lattice.asName
import dev.zacsweers.lattice.ir.BindingStack
import dev.zacsweers.lattice.ir.ContextualTypeKey
import dev.zacsweers.lattice.ir.LatticeTransformerContext
import dev.zacsweers.lattice.ir.TypeKey
import dev.zacsweers.lattice.ir.asContextualTypeKey
import dev.zacsweers.lattice.ir.locationOrNull
import dev.zacsweers.lattice.ir.parameters.Parameter.Kind
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.isPropertyAccessor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal data class MembersInjectParameter(
  override val kind: Kind,
  override val name: Name,
  override val contextualTypeKey: ContextualTypeKey,
  override val originalName: Name,
  override val providerType: IrType,
  override val lazyType: IrType,
  override val symbols: LatticeSymbols,
  override val hasDefault: Boolean,
  override val location: CompilerMessageSourceLocation?,
  val bindingStackEntry: BindingStack.Entry,
  val memberInjectorClassId: ClassId,
  val isProperty: Boolean,
) : Parameter {
  // TODO figure this out
  override lateinit var ir: IrValueParameter
  lateinit var setterFunction: IrFunction
  var irProperty: IrProperty? = null
  override val typeKey: TypeKey = contextualTypeKey.typeKey
  override val type: IrType = contextualTypeKey.typeKey.type
  override val isWrappedInProvider: Boolean = contextualTypeKey.isWrappedInProvider
  override val isWrappedInLazy: Boolean = contextualTypeKey.isWrappedInLazy
  override val isLazyWrappedInProvider: Boolean = contextualTypeKey.isLazyWrappedInProvider
  override val isAssisted: Boolean = false
  override val assistedIdentifier: String = ""
  override val assistedParameterKey: Parameter.AssistedParameterKey =
    Parameter.AssistedParameterKey(contextualTypeKey.typeKey, assistedIdentifier)
  override val isBindsInstance: Boolean = false
  override val isGraphInstance: Boolean = false
}

internal fun List<IrValueParameter>.mapToMemberInjectParameters(
  context: LatticeTransformerContext,
  nameAllocator: NameAllocator,
  declaringClass: ClassId,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): List<MembersInjectParameter> {
  return map { valueParameter ->
    valueParameter.toMemberInjectParameter(
      context = context,
      declaringClass = declaringClass,
      uniqueName = nameAllocator.newName(valueParameter.name.asString()).asName(),
      kind = Kind.VALUE,
      typeParameterRemapper = typeParameterRemapper,
    )
  }
}

internal fun IrProperty.toMemberInjectParameter(
  context: LatticeTransformerContext,
  declaringClass: ClassId,
  uniqueName: Name,
  kind: Kind = Kind.VALUE,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): MembersInjectParameter {
  val propertyType = getter?.returnType ?: backingField?.type ?: error("No getter or backing field!")
  // Remap type parameters in underlying types to the new target container. This is important for
  // type mangling
  val declaredType =
    typeParameterRemapper?.invoke(propertyType)
      ?: propertyType

  // TODO warn if it's anything other than null for now?
  val defaultValue = getter?.body ?: backingField?.initializer
  val contextKey =
    declaredType.asContextualTypeKey(
      context,
      with(context) { qualifierAnnotation() },
      defaultValue != null,
    )

  val memberInjectorClass = declaringClass
    .createNestedClassId(LatticeSymbols.Names.LatticeMembersInjector)

  return MembersInjectParameter(
    kind = kind,
    name = uniqueName,
    originalName = name,
    contextualTypeKey = contextKey,
    providerType = contextKey.typeKey.type.wrapInProvider(context.symbols.latticeProvider),
    lazyType = contextKey.typeKey.type.wrapInLazy(context.symbols),
    symbols = context.symbols,
    bindingStackEntry = BindingStack.Entry.injectedAt(contextKey, setter!!, null, this),
    hasDefault = defaultValue != null,
    location = locationOrNull(),
    memberInjectorClassId = memberInjectorClass,
    isProperty = isPropertyAccessor,
  )
    .apply {
      this.setterFunction = setter!!
      this.irProperty = this@toMemberInjectParameter
    }
}

internal fun IrValueParameter.toMemberInjectParameter(
  context: LatticeTransformerContext,
  declaringClass: ClassId,
  uniqueName: Name,
  kind: Kind = Kind.VALUE,
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


  val ownerFunction = this.parent as IrFunction // TODO is this safe
  val isPropertyAccessor = ownerFunction.isPropertyAccessor

  val memberInjectorClass = declaringClass
    .createNestedClassId(LatticeSymbols.Names.LatticeMembersInjector)

  return MembersInjectParameter(
    kind = kind,
    name = uniqueName,
    originalName = name,
    contextualTypeKey = contextKey,
    providerType = contextKey.typeKey.type.wrapInProvider(context.symbols.latticeProvider),
    lazyType = contextKey.typeKey.type.wrapInLazy(context.symbols),
    symbols = context.symbols,
    bindingStackEntry = BindingStack.Entry.injectedAt(contextKey, ownerFunction, this),
    hasDefault = defaultValue != null,
    location = locationOrNull(),
    memberInjectorClassId = memberInjectorClass,
    isProperty = isPropertyAccessor,
  )
    .apply {
      this.ir = this@toMemberInjectParameter
      this.setterFunction = ownerFunction
      this.irProperty = if (isPropertyAccessor) ownerFunction.propertyIfAccessor as IrProperty else null
    }
}