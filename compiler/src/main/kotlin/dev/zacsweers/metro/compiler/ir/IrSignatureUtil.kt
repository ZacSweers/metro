// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ir.parameters.parameters
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.kotlinFqName

/**
 * Returns a string that's sort of like a JVM descriptor but not actually a valid one. This is just
 * used for identifying distinct functions.
 */
context(context: IrMetroContext)
internal fun IrFunction.computeJvmDescriptorIsh(
  customName: String? = null,
  includeReturnType: Boolean = true,
  includeInstanceReceiver: Boolean = false,
): String = buildString {
  if (customName != null) {
    append(customName)
  } else {
    if (this@computeJvmDescriptorIsh is IrSimpleFunction) {
      append(name.asString())
    } else {
      append("<init>")
    }
  }

  append("(")
  val paramsToInclude =
    with(parameters()) {
      if (includeInstanceReceiver) {
        allParameters
      } else {
        nonDispatchParameters
      }
    }
  for (parameter in paramsToInclude) {
    append(parameter.typeKey.type.rawTypeOrNull()?.kotlinFqName?.asString() ?: "kotlin.Any")
  }
  append(")")

  if (includeReturnType) {
    if (
      this@computeJvmDescriptorIsh !is IrSimpleFunction || returnType == context.irBuiltIns.unitType
    ) {
      append("V")
    } else {
      append(returnType.rawType().kotlinFqName)
    }
  }
}
