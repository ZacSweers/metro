// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/** Includes the binary-name separator before Kotlin's generated companion object. */
internal const val GENERATED_COMPANION_NAME_BYTES = 10

/** Both FIR and IR must compute exactly the same provider-factory class ID. */
internal fun providerFactoryClassId(
  parentClassId: ClassId,
  callableName: Name,
  maxBytes: Int,
): ClassId {
  val suffix = Symbols.StringNames.METRO_FACTORY
  val preferredName = callableName.asString().capitalizeUS() + suffix
  return parentClassId
    .createNestedClassId(Name.identifier(preferredName))
    .truncate(
      maxLength = maxBytes,
      reservedNestedBytes = GENERATED_COMPANION_NAME_BYTES,
      hashSource = "provider-factory:${parentClassId.asString()}#${callableName.asString()}",
      requiredSuffix = suffix,
    )
}
