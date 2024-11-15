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
import dev.zacsweers.lattice.capitalizeUS
import dev.zacsweers.lattice.internal.Factory
import dev.zacsweers.lattice.provider
import java.lang.reflect.Modifier
import java.util.concurrent.Callable

fun JvmCompilationResult.assertCallableFactory(value: String) {
  val factory = ExampleClass.generatedFactoryClass()
  val callable = factory.createNewInstanceAs<Callable<String>>(provider { value })
  assertThat(callable.call()).isEqualTo(value)
}

fun JvmCompilationResult.assertNoArgCallableFactory(expectedValue: String) {
  val factory = ExampleClass.generatedFactoryClass()
  val callable = factory.createNewInstanceAs<Callable<String>>()
  assertThat(callable.call()).isEqualTo(expectedValue)
}

val JvmCompilationResult.ExampleClass: Class<*>
  get() = classLoader.loadClass("test.ExampleClass")

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

fun <T> Class<Factory<*>>.invokeCreateAs(vararg args: Any): T {
  @Suppress("UNCHECKED_CAST")
  return invokeCreate(*args) as T
}

/**
 * Exercises the whole generated factory creation flow by first creating with [invokeCreate] and
 * then calling [Factory.invoke] to exercise its `newInstance()`.
 */
fun Class<Factory<*>>.createNewInstance(vararg args: Any): Any {
  val factory = invokeCreate(*args)
  return factory()
}

/**
 * Exercises the whole generated factory creation flow by first creating with [invokeCreate] and
 * then calling [Factory.invoke] to exercise its `newInstance()`.
 */
fun <T> Class<Factory<*>>.createNewInstanceAs(vararg args: Any): T {
  @Suppress("UNCHECKED_CAST")
  return createNewInstance(*args) as T
}

val JvmCompilationResult.ExampleComponent: Class<*>
  get() = classLoader.loadClass("test.ExampleComponent")

fun Class<*>.generatedLatticeComponent(): Class<*> {
  return classLoader.loadClass("$packageName.Lattice$simpleName")
}

fun Class<*>.componentImpl(): Class<*> {
  return declaredClasses.single { it.simpleName.endsWith("Impl") }
}

fun <T> Any.callComponentAccessor(name: String): T {
  @Suppress("UNCHECKED_CAST")
  return javaClass.getMethod(name).invoke(this) as T
}

fun <T> Any.callComponentAccessorProperty(name: String): T {
  @Suppress("UNCHECKED_CAST")
  return javaClass.getMethod("get${name.capitalizeUS()}").invoke(this) as T
}

/**
 * Returns a new instance of a component's factory class by invoking its static "factory" function.
 */
fun Class<*>.invokeComponentFactory(): Any {
  @Suppress("UNCHECKED_CAST")
  return declaredMethods
    .single { Modifier.isStatic(it.modifiers) && it.name == "factory" }
    .invoke(null)
}

/**
 * Invokes a generated Component Factory class's create() function with the supplied [args].
 *
 * Note the function must be called "create".
 */
fun Class<*>.createComponentViaFactory(vararg args: Any): Any {
  val factoryInstance = invokeComponentFactory()
  return factoryInstance.javaClass.declaredMethods
    .single { it.name == "create" }
    .invoke(factoryInstance, *args)
}
