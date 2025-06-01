// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.PLUGIN_ID
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import org.jetbrains.kotlin.ir.declarations.IrClass

context(context: IrMetroContext)
internal var IrClass.metroMetadata: MetroMetadata?
  get() {
    return context.pluginContext.metadataDeclarationRegistrar
      .getCustomMetadataExtension(this, PLUGIN_ID)
      ?.let { MetroMetadata.ADAPTER.decode(it) }
  }
  set(value) {
    if (value == null) return
    context.pluginContext.metadataDeclarationRegistrar.addCustomMetadataExtension(
      this,
      PLUGIN_ID,
      value.encode(),
    )
  }
