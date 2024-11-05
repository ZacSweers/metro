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
package dev.zacsweers.lattice.compiler

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.JvmCompilationResult
import dev.zacsweers.lattice.internal.Factory
import java.util.concurrent.Callable

fun JvmCompilationResult.assertCallableFactory(value: String) {
  val factory = ExampleClass.generatedFactoryClass()
  val callable = factory.invokeNewInstanceAs<Callable<String>>(value)
  assertThat(callable.call()).isEqualTo(value)
}

val JvmCompilationResult.ExampleClass: Class<*>
  get() {
    return classLoader.loadClass("test.ExampleClass")
  }

fun Class<*>.generatedFactoryClass(): Class<Factory<*>> {
  @Suppress("UNCHECKED_CAST")
  return classLoader.loadClass(name + "_Factory") as Class<Factory<*>>
}

fun Class<Factory<*>>.invokeNewInstance(vararg args: Any): Any {
  return declaredMethods.single { it.name == "newInstance" }.invoke(null, *args)
}

fun <T> Class<Factory<*>>.invokeNewInstanceAs(vararg args: Any): T {
  @Suppress("UNCHECKED_CAST")
  return invokeNewInstance(*args) as T
}

fun Class<Factory<*>>.invokeCreate(vararg args: Any): Factory<*> {
  return declaredMethods.single { it.name == "create" }.invoke(null, *args) as Factory<*>
}
