/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice

import dev.zacsweers.lattice.annotations.DependencyGraph

/**
 * Creates a new parameter-less graph of type [T]. Note this is _only_ applicable for graphs that
 * have no creators (i.e. [DependencyGraph.Factory]).
 */
public inline fun <reified T : Any> createGraph(): T {
  throw NotImplementedError("Implemented by the compiler")
}

/**
 * Creates a new instance of a [@DependencyGraph.Factory][DependencyGraph.Factory]-annotated class.
 */
public inline fun <reified T : Any> createGraphFactory(): T {
  throw NotImplementedError("Implemented by the compiler")
}
