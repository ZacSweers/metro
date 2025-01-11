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

import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.ir.parameters.ConstructorParameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameters
import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.name.ClassId

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
  val generatedGraphId: ClassId by unsafeLazy {
    sourceGraph.requireNestedClass(LatticeSymbols.Names.latticeGraph).classIdOrFail
  }

  data class Creator(
    val type: IrClass,
    val createFunction: IrSimpleFunction,
    val parameters: Parameters<ConstructorParameter>,
  )

  // Build a full type map including inherited providers
  fun getAllProviders(context: LatticeTransformerContext): Map<TypeKey, LatticeSimpleFunction> {
    return sourceGraph.getAllProviders(context)
  }

  private fun IrClass.getAllProviders(
    context: LatticeTransformerContext
  ): Map<TypeKey, LatticeSimpleFunction> {
    val result = mutableMapOf<TypeKey, LatticeSimpleFunction>()

    // Add supertype providers first (can be overridden)
    // TODO cache these recursive lookups
    // TODO what about generic types?
    superTypes.forEach { superType -> result.putAll(superType.rawType().getAllProviders(context)) }

    // Add our providers (overriding inherited ones if needed)
    providerFunctions.forEach { (typeKey, function) -> result[typeKey] = function }

    return result
  }

  // Lazy-wrapped to cache these per-node
  val allDependencies by lazy {
    sequence {
        yieldAll(dependencies)
        dependencies.forEach { node -> yieldAll(node.dependencies) }
      }
      .toSet()
  }
}
