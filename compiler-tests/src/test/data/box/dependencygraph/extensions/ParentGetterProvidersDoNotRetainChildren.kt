// METRO_JVM_ONLY
// ENABLE_GRAPH_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 2

import java.lang.reflect.Modifier
import java.util.IdentityHashMap

object Constructions {
  var count = 0
}

@Inject class Dependency {
  init {
    Constructions.count++
  }
}

@Inject class Shared(val dependency: Dependency)

@SingleIn(AppScope::class) @Inject class Singleton

@AssistedInject
class Product(@Assisted val name: String, val shared: Shared) {
  @AssistedFactory
  fun interface Factory {
    fun create(name: String): Product
  }
}

@Inject class Consumer(val shared: () -> Shared)

@BindingContainer
object StringBindings {
  @Provides @IntoSet fun string(): String {
    Constructions.count++
    return "parent"
  }
}

@DependencyGraph(AppScope::class, bindingContainers = [StringBindings::class])
interface AppGraph {
  val shared: Shared
  val strings: Set<String>
  val singleton: Singleton
  val child: ChildGraph
}

@GraphExtension
interface ChildGraph {
  val shared: Provider<Shared>
  val function: () -> Shared
  val consumer: Consumer
  val factory: Product.Factory
  val strings: Provider<Set<String>>
  val singleton: Provider<Singleton>
  val grandchild: GrandchildGraph
}

@GraphExtension
interface GrandchildGraph {
  val shared: Provider<Shared>
  val function: () -> Shared
  val consumer: Consumer
  val factory: Product.Factory
  val strings: Provider<Set<String>>
}

// Inspect generated fields so the regression doesn't depend on garbage collection.
fun assertDoesNotRetain(value: Any, vararg children: Any) {
  val visited = IdentityHashMap<Any, Boolean>()
  val pending = ArrayDeque<Pair<Any, String>>()
  pending.add(value to value.javaClass.name)
  val loader = children.first().javaClass.classLoader
  while (pending.isNotEmpty()) {
    val (current, path) = pending.removeFirst()
    assertFalse(children.any { it === current }, "Retained child through $path")
    if (visited.put(current, true) != null) {
      continue
    }
    var type: Class<*>? = current.javaClass
    while (type != null) {
      val isGenerated = type.classLoader === loader
      val isMetroRuntime = type.name.startsWith("dev.zacsweers.metro.")
      if (!isGenerated && !isMetroRuntime) {
        break
      }
      for (field in type.declaredFields) {
        val isReference = !Modifier.isStatic(field.modifiers) && !field.type.isPrimitive
        if (isReference) {
          field.isAccessible = true
          val reference = field.get(current)
          if (reference != null) {
            pending.add(reference to "$path.${field.name}")
          }
        }
      }
      type = type.superclass
    }
  }
}

fun box(): String {
  Constructions.count = 0
  val graph = createGraph<AppGraph>()
  val child = graph.child
  val grandchild = child.grandchild
  val providers = listOf(child.shared, grandchild.shared)
  val functions = listOf(child.function, grandchild.function)
  val factories = listOf(child.factory, grandchild.factory)
  val collections = listOf(child.strings, grandchild.strings)
  val consumers = listOf(child.consumer, grandchild.consumer)
  val singleton = child.singleton
  assertEquals(0, Constructions.count)

  for (value in providers + functions + factories + collections + consumers + singleton) {
    assertDoesNotRetain(value, child, grandchild)
  }

  for (provider in providers) {
    val before = Constructions.count
    val first = provider()
    val second = provider()
    assertNotSame(first, second)
    assertNotSame(first.dependency, second.dependency)
    assertEquals(before + 2, Constructions.count)
  }
  for (function in functions + consumers.map { it.shared }) {
    val before = Constructions.count
    assertNotSame(function(), function())
    assertEquals(before + 2, Constructions.count)
  }
  for (factory in factories) {
    val before = Constructions.count
    val first = factory.create("first")
    val second = factory.create("second")
    assertEquals("first", first.name)
    assertEquals("second", second.name)
    assertNotSame(first.shared, second.shared)
    assertEquals(before + 2, Constructions.count)
  }
  for (collection in collections) {
    val before = Constructions.count
    val first = collection()
    val second = collection()
    assertEquals(setOf("parent"), first)
    assertEquals(setOf("parent"), second)
    assertNotSame(first, second)
    assertEquals(before + 2, Constructions.count)
  }
  assertSame(graph.singleton, singleton())
  assertSame(singleton(), singleton())
  return "OK"
}
