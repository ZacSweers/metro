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
import com.google.common.truth.Truth.assertWithMessage
import com.tschuchort.compiletesting.CompilationResult
import com.tschuchort.compiletesting.JvmCompilationResult
import dev.zacsweers.lattice.LatticeSymbols
import dev.zacsweers.lattice.Provider
import dev.zacsweers.lattice.annotations.Provides
import dev.zacsweers.lattice.capitalizeUS
import dev.zacsweers.lattice.internal.Factory
import dev.zacsweers.lattice.mapToSet
import dev.zacsweers.lattice.provider
import java.lang.reflect.Modifier
import java.util.concurrent.Callable
import kotlin.reflect.KCallable
import kotlin.reflect.KProperty
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.functions
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaMethod

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

val JvmCompilationResult.ExampleClassFactory: Class<*>
  get() = classLoader.loadClass("test.ExampleClassFactory")

fun Class<*>.generatedFactoryClass(): Class<Factory<*>> {
  @Suppress("UNCHECKED_CAST")
  return generatedFactoryClassAssisted() as Class<Factory<*>>
}

fun Class<*>.generatedFactoryClassAssisted(): Class<*> {
  val expectedName = LatticeSymbols.Names.LatticeFactory.asString()
  return classes.single { it.simpleName == expectedName }
}

fun Class<*>.generatedAssistedFactoryImpl(): Class<*> {
  val expectedName = LatticeSymbols.Names.LatticeImpl.asString()
  return classes.single { it.simpleName == expectedName }
}

fun Class<*>.providesFactoryClass(
  providerCallableName: String? = null,
  companion: Boolean = false,
): Class<Factory<*>> {
  val companionString = if (companion) "Companion_" else ""

  val callables: List<KCallable<*>> =
    if (companion) {
      kotlin.companionObject!!.let { companionObject ->
        companionObject.memberProperties.toList() + companionObject.functions.toList()
      }
    } else {
      kotlin.memberProperties.toList() + kotlin.functions.toList()
    }

  val providesCallables =
    callables
      .filter {
        // Exclude synthetic annotation holder methods
        it.hasAnnotation<Provides>()
      }
      .mapToSet {
        when (it) {
          is KProperty<*> -> it.getter.javaMethod!!.name
          else -> it.name
        }
      }

  assertWithMessage("No @Provides methods found in $this").that(providesCallables).isNotEmpty()

  if (providerCallableName != null) {
    assertWithMessage(
        "The name '$providerCallableName' must match a callable annotated with @Provides"
      )
      .that(providesCallables)
      .contains(providerCallableName)
  } else {
    assertWithMessage(
        "You must specify a providerCallableName value when there is more than one @Provides callable"
      )
      .that(providesCallables)
      .hasSize(1)
  }

  val methodName = providerCallableName ?: providesCallables.single()

  val expectedName =
    "${companionString}${methodName.capitalizeUS()}${LatticeSymbols.Names.LatticeFactory.asString()}"
  @Suppress("UNCHECKED_CAST")
  return this.classes.singleOrNull { it.simpleName == expectedName } as Class<Factory<*>>?
    ?: error("Could not find nested class $this.$expectedName")
}

fun Class<Factory<*>>.invokeNewInstance(vararg args: Any): Any {
  return declaredMethods.single { it.name == "newInstance" }.invoke(null, *args)
}

fun <T> Class<Factory<*>>.invokeNewInstanceAs(vararg args: Any): T {
  @Suppress("UNCHECKED_CAST")
  return invokeNewInstance(*args) as T
}

fun Class<Factory<*>>.invokeCreateAsFactory(vararg args: Any): Factory<*> {
  return invokeCreate(*args) as Factory<*>
}

fun Class<*>.invokeCreateAsProvider(vararg args: Any): Provider<*> {
  return invokeCreate(*args) as Provider<*>
}

fun Class<*>.invokeCreate(vararg args: Any): Any {
  return declaredMethods
    .single { it.name == "create" && Modifier.isStatic(it.modifiers) }
    .invoke(null, *args)
}

fun Class<Factory<*>>.invokeProvider(providerName: String, vararg args: Any): Any {
  return declaredMethods.single { it.name == providerName }.invoke(null, *args)
}

fun <T> Class<Factory<*>>.invokeCreateAs(vararg args: Any): T {
  @Suppress("UNCHECKED_CAST")
  return invokeCreateAsFactory(*args) as T
}

/**
 * Exercises the whole generated factory creation flow by first creating with
 * [invokeCreateAsFactory] and then calling [Factory.invoke] to exercise its `newInstance()`.
 */
fun Class<Factory<*>>.createNewInstance(vararg args: Any): Any {
  val factory = invokeCreateAsFactory(*args)
  return factory()
}

/**
 * Exercises the whole generated factory provider flow by first creating with [invokeProvider] and
 * then calling the component's provider
 */
fun Class<Factory<*>>.provideValue(providerName: String, vararg args: Any): Any {
  return invokeProvider(providerName, *args)
}

/**
 * Exercises the whole generated factory creation flow by first creating with
 * [invokeCreateAsFactory] and then calling [Factory.invoke] to exercise its `newInstance()`.
 */
fun <T> Class<Factory<*>>.createNewInstanceAs(vararg args: Any): T {
  @Suppress("UNCHECKED_CAST")
  return createNewInstance(*args) as T
}

/**
 * Exercises the whole generated factory provider flow by first creating with [invokeProvider] and
 * then calling the component's provider
 */
fun <T> Class<Factory<*>>.provideValueAs(providerName: String, vararg args: Any): T {
  @Suppress("UNCHECKED_CAST")
  return provideValue(providerName, *args) as T
}

val JvmCompilationResult.ExampleComponent: Class<*>
  get() = classLoader.loadClass("test.ExampleComponent")

fun Class<*>.generatedLatticeComponentClass(): Class<*> {
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

fun <T> Any.invokeFactoryGet(vararg args: Any): T {
  return invokeInstanceMethod("get", *args) as T
}

fun <T> Any.invokeInstanceMethod(name: String, vararg args: Any): T {
  @Suppress("UNCHECKED_CAST")
  return javaClass.methods
    .single { it.name == name && !Modifier.isStatic(it.modifiers) }
    .invoke(this, *args) as T
}

/**
 * Returns a new instance of a component's factory class by invoking its static "factory" function.
 */
fun Class<*>.invokeComponentFactory(): Any {
  return declaredMethods
    .single { Modifier.isStatic(it.modifiers) && it.name == "factory" }
    .invoke(null)
}

/** Creates a component instance via its generated no-arg static create() function. */
fun Class<*>.createComponentWithNoArgs(): Any {
  return declaredMethods
    .single { Modifier.isStatic(it.modifiers) && it.name == "create" }
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

fun Class<*>.generatedClassesString(separator: String = "_"): String {
  return generateSequence(enclosingClass) { it.enclosingClass }
    .toList()
    .reversed()
    .joinToString(separator = "", prefix = packageName(), postfix = simpleName) {
      "${it.simpleName}$separator"
    }
}

fun Class<*>.packageName(): String = `package`.name.let { if (it.isBlank()) "" else "$it." }

fun CompilationResult.assertContainsAll(vararg messages: String) {
  check(messages.isNotEmpty()) { "No messages supplied" }
  for (message in messages) {
    assertThat(this.messages).contains(message)
  }
}
