// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Minimal reproducer transformer for IC bug testing.
 *
 * Finds classes named `ICReproCollector` and implements their abstract `getReproStatus` function:
 * - Returns "present" if a `Marker` class is found in dependencies
 * - Returns "absent" if `Marker` class is not found
 *
 * Uses [trackClassLookup] to ensure proper IC linking.
 */
internal class ICReproTransformer(context: IrMetroContext) :
  IrElementTransformerVoid(), IrMetroContext by context {

  override fun visitClass(declaration: IrClass): IrStatement {
    if (declaration.name.asString() == "ICReproCollector") {
      implementReproStatusFunction(declaration)
    }
    return super.visitClass(declaration)
  }

  private fun implementReproStatusFunction(collectorClass: IrClass) {
    // Find the abstract getReproStatus function
    val getReproStatusFunction = collectorClass.getSimpleFunction("getReproStatus")!!

    // Try to find the Marker class
    val packageFqName = collectorClass.classId?.packageFqName ?: FqName.ROOT
    val markerClassId = ClassId(packageFqName, Name.identifier("Marker"))
    val markerClass = pluginContext.referenceClass(markerClassId)?.owner

    // Track the lookup for IC
    if (markerClass != null) {
      trackClassLookup(collectorClass, markerClass)
    }

    // Transform the function body
    val statusValue = if (markerClass != null) "present" else "absent"

    getReproStatusFunction.owner.body =
      createIrBuilder(getReproStatusFunction).run { irExprBodySafe(irString(statusValue)) }
  }
}
