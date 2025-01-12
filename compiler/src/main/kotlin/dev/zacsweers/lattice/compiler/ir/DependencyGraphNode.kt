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
package dev.zacsweers.lattice.compiler.ir

import dev.zacsweers.lattice.compiler.ir.parameters.ConstructorParameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameters
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

// Represents an object graph's structure and relationships
internal data class DependencyGraphNode(
  val sourceGraph: IrClass,
  val dependencies: List<DependencyGraphNode>,
  val scopes: Set<IrAnnotation>,
  val providerFunctions: List<Pair<TypeKey, LatticeSimpleFunction>>,
  // Types accessible via this graph (includes inherited)
  val exposedTypes: Map<LatticeSimpleFunction, ContextualTypeKey>,
  val bindsFunctions: Map<LatticeSimpleFunction, ContextualTypeKey>,
  val injectors: Map<LatticeSimpleFunction, ContextualTypeKey>,
  val isExternal: Boolean,
  val creator: Creator?,
  val typeKey: TypeKey,
) {
  data class Creator(
    val type: IrClass,
    val createFunction: IrSimpleFunction,
    val parameters: Parameters<ConstructorParameter>,
  )

  // Lazy-wrapped to cache these per-node
  val allDependencies by lazy {
    sequence {
        yieldAll(dependencies)
        dependencies.forEach { node -> yieldAll(node.dependencies) }
      }
      .toSet()
  }
}
