// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import com.google.common.truth.ThrowableSubject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tschuchort.compiletesting.CompilationResult
import com.tschuchort.compiletesting.DiagnosticMessage
import com.tschuchort.compiletesting.DiagnosticSeverity
import com.tschuchort.compiletesting.JvmCompilationResult
import dev.zacsweers.metro.MembersInjector
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.internal.Factory
import dev.zacsweers.metro.internal.MetroImplMarker
import dev.zacsweers.metro.provider
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Callable
import kotlin.reflect.KCallable
import kotlin.reflect.KProperty
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.functions
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaMethod
import kotlin.test.assertFailsWith
import org.jetbrains.kotlin.descriptors.runtime.structure.primitiveByWrapper

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

fun <T> JvmCompilationResult.invokeTopLevel(name: String, vararg args: Any?, clazz: String): T {
  @Suppress("UNCHECKED_CAST")
  return classLoader.loadClass(clazz)!!.staticMethods().single { it.name == name }.invoke(*args)
    as T
}

fun <T> JvmCompilationResult.invokeMain(vararg args: Any?, mainClass: String = "test.MainKt"): T {
  return invokeTopLevel("main", args = args, clazz = mainClass) as T
}

val JvmCompilationResult.ExampleClass: Class<*>
  get() = classLoader.loadClass("test.ExampleClass")

val JvmCompilationResult.ExampleClass2: Class<*>
  get() = classLoader.loadClass("test.ExampleClass2")

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
  val expectedName = Symbols.Names.MetroFactory.asString()
  return classes.single { it.simpleName == expectedName }
}

fun Class<*>.generatedMembersInjector(): Class<MembersInjector<*>> {
  val expectedName = Symbols.Names.MetroMembersInjector.asString()
  val nestedClass =
    declaredClasses.singleOrNull { it.simpleName == expectedName }
      ?: error(
        "Did not find nested class with name $expectedName in $this. Available: ${classes.joinToString { it.simpleName }}"
      )
  @Suppress("UNCHECKED_CAST")
  return nestedClass as Class<MembersInjector<*>>
}

fun Class<*>.generatedAssistedFactoryImpl(): Class<*> {
  val expectedName = Symbols.Names.Impl.asString()
  return classes.single { it.simpleName == expectedName }
}

fun Class<*>.providesFactoryClass(
  providerCallableName: String? = null,
  companion: Boolean = false,
): Class<Factory<*>> {
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

  val expectedName = "${methodName.capitalizeUS()}${Symbols.Names.MetroFactory.asString()}"

  val classToSearch = if (companion) companionObjectClass else this

  @Suppress("UNCHECKED_CAST")
  return classToSearch.classes.singleOrNull { it.simpleName == expectedName } as Class<Factory<*>>?
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
fun <T> Class<*>.invokeCreateAsProvider(vararg args: Any): Provider<T> {
  @Suppress("UNCHECKED_CAST")
  return invokeCreate(*args) as Provider<T>
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

  operator fun invoke(vararg args: Any?): Any? {
    try {
      return method.invoke(instance, *args)
    } catch (e: Exception) {
      throw AssertionError(
        "Error invoking!\n Method: $method\nArgs: ${args.contentToString()}\n",
        e,
      )
    }
  }
}

val Class<*>.objectInstanceFieldOrNull: Field?
  get() {
    return fields.find {
      Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) && it.name == "INSTANCE"
    }
  }

val Class<*>.companionObjectInstance: Any
  get() {
    return companionObjectInstanceOrNull ?: error("No companion object instance found on $this")
  }

val Class<*>.companionObjectInstanceOrNull: Any?
  get() {
    return companionObjectInstanceFieldOrNull?.get(null)
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
  val createFunctions = staticMethods().filter { it.name == Symbols.StringNames.CREATE }.toList()

  return when (createFunctions.size) {
    0 -> error("No create functions found in $this")
    1 -> createFunctions.single()(*args)
    else -> {
      error("Multiple create functions found in $this:\n${createFunctions.joinToString("\n")}")
    }
  }!!
}

fun Class<*>.invokeStaticInvokeOperator(vararg args: Any): Any {
  val invokeFunctions = staticMethods().filter { it.name == Symbols.StringNames.INVOKE }.toList()

  return when (invokeFunctions.size) {
    0 -> error("No invoke functions found in $this")
    1 -> invokeFunctions.single()(*args)
    else -> {
      error("Multiple invoke functions found in $this:\n${invokeFunctions.joinToString("\n")}")
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
fun Class<Factory<*>>.createNewInstance(vararg args: Any): Any? {
  val factory = invokeCreateAsFactory(*args)
  return factory()
}

/**
 * Exercises the whole generated factory provider flow by first creating with [invokeProvider] and
 * then calling the graph's provider
 */
fun Class<Factory<*>>.provideValue(providerName: String, vararg args: Any): Any? {
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

val JvmCompilationResult.ParentGraph: Class<*>
  get() = classLoader.loadClass("test.ParentGraph")

val JvmCompilationResult.GrandParentGraph: Class<*>
  get() = classLoader.loadClass("test.GrandParentGraph")

val JvmCompilationResult.ChildGraph: Class<*>
  get() = classLoader.loadClass("test.ChildGraph")

val JvmCompilationResult.Parent1Graph: Class<*>
  get() = classLoader.loadClass("test.Parent1Graph")

val JvmCompilationResult.Parent2Graph: Class<*>
  get() = classLoader.loadClass("test.Parent2Graph")

fun Class<*>.generatedImpl(): Class<*> {
  return implOrNull()
    ?: error(
      "Could not find nested class $this.${Symbols.Names.Impl}. Available: ${classes.joinToString { it.simpleName }}"
    )
}

fun Class<*>.implOrNull(): Class<*>? {
  return declaredClasses.singleOrNull { it.isAnnotationPresent(MetroImplMarker::class.java) }
}

fun <T> Any.callFunction(name: String, vararg args: Any): T {
  @Suppress("UNCHECKED_CAST")
  return javaClass
    .getMethod(
      name,
      *args
        .map { it.javaClass.unboxIfPrimitive }
        .mapToArray {
          if (it.isAnnotationPresent(MetroImplMarker::class.java)) {
            it.enclosingClass
          } else {
            it
          }
        },
    )
    .invoke(this, *args) as T
}

private val Class<*>.unboxIfPrimitive: Class<*>
  get() = primitiveByWrapper ?: this

fun <T> Any.callProperty(name: String): T {
  @Suppress("UNCHECKED_CAST")
  return javaClass.getMethod("get${name.capitalizeUS()}").invoke(this) as T
}

fun <T> Any.callFactoryInvoke(vararg args: Any): T {
  return invokeInstanceMethod(Symbols.StringNames.INVOKE, *args) as T
}

fun <T> Any.invokeInstanceMethod(name: String, vararg args: Any): T {
  @Suppress("UNCHECKED_CAST")
  return getInstanceMethod(name).invoke(this, *args) as T
}

fun Any.getInstanceMethod(name: String): Method {
  @Suppress("UNCHECKED_CAST")
  return javaClass.methods.singleOrNull { it.name == name && !Modifier.isStatic(it.modifiers) }
    ?: error(
      "No instance method with name '$name' found in $this. Available: ${
        javaClass.methods.filterNot {
          Modifier.isStatic(
            it.modifiers
          )
        }.joinToString { it.name }
      }"
    )
}

suspend fun <T> Any.invokeSuspendInstanceFunction(name: String, vararg args: Any): T {
  // Add the instance receiver as the first argument
  val mergedArgs =
    Array(1 + args.size) {
      when (it) {
        0 -> this
        else -> args[it - 1]
      }
    }
  @Suppress("UNCHECKED_CAST")
  return javaClass.kotlin.declaredFunctions.single { it.name == name }.callSuspend(*mergedArgs) as T
}

/** Returns a new instance of a graph's factory class by invoking its static "factory" function. */
fun Class<*>.invokeGraphFactory(): Any {
  // TODO update callers to new location
  val staticMethod = staticMethods().singleOrNull { it.name == "factory" }
  return if (staticMethod != null) {
    staticMethod.invoke()!!
  } else {
    // We're in a graph impl right now, so go up one and then find its companion object
    enclosingClass.companionObjectInstanceFieldOrNull?.get(null)
      ?: error("No factory found for $this")
  }
}

/** Creates a graph instance via its generated no-arg static create() function. */
fun Class<*>.createGraphWithNoArgs(): Any {
  // TODO update callers to new location
  // We're in a graph impl right now, so go up one and then find its companion object
  return enclosingClass.invokeStaticInvokeOperator()
}

/**
 * Invokes a generated Graph Factory class's create() function with the supplied [args].
 *
 * Note the function must be called [Symbols.StringNames.CREATE].
 */
fun Class<*>.createGraphViaFactory(vararg args: Any): Any {
  val factoryInstance = invokeGraphFactory()
  return factoryInstance.javaClass.declaredMethods
    .single { it.name == Symbols.StringNames.CREATE && Modifier.isFinal(it.modifiers) }
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
  assertThat(cleanedDiagnostics.filterValues { it.isNotEmpty() })
    .containsExactlyEntriesIn(diagnostics.filterValues { it.isNotEmpty() })
}

val CompilationResult.cleanedDiagnostics: Map<DiagnosticSeverity, List<String>>
  get() {
    return messages.parseDiagnostics()
  }

// Shorten messages, removing the intermediary temp dir and just printing the file name
// Note diagnostics may be multi-line, so we chunk by severity prefixes
private fun String.parseDiagnostics(): Map<DiagnosticSeverity, List<String>> {
  return lineSequence()
    .chunkedLinesBy { it.startsWith("e: ") || it.startsWith("w: ") || it.startsWith("i: ") }
    .map { it.trim() }
    .mapNotNull { text ->
      if (text.startsWith("e: ")) {
        DiagnosticMessage(DiagnosticSeverity.ERROR, text.substring(3).trim())
      } else if (text.startsWith("w: ")) {
        DiagnosticMessage(DiagnosticSeverity.WARNING, text.substring(3).trim())
      } else if (text.startsWith("i: ")) {
        DiagnosticMessage(DiagnosticSeverity.INFO, text.substring(3).trim())
      } else {
        null
      }
    }
    .groupBy { it.severity }
    .mapValues { (_, messages) ->
      messages.map { it.message.lineSequence().map(String::cleanOutputLine).joinToString("\n") }
    }
    .let { parsed ->
      buildMap {
        putAll(parsed)
        DiagnosticSeverity.entries.forEach { severity -> getOrPut(severity, ::emptyList) }
      }
    }
}

private val FILE_PATH_REGEX = Regex("file://.*?/(?=[^/]+\\.kt)")

fun String.cleanOutputLine(): String = FILE_PATH_REGEX.replace(trimEnd(), "")

inline fun <reified T : Throwable> assertThrows(block: () -> Unit): ThrowableSubject {
  val throwable = assertFailsWith(T::class, block)
  return assertThat(throwable)
}

fun Sequence<String>.chunkedLinesBy(predicate: (String) -> Boolean): Sequence<String> {
  return chunkedBy(predicate).map { it.joinToString("\n") }
}

fun <T> Sequence<T>.chunkedBy(predicate: (T) -> Boolean): Sequence<List<T>> = sequence {
  val current = mutableListOf<T>()
  for (item in this@chunkedBy) {
    if (predicate(item) && current.isNotEmpty()) {
      yield(current.toList())
      current.clear()
    }
    current.add(item)
  }
  if (current.isNotEmpty()) {
    yield(current)
  }
}

fun Class<*>.allSupertypes(
  includeInterfaces: Boolean = true,
  includeSuperclasses: Boolean = true,
  includeSelf: Boolean = false,
): Set<Class<*>> {
  val supertypes = mutableSetOf<Class<*>>()
  allSupertypes(
    includeInterfaces = includeInterfaces,
    includeSuperclasses = includeSuperclasses,
    includeSelf = includeSelf,
    supertypes = supertypes,
  )
  return supertypes
}

private fun Class<*>.allSupertypes(
  includeInterfaces: Boolean,
  includeSuperclasses: Boolean,
  includeSelf: Boolean,
  supertypes: MutableSet<Class<*>>,
) {
  if (includeSelf) {
    supertypes += this
  }

  if (includeSuperclasses) {
    supertypes.addAll(generateSequence(superclass) { it.superclass })
  }

  if (includeInterfaces) {
    for (superinterface in interfaces) {
      val added = supertypes.add(superinterface)
      if (added) {
        superinterface.allSupertypes(
          includeInterfaces = true,
          includeSuperclasses = false,
          includeSelf = false,
          supertypes = supertypes,
        )
      }
    }
  }
}

fun captureStandardOut(block: () -> Unit): String {
  val originalOut = System.out
  val outputStream = ByteArrayOutputStream()
  val printStream = PrintStream(outputStream)

  return try {
    printStream.use {
      System.setOut(it)
      block()
      outputStream.toString().trim().ifBlank {
        throw AssertionError("No output was written to System.out")
      }
    }
  } finally {
    System.setOut(originalOut)
  }
}
