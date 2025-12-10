// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.parentAsClass

internal enum class PropertyType {
  FIELD,
  /** Property with a backing field and an inline getter for child graph access optimization. */
  FIELD_WITH_INLINE_GETTER,
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
  when (propertyType) {
    PropertyType.FIELD -> {
      // Add backing field if missing (even if getter exists, e.g., for reserved properties)
      if (backingField == null) {
        with(context) { addBackingFieldCompat { this.type = type() } }
      }
    }
    PropertyType.FIELD_WITH_INLINE_GETTER -> {
      // Add backing field if missing
      if (backingField == null) {
        with(context) { addBackingFieldCompat { this.type = type() } }
      }
      // Add inline getter for child graph access optimization.
      // Inline getters allow the compiler to optimize calls down to direct field access.
      addGetterIfMissing(type(), isInline = true)
    }
    PropertyType.GETTER -> {
      addGetterIfMissing(type(), isInline = false)
    }
  }
}

private fun IrProperty.addGetterIfMissing(type: IrType, isInline: Boolean) {
  if (getter == null) {
    addGetter {
        this.returnType = type
        this.visibility = this@addGetterIfMissing.visibility
        this.isInline = isInline
      }
      .apply {
        setDispatchReceiver(this@addGetterIfMissing.parentAsClass.thisReceiverOrFail.copyTo(this))
      }
  }
}

internal var IrProperty.graphPropertyData: GraphPropertyData? by irAttribute(copyByDefault = false)

internal data class GraphPropertyData(val contextKey: IrContextualTypeKey, val type: IrType)

/**
 * For inner class graphs (extension graphs), stores a reference to the parent graph class. This is
 * used to access the parent graph's `this` receiver when generating code that needs to access
 * parent graph properties.
 */
internal var IrClass.parentGraphClass: IrClass? by irAttribute(copyByDefault = false)

/**
 * For inner class graphs (extension graphs) that have shards, stores the backing field that holds
 * the parent graph instance. Shards (static nested classes) can't access the implicit outer class
 * reference, so they need this explicit field to navigate to the parent graph via
 * `this.graph.parentGraph`.
 *
 * This field is only generated when the graph will have shards.
 */
internal var IrClass.parentGraphField: IrField? by irAttribute(copyByDefault = false)
