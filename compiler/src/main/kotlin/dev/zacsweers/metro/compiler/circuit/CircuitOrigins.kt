// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.name.ClassId

/** FIR declaration origin keys and IR origins for Circuit-generated declarations. */
internal object CircuitOrigins {
  /** Key for generated Circuit factory classes. */
  data class FactoryClass(val target: CircuitCodegenTarget, val type: FactoryType?) :
    GeneratedDeclarationKey()

  /** Key for generated Circuit factory constructors. */
  object FactoryConstructor : GeneratedDeclarationKey()

  /** Key for generated Circuit factory `create()` functions. */
  object FactoryCreateFunction : GeneratedDeclarationKey()

  /** Key for a generated Circuit serializer registration class. */
  data class SerializerRegistrationClass(val serializedType: ClassId) : GeneratedDeclarationKey()

  /** Key for generated Circuit serializer registration constructors. */
  object SerializerRegistrationConstructor : GeneratedDeclarationKey()

  /** Key for generated Circuit serializer registration `register()` functions. */
  object SerializerRegistrationFunction : GeneratedDeclarationKey()

  // IR Origins
  //  val IrFactoryClass = IrDeclarationOrigin.GeneratedByPlugin(FactoryClass)
  val IrFactoryConstructor = IrDeclarationOrigin.GeneratedByPlugin(FactoryConstructor)
  val IrFactoryCreateFunction = IrDeclarationOrigin.GeneratedByPlugin(FactoryCreateFunction)
  val IrSerializerRegistrationConstructor =
    IrDeclarationOrigin.GeneratedByPlugin(SerializerRegistrationConstructor)
  val IrSerializerRegistrationFunction =
    IrDeclarationOrigin.GeneratedByPlugin(SerializerRegistrationFunction)
}
