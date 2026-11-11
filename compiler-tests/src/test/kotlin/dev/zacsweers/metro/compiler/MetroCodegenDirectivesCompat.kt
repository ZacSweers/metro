// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.model.SimpleDirective

/** Kotlin 2.5.20 moved this directive into JvmCodegenDirectives. */
internal val IGNORE_DEXING_COMPAT: SimpleDirective by lazy {
  val legacyGetter =
    CodegenTestDirectives::class.java.declaredMethods.find { it.name == "getIGNORE_DEXING" }
  if (legacyGetter != null) {
    legacyGetter.invoke(CodegenTestDirectives) as SimpleDirective
  } else {
    val containerClass = Class.forName("org.jetbrains.kotlin.test.directives.JvmCodegenDirectives")
    val container = containerClass.getField("INSTANCE").get(null)
    containerClass.getMethod("getIGNORE_DEXING").invoke(container) as SimpleDirective
  }
}
