// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.remapTypes
import dev.zacsweers.metro.compiler.proto.MemberInjectionsProto
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.name.ClassId

internal data class MemberInjectClass(
  val sourceClass: IrClass,
  /**
   * The generated injector class. May be null if the [sourceClass] has no direct members to inject.
   */
  val injectorClass: IrClass?,
  /** The class that declares [injectorClass]'s `create()` and `inject{Name}()` helpers. */
  val helperOwner: IrClass?,
  val typeKey: IrTypeKey,
  val requiredParametersByClass: Map<ClassId, List<Parameters>>,
  val declaredInjectFunctions: Map<IrSimpleFunction, Parameters>,
  val isDagger: Boolean,
) {
  fun toProto(): MemberInjectionsProto {
    return MemberInjectionsProto(
      // Simple name is fine because it's always nested in the parent class
      injector_class_name = injectorClass!!.name.asString(),
      member_inject_functions = declaredInjectFunctions.keys.map { it.name.asString() },
    )
  }

  fun mergedParameters(remapper: TypeRemapper): Parameters {
    // `MembersInjector` -> origin class
    val allParams = declaredInjectFunctions.map { (_, parameters) ->
      parameters.remapTypes(remapper)
    }
    return when (allParams.size) {
      0 -> Parameters.empty()
      1 -> allParams.first()
      else -> allParams.reduce { current, next -> current.mergeValueParametersWith(next) }
    }
  }
}
