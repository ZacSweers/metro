package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.builders.declarations.addBackingField
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.parentAsClass

internal enum class PropertyType {
  FIELD,
  GETTER,
}

internal fun IrProperty.ensureInitialized(
  propertyType: PropertyType,
  type: () -> IrType = { graphPropertyData!!.type },
): IrProperty = apply {
  if (backingField == null && getter == null) {
    when (propertyType) {
      PropertyType.FIELD ->
        addBackingField {
          this.type = type()
        }
      PropertyType.GETTER ->
        addGetter {
          this.returnType = type()
          this.visibility = this@ensureInitialized.visibility
        }
          .apply {
            setDispatchReceiver(
              this@ensureInitialized.parentAsClass.thisReceiverOrFail.copyTo(this)
            )
          }
    }
  }
}

internal var IrProperty.graphPropertyData: GraphPropertyData? by irAttribute(copyByDefault = false)

internal data class GraphPropertyData(val typeKey: IrTypeKey, val type: IrType)
