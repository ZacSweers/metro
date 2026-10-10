// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.platform.jvm.isJvm

/**
 * Returns a static stand-in for this function when it's a `@JvmStatic` member of an object or
 * companion object on JVM. Otherwise returns this symbol.
 *
 * The JVM backend emits a static method for every `@JvmStatic` member. Calls from Kotlin still go
 * through the object instance though. Calling the stand-in compiles to a single `invokestatic` of
 * that static method instead. This skips loading the object and keeps the companion class out of
 * the caller's constant pool, which adds up in generated graphs that call thousands of factories.
 */
context(context: IrMetroContext)
internal fun IrSimpleFunctionSymbol.jvmStaticOrSelf(): IrSimpleFunctionSymbol {
  if (!context.platform.isJvm()) return this
  val function = owner
  function.jvmStaticStub?.let {
    return it.symbol
  }
  val container = function.parent as? IrClass ?: return this
  val canCallStatically =
    container.isObject &&
      function.dispatchReceiverParameter != null &&
      !function.isSuspend &&
      // Inline functions need their body at the call site.
      !function.isInline &&
      // Default arguments go through a `$default` bridge that only exists on the original class.
      function.parameters.none { it.defaultValue != null } &&
      function.hasAnnotation(Symbols.ClassIds.JvmStatic) &&
      !function.usesValueClasses()
  if (!canCallStatically) return this

  // A companion's static methods live on its outer class. A plain object holds its own.
  val staticOwner =
    if (container.isCompanion) {
      container.parent as? IrClass ?: return this
    } else {
      container
    }
  // Value classes mangle the names of their static methods.
  if (staticOwner.isValue) return this
  val stub =
    function.deepCopyWithSymbols(initialParent = staticOwner).apply {
      parent = staticOwner
      setDispatchReceiver(null)
      body = null
      // The stand-in is already static. Keep the JVM backend from treating it as a member again.
      with(context) {
        replaceAnnotationsCompat(
          annotationsCompat().filterNot {
            it.type.classOrNull?.owner?.classId == Symbols.ClassIds.JvmStatic
          },
        )
      }
    }
  function.jvmStaticStub = stub
  return stub.symbol
}

/** Value class parameters and returns get mangled names that a copied signature won't match. */
private fun IrSimpleFunction.usesValueClasses(): Boolean {
  val types = buildList {
    add(returnType)
    parameters.mapTo(this) { it.type }
  }
  return types.any { it.classOrNull?.owner?.isValue == true }
}

private var IrSimpleFunction.jvmStaticStub: IrSimpleFunction? by irAttribute(copyByDefault = false)
