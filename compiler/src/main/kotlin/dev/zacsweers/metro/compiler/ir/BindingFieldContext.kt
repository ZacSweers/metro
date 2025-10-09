// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.declarations.IrField

internal class BindingFieldContext {
  // TODO we can end up in awkward situations where we
  //  have the same type keys in both instance and provider fields
  //  this is tricky because depending on the context, it's not valid
  //  to use an instance (for example - you need a provider). How can we
  //  clean this up?
  // Fields for this graph and other instance params
  private val instanceFields = mutableMapOf<IrTypeKey, IrField>()
  // Fields for providers. May include both scoped and unscoped providers as well as bound
  // instances
  private val providerFields = mutableMapOf<IrTypeKey, IrField>()

  val availableInstanceKeys: Set<IrTypeKey>
    get() = instanceFields.keys

  val availableProviderKeys: Set<IrTypeKey>
    get() = providerFields.keys

  fun hasKey(key: IrTypeKey): Boolean = key in instanceFields || key in providerFields

  fun putInstanceField(key: IrTypeKey, field: IrField) {
    instanceFields[key] = field
  }

  fun putProviderField(key: IrTypeKey, field: IrField) {
    providerFields[key] = field
  }

  fun instanceField(key: IrTypeKey): IrField? {
    return instanceFields[key]
  }

  fun providerField(key: IrTypeKey): IrField? {
    return providerFields[key]
  }

  operator fun contains(key: IrTypeKey): Boolean =
    instanceFields.containsKey(key) || providerFields.containsKey(key)
}
