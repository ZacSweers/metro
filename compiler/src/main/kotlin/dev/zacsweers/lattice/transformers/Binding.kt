package dev.zacsweers.lattice.transformers

import dev.zacsweers.lattice.ir.IrAnnotation
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

internal sealed interface Binding {
  val scope: IrAnnotation?
  val dependencies: Map<TypeKey, Parameter>
  val nameHint: String

  data class ConstructorInjected(
    val type: IrClass,
    override val dependencies: Map<TypeKey, Parameter>,
    override val scope: IrAnnotation? = null,
  ) : Binding {
    override val nameHint: String = type.name.asString()
  }

  data class Provided(
    val providerFunction: IrSimpleFunction,
    override val dependencies: Map<TypeKey, Parameter>,
    override val scope: IrAnnotation? = null,
  ) : Binding {
    override val nameHint: String = providerFunction.name.asString()
  }

  data class ComponentDependency(val component: IrClass, val getter: IrFunction) : Binding {
    override val scope: IrAnnotation? = null
    // TODO what if the getter is a property getter, then it's a special name
    override val nameHint: String = component.name.asString() + getter.name.asString()
    override val dependencies: Map<TypeKey, Parameter> = emptyMap()
  }
}
