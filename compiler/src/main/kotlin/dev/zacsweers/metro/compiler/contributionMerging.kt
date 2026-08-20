// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.name.ClassId

internal inline fun <T : Any> computeOriginClassIdChain(
  startClass: T,
  originClassId: (T) -> ClassId?,
  resolveClass: (ClassId) -> T?,
): List<ClassId> {
  val chain = mutableListOf<ClassId>()
  val seen = mutableSetOf<ClassId>()
  var currentClass = startClass

  while (true) {
    val currentOriginClassId = originClassId(currentClass) ?: break
    if (!seen.add(currentOriginClassId)) break
    chain += currentOriginClassId
    currentClass = resolveClass(currentOriginClassId) ?: break
  }

  return chain
}
