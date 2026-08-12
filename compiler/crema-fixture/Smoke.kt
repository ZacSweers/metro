// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.createGraph

@Inject class ValueHolder(val value: String = "OK")

@DependencyGraph
interface AppGraph {
  val holder: ValueHolder
}

fun box(): String = createGraph<AppGraph>().holder.value

fun main() {
  print(box())
}
