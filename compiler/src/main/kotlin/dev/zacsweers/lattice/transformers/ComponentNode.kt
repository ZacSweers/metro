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
package dev.zacsweers.lattice.transformers

import dev.zacsweers.lattice.ir.IrAnnotation
import dev.zacsweers.lattice.ir.rawType
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

// Represents a component's structure and relationships
internal data class ComponentNode(
  val sourceComponent: IrClass,
  val generatedComponentId: ClassId,
  val isAnnotatedWithComponent: Boolean,
  val dependencies: List<ComponentNode>,
  val scope: IrAnnotation?,
  val providerFunctions: List<Pair<TypeKey, IrSimpleFunction>>,
  // Types accessible via this component (includes inherited)
  // TODO this should eventually expand to cover inject(...) calls too once we have member injection
  val exposedTypes: Map<IrSimpleFunction, ContextualTypeKey>,
  val isExternal: Boolean,
  val creator: Creator?,
  val typeKey: TypeKey,
) {
  data class Creator(
    val type: IrClass,
    val createFunction: IrSimpleFunction,
    val parameters: Parameters,
  )

  // Build a full type map including inherited providers
  fun getAllProviders(context: LatticeTransformerContext): Map<TypeKey, IrFunction> {
    return sourceComponent.getAllProviders(context)
  }

  private fun IrClass.getAllProviders(
    context: LatticeTransformerContext
  ): Map<TypeKey, IrFunction> {
    val result = mutableMapOf<TypeKey, IrFunction>()

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
