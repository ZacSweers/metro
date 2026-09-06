// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.ir.buildDeepSubstitutionMap
import kotlin.test.assertEquals
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.name.Name
import org.junit.Test

/** Checks that shared inheritance paths don't repeat supertype reads. */
class DeepTypeSubstitutionTest {

  @Test
  fun layeredDiamondsReadEachSupertypeOnce() {
    val supertypeLists = mutableListOf<CountingSupertypeList>()

    fun interfaceClass(name: String, vararg parents: IrClass): IrClass {
      val declaration = IrFactoryImpl.buildClass {
        this.name = Name.identifier(name)
        kind = ClassKind.INTERFACE
        modality = Modality.ABSTRACT
      }
      val supertypes = CountingSupertypeList(parents.map { it.typeWith() })
      declaration.superTypes = supertypes
      supertypeLists += supertypes
      return declaration
    }

    val leaf = interfaceClass("Value")
    var left = interfaceClass("Left0", leaf)
    var right = interfaceClass("Right0", leaf)
    for (level in 1..4) {
      val nextLeft = interfaceClass("Left$level", left, right)
      val nextRight = interfaceClass("Right$level", left, right)
      left = nextLeft
      right = nextRight
    }
    val target = interfaceClass("Target", left, right)

    // This call bypasses the remapper cache.
    buildDeepSubstitutionMap(target, target.typeWith())

    assertEquals(supertypeLists.sumOf { it.size }, supertypeLists.sumOf { it.reads })
  }
}

/** Counts reads of real IR supertype edges. */
private class CountingSupertypeList(private val supertypes: List<IrType>) : AbstractList<IrType>() {
  var reads = 0
    private set

  override val size: Int
    get() = supertypes.size

  override fun get(index: Int): IrType {
    reads++
    return supertypes[index]
  }
}
