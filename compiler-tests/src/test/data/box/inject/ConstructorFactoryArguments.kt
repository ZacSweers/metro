// METRO_JVM_ONLY
// GENERATE_CLASSES_IN_IR: false
// MODULE: lib
// COMPANION_MODE: COMPANION_OBJECT
// FILE: Library.kt

@Inject
class NoArguments {
  fun call(): String = "Hello, world!"
}

class ConstructorAnnotation @Inject constructor(private val value: String) {
  fun call(): String = value
}

class GenericConstructor<T> @Inject constructor(private val value: T) {
  fun call(): T = value
}

@Inject
class ClassAnnotation(private val value: String) {
  fun call(): String = value
}

// MODULE: main(lib)
// FILE: Main.kt

@Suppress("DEPRECATION_ERROR")
fun box(): String {
  val text = "Hello, world!"
  assertEquals(text, ConstructorAnnotation.MetroFactory.Companion.create(Provider { text }).invoke().call())
  assertEquals(text, GenericConstructor.MetroFactory.Companion.create(Provider { text }).invoke().call())
  assertEquals(text, ClassAnnotation.MetroFactory.Companion.create(Provider { text }).invoke().call())

  // The zero-argument factory is shared. Each call still produces a fresh instance.
  val factory = NoArguments.MetroFactory.create()
  assertSame(factory, NoArguments.MetroFactory.create())
  assertNotSame(NoArguments.MetroFactory.newInstance(), NoArguments.MetroFactory.newInstance())
  assertEquals(text, factory.invoke().call())
  return "OK"
}
