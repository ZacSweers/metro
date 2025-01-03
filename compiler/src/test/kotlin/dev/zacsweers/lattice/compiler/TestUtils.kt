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

import com.google.common.truth.ThrowableSubject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tschuchort.compiletesting.CompilationResult
import com.tschuchort.compiletesting.DiagnosticMessage
import com.tschuchort.compiletesting.DiagnosticSeverity
import com.tschuchort.compiletesting.JvmCompilationResult
import dev.zacsweers.lattice.MembersInjector
import dev.zacsweers.lattice.Provider
import dev.zacsweers.lattice.Provides
import dev.zacsweers.lattice.internal.Factory
import dev.zacsweers.lattice.provider
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Callable
import kotlin.reflect.KCallable
import kotlin.reflect.KProperty
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.functions
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaMethod
import kotlin.test.assertFailsWith

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

fun <T> JvmCompilationResult.invokeTopLevel(name: String, vararg args: Any?): T {
  @Suppress("UNCHECKED_CAST")
  return classLoader
    .loadClass("test.ExampleClassKt")!!
    .staticMethods()
    .single { it.name == name }
    .invoke(*args) as T
}

fun <T> JvmCompilationResult.invokeMain(vararg args: Any?): T {
  @Suppress("UNCHECKED_CAST")
  return invokeTopLevel("main", *args) as T
}

val JvmCompilationResult.ExampleClass: Class<*>
  get() = classLoader.loadClass("test.ExampleClass")

val JvmCompilationResult.ExampleClassFactory: Class<*>
  get() = classLoader.loadClass("test.ExampleClassFactory")

fun Class<*>.newInstanceStrict(vararg args: Any): Any {
  return getDeclaredConstructor(*args.map { it::class.java }.toTypedArray()).newInstance(*args)
}

fun Any.callInject(instance: Any) {
  javaClass.getMethod("inject", instance::class.java).invoke(this, instance)
}

val Class<*>.Factory: Class<*>
  get() = classes.single { it.simpleName == "Factory" }

fun Class<*>.generatedFactoryClass(): Class<Factory<*>> {
  @Suppress("UNCHECKED_CAST")
  return generatedFactoryClassAssisted() as Class<Factory<*>>
}

fun Class<*>.generatedFactoryClassAssisted(): Class<*> {
  val expectedName = LatticeSymbols.Names.latticeFactory.asString()
  return classes.single { it.simpleName == expectedName }
}

fun Class<*>.generatedMembersInjector(): Class<MembersInjector<*>> {
  val expectedName = LatticeSymbols.Names.latticeMembersInjector.asString()
  val nestedClass =
    declaredClasses.singleOrNull { it.simpleName == expectedName }
      ?: error(
        "Did not find nested class with name $expectedName in $this. Available: ${classes.joinToString { it.simpleName }}"
      )
  @Suppress("UNCHECKED_CAST")
  return nestedClass as Class<MembersInjector<*>>
}

fun Class<*>.generatedAssistedFactoryImpl(): Class<*> {
  val expectedName = LatticeSymbols.Names.latticeImpl.asString()
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
    "${companionString}${methodName.capitalizeUS()}${LatticeSymbols.Names.latticeFactory.asString()}"
  @Suppress("UNCHECKED_CAST")
  return this.classes.singleOrNull { it.simpleName == expectedName } as Class<Factory<*>>?
    ?: error("Could not find nested class $this.$expectedName")
}

fun Class<Factory<*>>.invokeNewInstance(vararg args: Any): Any {
  return staticMethods().single { it.name == "newInstance" }.invoke(*args)!!
}

fun <T> Class<Factory<*>>.invokeNewInstanceAs(vararg args: Any): T {
  @Suppress("UNCHECKED_CAST")
  return invokeNewInstance(*args) as T
}

fun Class<Factory<*>>.invokeCreateAsFactory(vararg args: Any): Factory<*> {
  return invokeCreate(*args) as Factory<*>
}

// Cannot confine to Class<Factory<*>> because this is also used for assisted factories
fun Class<*>.invokeCreateAsProvider(vararg args: Any): Provider<*> {
  return invokeCreate(*args) as Provider<*>
}

val Class<*>.companionObjectClass: Class<*>
  get() {
    return companionObjectClassOrNull ?: error("No companion object found on $this")
  }

val Class<*>.companionObjectClassOrNull: Class<*>?
  get() {
    return classes.find { it.simpleName == "Companion" }
  }

class StaticMethod(val method: Method, val instance: Any? = null) {
  val name: String
    get() = method.name

  operator fun invoke(vararg args: Any?): Any? = method.invoke(instance, *args)
}

val Class<*>.objectInstanceFieldOrNull: Field?
  get() {
    return fields.find {
      Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) && it.name == "INSTANCE"
    }
  }

val Class<*>.companionObjectInstanceFieldOrNull: Field?
  get() {
    return fields.find {
      Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) && it.name == "Companion"
    }
  }

fun Class<*>.staticMethods(
  objectInstanceField: Field? = objectInstanceFieldOrNull
): Sequence<StaticMethod> = sequence {
  yieldAll(declaredMethods.filter { Modifier.isStatic(it.modifiers) }.map(::StaticMethod))

  if (objectInstanceField != null) {
    yieldAll(
      declaredMethods
        .filter { !Modifier.isStatic(it.modifiers) }
        .map { StaticMethod(it, objectInstanceField.get(null)) }
    )
  }

  companionObjectClassOrNull?.let {
    yieldAll(it.staticMethods(companionObjectInstanceFieldOrNull!!))
  }
}

// Cannot confine to Class<Factory<*>> because this is also used for assisted factories
fun Class<*>.invokeCreate(vararg args: Any): Any {
  val createFunctions =
    staticMethods().filter { it.name == LatticeSymbols.StringNames.create }.toList()

  return when (createFunctions.size) {
    0 -> error("No create functions found in $this")
    1 -> createFunctions.single()(*args)
    else -> {
      error("Multiple create functions found in $this:\n${createFunctions.joinToString("\n")}")
    }
  }!!
}

fun Class<Factory<*>>.invokeProvider(providerName: String, vararg args: Any): Any {
  return staticMethods().single { it.name == providerName }.invoke(*args)!!
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
 * then calling the graph's provider
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
 * then calling the graph's provider
 */
fun <T> Class<Factory<*>>.provideValueAs(providerName: String, vararg args: Any): T {
  @Suppress("UNCHECKED_CAST")
  return provideValue(providerName, *args) as T
}

val JvmCompilationResult.ExampleGraph: Class<*>
  get() = classLoader.loadClass("test.ExampleGraph")

fun Class<*>.generatedLatticeGraphClass(): Class<*> {
  return classes.singleOrNull { it.simpleName == LatticeSymbols.Names.latticeGraph.asString() }
    ?: error(
      "Could not find nested class $this.${LatticeSymbols.Names.latticeGraph.asString()}. Available: ${classes.joinToString { it.simpleName }}"
    )
}

fun Class<*>.graphImpl(): Class<*> {
  return declaredClasses.single { it.simpleName.endsWith("Impl") }
}

fun <T> Any.callFunction(name: String): T {
  @Suppress("UNCHECKED_CAST")
  return javaClass.getMethod(name).invoke(this) as T
}

fun <T> Any.callProperty(name: String): T {
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

/** Returns a new instance of a graph's factory class by invoking its static "factory" function. */
fun Class<*>.invokeGraphFactory(): Any {
  return staticMethods().single { it.name == "factory" }.invoke()!!
}

/** Creates a graph instance via its generated no-arg static create() function. */
fun Class<*>.createGraphWithNoArgs(): Any {
  return invokeCreate()
}

/**
 * Invokes a generated Graph Factory class's create() function with the supplied [args].
 *
 * Note the function must be called [LatticeSymbols.StringNames.create].
 */
fun Class<*>.createGraphViaFactory(vararg args: Any): Any {
  val factoryInstance = invokeGraphFactory()
  return factoryInstance.javaClass.declaredMethods
    .single { it.name == LatticeSymbols.StringNames.create }
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

fun Class<MembersInjector<*>>.staticInjectMethod(memberName: String): StaticMethod {
  val expectedName = "inject${memberName.capitalizeUS()}"
  return staticMethods().singleOrNull { it.name == expectedName }
    ?: error("No static method with name '$expectedName' found in $this")
}

@Suppress("UNCHECKED_CAST")
fun <T> Annotation.getValue(name: String = "value"): T {
  val value =
    this::class.java.declaredMethods.singleOrNull { it.name == name }
      ?: error("No 'value' property found on $this")
  return value.invoke(this) as T
}

fun Class<*>.packageName(): String = `package`.name.let { if (it.isBlank()) "" else "$it." }

fun CompilationResult.assertContainsAll(vararg messages: String) {
  check(messages.isNotEmpty()) { "No messages supplied" }
  for (message in messages) {
    assertThat(this.messages).contains(message)
  }
}

/**
 * Allows passing in a raw diagnostic output that it will parse and match up with recorded kotlinc
 * diagnostics in [this].
 */
fun CompilationResult.assertDiagnostics(expected: String) {
  val diagnostics = expected.parseDiagnostics()
  check(diagnostics.isNotEmpty()) { "No diagnostics supplied" }
  assertDiagnostics(diagnostics)
}

fun CompilationResult.assertNoWarningsOrErrors() {
  assertDiagnostics(emptyList(), emptyList())
}

fun CompilationResult.assertErrors(vararg errors: String) {
  assertDiagnostics(emptyList(), errors.toList())
}

fun CompilationResult.assertWarnings(vararg warnings: String) {
  assertDiagnostics(warnings.toList(), emptyList())
}

fun CompilationResult.assertDiagnostics(warnings: List<String>, errors: List<String>) {
  assertDiagnostics(
    mapOf(DiagnosticSeverity.WARNING to warnings, DiagnosticSeverity.ERROR to errors)
  )
}

fun CompilationResult.assertDiagnostics(diagnostics: Map<DiagnosticSeverity, List<String>>) {
  assertThat(cleanedDiagnostics).containsExactlyEntriesIn(diagnostics)
}

val CompilationResult.cleanedDiagnostics: Map<DiagnosticSeverity, List<String>>
  get() {
    return messages.parseDiagnostics()
  }

// Shorten messages, removing the intermediary temp dir and just printing the file name
private fun String.parseDiagnostics() =
  lineSequence()
    .filterNot { it.isBlank() }
    .mapNotNull { line ->
      if (line.startsWith("e: ")) {
        DiagnosticMessage(DiagnosticSeverity.ERROR, line.substring(3).trim())
      } else if (line.startsWith("w: ")) {
        DiagnosticMessage(DiagnosticSeverity.WARNING, line.substring(3).trim())
      } else if (line.startsWith("i: ")) {
        DiagnosticMessage(DiagnosticSeverity.INFO, line.substring(3).trim())
      } else {
        null
      }
    }
    .groupBy { it.severity }
    .mapValues { (_, messages) ->
      messages.map { it.message.cleanOutputLine(includeSeverity = false) }
    }

fun String.cleanOutputLine(includeSeverity: Boolean): String {
  val trimmed = trimEnd()
  val sourceFileIndex = trimmed.indexOf(".kt")
  if (sourceFileIndex == -1) return trimmed
  val startIndex =
    trimmed.withIndex().indexOfLast { (i, char) ->
      i < sourceFileIndex && char == File.separatorChar
    }
  if (startIndex == -1) return trimmed
  val severity =
    if (includeSeverity) {
      "${trimmed.substringBefore(": ", "")}: "
    } else {
      ""
    }
  return "$severity${trimmed.substring(startIndex + 1)}"
}

inline fun <reified T : Throwable> assertThrows(block: () -> Unit): ThrowableSubject {
  val throwable = assertFailsWith(T::class, block)
  return assertThat(throwable)
}
