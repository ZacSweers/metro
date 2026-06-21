// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

// CircuitClassIds lives in metro-common so the IDE plugin can share it.

internal object CircuitCallableIds {
  private val presenterPackage = FqName("com.slack.circuit.runtime.presenter")
  private val uiPackage = FqName("com.slack.circuit.runtime.ui")

  val presenterOf = CallableId(presenterPackage, Name.identifier("presenterOf"))
  val ui = CallableId(uiPackage, Name.identifier("ui"))
}

internal object CircuitNames {
  val Factory = Name.identifier("CircuitFactory")
  val create = Name.identifier("create")
  val screen = Name.identifier("screen")
  val scope = Name.identifier("scope")
  val navigator = Name.identifier("navigator")
  val context = Name.identifier("context")
  val state = Name.identifier("state")
  val modifier = Name.identifier("modifier")
  val provider = Name.identifier("provider")
  val factoryField = Name.identifier("factory")
}
