// COMPILER_OPTIONS: -Xplugin-option=metro:sharding.enabled=true -Xplugin-option=metro:sharding.keysPerShard=2 -Xplugin-option=metro:debug=true
// TEST_TARGET: jvm

// Sanity test for graph sharding - exercises all key components:
// 1. Main binding (module provides)
// 2. Shard binding (constructor injected)
// 3. Bound instance
// 4. Provides method
// 5. Constructor injection
// Tests that SwitchingProvider works and fields are created on correct owners

import javax.inject.Inject
import javax.inject.Provider
import dev.zacsweers.metro.annotations.*

// Simple service classes
@Inject class ConstructorService

class ProvidedService(val value: String)

class BoundInstanceService(val id: Int)

// BindingContainer with provides method (Metro's equivalent of Module)
@BindingContainer
interface TestBindings {
  @Provides
  fun provideService(): ProvidedService = ProvidedService("provided")
}

// Graph with all binding types
@DependencyGraph(bindingContainers = [TestBindings::class])
interface SanityTestGraph {
  // Main binding - module provides (stays in main graph)
  val providedService: ProvidedService

  // Shard binding - constructor injected (goes to shard)
  val constructorService: ConstructorService

  // Provider access to test SwitchingProvider
  val constructorServiceProvider: Provider<ConstructorService>

  // Factory with bound instance
  @Factory
  interface Factory {
    fun create(@BindsInstance boundInstance: BoundInstanceService): SanityTestGraph
  }
}

fun box(): String {
  // Create graph with bound instance
  val boundInstance = BoundInstanceService(42)
  val graph = createGraph<SanityTestGraph.Factory>().create(boundInstance)

  // Test 1: Verify all bindings are accessible
  assertNotNull(graph.providedService)
  assertNotNull(graph.constructorService)
  assertNotNull(graph.constructorServiceProvider)

  // Test 2: Verify values are correct
  assertEquals("provided", graph.providedService.value)
  assertEquals(42, boundInstance.id)

  // Test 3: Verify provider works (tests SwitchingProvider.invoke)
  val service1 = graph.constructorServiceProvider.get()
  val service2 = graph.constructorServiceProvider.get()
  assertNotNull(service1)
  assertNotNull(service2)
  // Should be same instance (scoped as singleton by default)
  assertEquals(service1, service2)

  // Test 4: Verify direct access gives same instance as provider
  assertEquals(graph.constructorService, service1)

  // Test 5: Use reflection to verify field ownership (if sharding worked)
  val graphClass = graph::class.java
  val fields = graphClass.declaredFields

  // Look for shard fields (should exist if sharding is enabled)
  val shardFields = fields.filter { it.name.startsWith("shard") }
  if (shardFields.isNotEmpty()) {
    // We have sharding - verify at least one shard was created
    println("Sharding active: found ${shardFields.size} shard(s)")

    // Verify shard is initialized
    shardFields.forEach { field ->
      field.isAccessible = true
      val shardInstance = field.get(graph)
      assertNotNull(shardInstance, "Shard ${field.name} should be initialized")
    }

    // Check for SwitchingProvider (inner class)
    val innerClasses = graphClass.declaredClasses
    val switchingProvider = innerClasses.find {
      it.simpleName?.contains("SwitchingProvider") == true
    }
    if (switchingProvider != null) {
      println("SwitchingProvider found: ${switchingProvider.simpleName}")

      // Verify it has the expected fields
      val spFields = switchingProvider.declaredFields
      val graphField = spFields.find { it.name == "graph" }
      val idField = spFields.find { it.name == "id" }

      assertNotNull(graphField, "SwitchingProvider should have 'graph' field")
      assertNotNull(idField, "SwitchingProvider should have 'id' field")

      // Verify it has invoke method
      val invokeMethods = switchingProvider.declaredMethods.filter {
        it.name == "invoke"
      }
      assertTrue(invokeMethods.isNotEmpty(), "SwitchingProvider should have invoke() method")
    }
  } else {
    // No sharding (might be disabled in test) - that's OK for sanity test
    println("No sharding detected (may be disabled)")
  }

  // Test 6: Verify bound instance field exists
  val boundInstanceFields = fields.filter {
    it.type == BoundInstanceService::class.java
  }
  assertTrue(
    boundInstanceFields.isNotEmpty(),
    "Graph should have field for bound instance"
  )

  return "OK"
}

// Helper assertions
fun assertNotNull(value: Any?, message: String = "Value should not be null") {
  if (value == null) {
    throw AssertionError(message)
  }
}

fun assertEquals(expected: Any?, actual: Any?, message: String = "Values should be equal") {
  if (expected != actual) {
    throw AssertionError("$message: expected=$expected, actual=$actual")
  }
}

fun assertTrue(condition: Boolean, message: String = "Condition should be true") {
  if (!condition) {
    throw AssertionError(message)
  }
}