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
plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.atomicfu)
  id("dev.zacsweers.metro")
}

kotlin {
  jvm()
  /*
   TODO non-jvm targets fail with "IrValueParameterSymbolImpl is already bound" exceptions
    e: java.lang.IllegalStateException: IrValueParameterSymbolImpl is already bound. Signature: null.
    Owner: VALUE_PARAMETER INSTANCE_RECEIVER name:<this> type:<uninitialized parent>.$$MetroGraph
        at org.jetbrains.kotlin.ir.symbols.impl.IrSymbolBase.bind(IrSymbolImpl.kt:67)
        at org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl.<init>(IrValueParameterImpl.kt:48)
        at org.jetbrains.kotlin.ir.declarations.IrFactory.createValueParameter(IrFactory.kt:407)
        at org.jetbrains.kotlin.backend.common.serialization.IrDeclarationDeserializer.deserializeIrValueParameter
        (IrDeclarationDeserializer.kt:298)
        at org.jetbrains.kotlin.backend.common.serialization.IrDeclarationDeserializer.deserializeIrValueParameter
        $default(IrDeclarationDeserializer.kt:294)
  */
  // macosArm64()
  // js { browser() }
  // @OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }
  sourceSets { commonTest { dependencies { implementation(libs.kotlin.test) } } }
}

metro { debug.set(false) }
