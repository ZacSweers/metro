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
package dev.zacsweers.lattice.compiler.fir

import dev.zacsweers.lattice.compiler.LatticeClassIds
import dev.zacsweers.lattice.compiler.LatticeOptions
import dev.zacsweers.lattice.compiler.fir.generators.AssistedFactoryFirGenerator
import dev.zacsweers.lattice.compiler.fir.generators.InjectConstructorFactoryFirGenerator
import dev.zacsweers.lattice.compiler.fir.generators.ProvidesFactoryFirGenerator
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

internal class LatticeFirExtensionRegistrar(
  private val latticeClassIds: LatticeClassIds,
  private val options: LatticeOptions,
) : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +LatticeFirBuiltIns.getFactory(latticeClassIds)
    +::LatticeFirCheckers
    // TODO enable once we support metadata propagation
    //    +::FirProvidesStatusTransformer
    +::InjectConstructorFactoryFirGenerator
    +::ProvidesFactoryFirGenerator
    if (options.generateAssistedFactories) {
      +::AssistedFactoryFirGenerator
    }
  }
}
