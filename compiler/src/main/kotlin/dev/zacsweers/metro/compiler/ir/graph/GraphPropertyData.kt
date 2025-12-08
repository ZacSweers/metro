// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
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

/**
 * Implementation note: sometimes these properties may be "mutable" because they are set in chunked
 * inits, but we always mark them as `val` anyway because the IR code gen will just set the field
 * directly in those cases.
 */
context(context: IrMetroContext)
internal fun IrProperty.ensureInitialized(
  propertyType: PropertyType,
  type: () -> IrType = { graphPropertyData!!.type },
): IrProperty = apply {
  if (backingField == null && getter == null) {
    when (propertyType) {
      PropertyType.FIELD -> with(context) { addBackingFieldCompat { this.type = type() } }
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

internal data class GraphPropertyData(val contextKey: IrContextualTypeKey, val type: IrType)
