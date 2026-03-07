// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.test.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.ElementsIntoSet
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.MapKey
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metro.createGraphFactory
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DependencyGraphProcessingTest {

  @Singleton
  @DependencyGraph
  interface ComplexDependenciesGraph {

    val repository: Repository
    val apiClient: ApiClient

    @Provides private fun provideFileSystem(): FileSystem = FileSystem()

    @Named("cache-dir-name") @Provides private fun provideCacheDirName(): String = "cache"

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(): ComplexDependenciesGraph
    }
  }

  @Test
  fun `complex dependencies setup`() {
    val graph = createGraphFactory<ComplexDependenciesGraph.Factory>().create()

    // Scoped bindings always use the same instance
    val apiClient = graph.apiClient
    assertSame(graph.apiClient, apiClient)

    // Calling repository creates a new repository each time
    val repository1 = graph.repository
    val repository2 = graph.repository
    assertNotSame(repository1, repository2)

    // Scoped dependencies use the same instance
    assertSame(repository1.apiClient, apiClient)
    assertSame(repository2.apiClient, apiClient)
  }

  @DependencyGraph
  abstract class ProviderTypesGraph {

    var callCount = 0

    abstract val counter: Counter

    @Provides private fun count(): Int = callCount++

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(): ProviderTypesGraph
    }

    @Inject
    class Counter(
      val scalar: Int,
      val providedValue: Provider<Int>,
      val lazyValue: Lazy<Int>,
      val providedLazies: Provider<Lazy<Int>>,
    )
  }

  @Test
  fun `providers on site targets`() {
    val graph = createGraph<ProvidersWithSiteTargetsGraph>()
    assertEquals(3, graph.count)
  }

  @DependencyGraph
  abstract class ProvidersWithSiteTargetsGraph {
    abstract val count: Int

    @get:Provides val countProvider: Int = 3
  }

  @Test
  fun `different provider types`() {
    val graph = createGraphFactory<ProviderTypesGraph.Factory>().create()
    val counter = graph.counter

    assertEquals(0, counter.scalar)
    assertEquals(1, counter.providedValue())
    assertEquals(2, counter.providedValue())
    assertEquals(3, counter.lazyValue.value)
    assertEquals(3, counter.lazyValue.value)
    val lazyValue = counter.providedLazies()
    assertEquals(4, lazyValue.value)
    assertEquals(4, lazyValue.value)
    val lazyValue2 = counter.providedLazies()
    assertEquals(5, lazyValue2.value)
    assertEquals(5, lazyValue2.value)
  }

  @DependencyGraph
  abstract class ProviderTypesAsAccessorsGraph {

    var counter = 0

    abstract val scalar: Int
    abstract val providedValue: Provider<Int>
    abstract val lazyValue: Lazy<Int>
    abstract val providedLazies: Provider<Lazy<Int>>

    @Provides private fun provideInt(): Int = counter++
  }

  @Test
  fun `different provider types as accessors`() {
    val graph = createGraph<ProviderTypesAsAccessorsGraph>()

    assertEquals(0, graph.scalar)
    assertEquals(1, graph.providedValue())
    assertEquals(2, graph.providedValue())
    val lazyValue = graph.lazyValue
    assertEquals(3, lazyValue.value)
    assertEquals(3, lazyValue.value)
    val providedLazyValue = graph.providedLazies()
    assertEquals(4, providedLazyValue.value)
    assertEquals(4, providedLazyValue.value)
    val providedLazyValue2 = graph.providedLazies()
    assertEquals(5, providedLazyValue2.value)
    assertEquals(5, providedLazyValue2.value)
  }

  @Test
  fun `simple graph dependencies`() {
    val stringGraph = createGraphFactory<StringGraph.Factory>().create("Hello, world!")

    val graph = createGraphFactory<GraphWithDependencies.Factory>().create(stringGraph)

    assertEquals("Hello, world!", graph.value())
  }

  @DependencyGraph
  interface GraphWithDependencies {

    fun value(): CharSequence

    @Provides private fun provideValue(string: String): CharSequence = string

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(@Includes stringGraph: StringGraph): GraphWithDependencies
    }
  }

  @Test
  fun `graph dependencies can be non-graphs`() {
    val graph =
      createGraphFactory<GraphWithNonGraphDependencies.Factory>()
        .create(
          intProvider = { 1 },
          longProvider =
            object : GraphWithNonGraphDependencies.LongProvider() {
              override val long: Long = 2L
            },
          doubleProvider = GraphWithNonGraphDependencies.DoubleProvider(3.0),
          stringProvider = GraphWithNonGraphDependencies.StringProvider("Hello, world!"),
        )

    assertEquals(1, graph.int)
    assertEquals(2L, graph.long)
    assertEquals(3.0, graph.double)
    assertEquals("Hello, world!", graph.charSequence)
    assertEquals("Hello, world!", graph.string)
  }

  @DependencyGraph
  interface GraphWithNonGraphDependencies {

    val int: Int
    val long: Long
    val double: Double
    val charSequence: CharSequence
    val string: String

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(
        @Includes intProvider: IntProvider,
        @Includes longProvider: LongProvider,
        @Includes doubleProvider: DoubleProvider,
        @Includes stringProvider: StringProvider,
      ): GraphWithNonGraphDependencies
    }

    fun interface IntProvider {
      fun provideInt(): Int
    }

    abstract class LongProvider {
      abstract val long: Long
    }

    class DoubleProvider(val double: Double)

    abstract class CharSequenceProvider(val charSequence: CharSequence)

    class StringProvider(charSequence: CharSequence) : CharSequenceProvider(charSequence) {
      fun provideString(): String = charSequence.toString()
    }
  }

  @Test
  fun `graph factories can inherit abstract functions from base types`() {
    val graph =
      createGraphFactory<GraphWithInheritingAbstractFunction.Factory>().create("Hello, world!")

    assertEquals("Hello, world!", graph.value)
  }

  interface BaseFactory<T> {
    fun create(@Provides value: String): T
  }

  @DependencyGraph
  interface GraphWithInheritingAbstractFunction {
    val value: String

    @DependencyGraph.Factory interface Factory : BaseFactory<GraphWithInheritingAbstractFunction>
  }

  @Test
  fun `graph factories should merge overlapping interfaces`() {
    val value = createGraphFactory<GraphCreatorWithMergeableInterfaces.Factory>().create(3).value

    assertEquals(value, 3)
  }

  @DependencyGraph
  interface GraphCreatorWithMergeableInterfaces {
    val value: Int

    interface BaseFactory1<T> {
      fun create(@Provides value: Int): T
    }

    interface BaseFactory2<T> {
      fun create(@Provides value: Int): T
    }

    @DependencyGraph.Factory
    interface Factory :
      BaseFactory1<GraphCreatorWithMergeableInterfaces>,
      BaseFactory2<GraphCreatorWithMergeableInterfaces>
  }

  @Test
  fun `graph factories should merge overlapping interfaces where only the abstract override has the bindsinstance`() {
    val value =
      createGraphFactory<
          GraphCreatorWithMergeableInterfacesWhereOnlyTheOverrideHasTheBindsInstance.Factory
        >()
        .create(3)
        .value

    assertEquals(value, 3)
  }

  // Also covers overrides with different return types
  @DependencyGraph
  interface GraphCreatorWithMergeableInterfacesWhereOnlyTheOverrideHasTheBindsInstance {
    val value: Int

    interface BaseFactory1<T> {
      fun create(value: Int): T
    }

    interface BaseFactory2<T> {
      fun create(value: Int): T
    }

    @DependencyGraph.Factory
    interface Factory :
      BaseFactory1<GraphCreatorWithMergeableInterfacesWhereOnlyTheOverrideHasTheBindsInstance>,
      BaseFactory2<GraphCreatorWithMergeableInterfacesWhereOnlyTheOverrideHasTheBindsInstance> {
      override fun create(
        @Provides value: Int
      ): GraphCreatorWithMergeableInterfacesWhereOnlyTheOverrideHasTheBindsInstance
    }
  }

  @Test
  fun `graph factories should understand partially-implemented supertypes`() {
    val factory =
      createGraphFactory<GraphCreatorWithIntermediateOverriddenDefaultFunctions.Factory>()
    val value1 = factory.create1().value

    assertEquals(value1, 0)

    val value2 = factory.create2(3).value

    assertEquals(value2, 3)
  }

  @DependencyGraph
  interface GraphCreatorWithIntermediateOverriddenDefaultFunctions {
    val value: Int

    interface BaseFactory1<T> {
      fun create1(): T
    }

    interface BaseFactory2<T> : BaseFactory1<T> {
      override fun create1(): T = create2(0)

      fun create2(@Provides value: Int): T
    }

    @DependencyGraph.Factory
    interface Factory : BaseFactory2<GraphCreatorWithIntermediateOverriddenDefaultFunctions>
  }

  @Test
  fun `bindsinstance params with same types but different qualifiers are ok`() {
    val factory = createGraphFactory<GraphWithDifferentBindsInstanceTypeQualifiers.Factory>()
    val graph = factory.create(1, 2, 3)

    assertEquals(graph.value1, 1)
    assertEquals(graph.value2, 2)
    assertEquals(graph.value3, 3)
  }

  @DependencyGraph
  interface GraphWithDifferentBindsInstanceTypeQualifiers {

    val value1: Int
    @Named("value2") val value2: Int
    @Named("value3") val value3: Int

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(
        @Provides value1: Int,
        @Provides @Named("value2") value2: Int,
        @Provides @Named("value3") value3: Int,
      ): GraphWithDifferentBindsInstanceTypeQualifiers
    }
  }

  @Test
  fun `basic assisted injection`() {
    val graph = createGraphFactory<AssistedInjectGraph.Factory>().create("Hello, world!")
    val factory1 = graph.factory
    val exampleClass1 = factory1.create(3)
    assertEquals("Hello, world!", exampleClass1.message)
    assertEquals(3, exampleClass1.intValue)

    val factory2 = graph.factory2
    val exampleClass2 = factory2.create(4)
    assertEquals("Hello, world!", exampleClass2.message)
    assertEquals(4, exampleClass2.intValue)
  }

  @DependencyGraph
  interface AssistedInjectGraph {
    val factory: ExampleClass.Factory
    val factory2: ExampleClass.Factory2

    @DependencyGraph.Factory
    interface Factory {
      fun create(@Provides message: String): AssistedInjectGraph
    }

    @AssistedInject
    class ExampleClass(@Assisted val intValue: Int, val message: String) {
      @AssistedFactory
      fun interface Factory {
        fun create(intValue: Int): ExampleClass
      }

      // Multiple factories are allowed
      @AssistedFactory
      fun interface Factory2 {
        fun create(intValue: Int): ExampleClass
      }
    }
  }

  @Test
  fun `assisted injection with generic factory supertype`() {
    val graph = createGraph<AssistedInjectGraphWithGenericFactorySupertype>()
    val factory = graph.factory
    val exampleClass = factory.create(2)
    assertEquals(2, exampleClass.intValue)
  }

  @DependencyGraph
  interface AssistedInjectGraphWithGenericFactorySupertype {
    val factory: ExampleClass.Factory

    @AssistedInject
    class ExampleClass(@Assisted val intValue: Int) {
      fun interface BaseFactory<T> {
        fun create(intValue: Int): T
      }

      @AssistedFactory
      fun interface Factory : BaseFactory<ExampleClass> {
        override fun create(intValue: Int): ExampleClass
      }
    }
  }

  @Test
  fun `assisted injection - diamond inheritance`() {
    val graph = createGraph<AssistedInjectGraphDiamondInheritance>()
    val factory = graph.factory
    val exampleClass = factory.create(2)
    assertEquals(2, exampleClass.intValue)
  }

  @DependencyGraph
  interface AssistedInjectGraphDiamondInheritance {
    val factory: ExampleClass.Factory

    @AssistedInject
    class ExampleClass(@Assisted val intValue: Int) {
      fun interface GrandParentBaseFactory<T> {
        fun create(intValue: Int): T
      }

      fun interface BaseFactory<T> : GrandParentBaseFactory<T> {
        override fun create(intValue: Int): T
      }

      fun interface BaseFactory2<T> : GrandParentBaseFactory<T> {
        override fun create(intValue: Int): T
      }

      @AssistedFactory fun interface Factory : BaseFactory<ExampleClass>, BaseFactory2<ExampleClass>
    }
  }

  @Test
  fun `multibindings - simple int set with one value`() {
    val graph = createGraph<MultibindingGraphWithSingleIntSet>()
    assertEquals(setOf(1), graph.ints)

    // Each call yields a new set instance
    assertNotSame(graph.ints, graph.ints)

    // Ensure we return immutable types
    // TODO on the JVM we get Collections.singleton() but other platforms just us HashSet. Maybe we
    //  should use our own? Or use buildSet
    if (isJvm()) {
      assertFailsWith<UnsupportedOperationException> { (graph.ints as MutableSet<Int>).clear() }
    }
  }

  @DependencyGraph
  interface MultibindingGraphWithSingleIntSet {
    val ints: Set<Int>

    @Provides @IntoSet private fun provideInt1(): Int = 1
  }

  @Test
  fun `multibindings - simple empty int set`() {
    val graph = createGraph<MultibindingGraphWithEmptySet>()
    assertTrue(graph.ints.isEmpty())

    // Each call in this case is actually the same instance
    assertSame(emptySet(), graph.ints)
  }

  @DependencyGraph
  interface MultibindingGraphWithEmptySet {
    @Multibinds(allowEmpty = true) val ints: Set<Int>
  }

  @Test
  fun `multibindings - int set with multiple values`() {
    val graph = createGraph<MultibindingGraphWithIntSet>()
    assertEquals(setOf(1, 2), graph.ints)

    // Each call yields a new set instance
    assertNotSame(graph.ints, graph.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> { (graph.ints as MutableSet<Int>).clear() }
  }

  @DependencyGraph
  interface MultibindingGraphWithIntSet {
    val ints: Set<Int>

    @Provides @IntoSet private fun provideInt1(): Int = 1

    @Provides @IntoSet private fun provideInt2(): Int = 2
  }

  @Test
  fun `multibindings - int set with elements into set`() {
    val graph = createGraph<MultibindingGraphWithElementsIntoSet>()
    assertEquals(setOf(1, 2, 3), graph.ints)

    // Each call yields a new set instance
    assertNotSame(graph.ints, graph.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> { (graph.ints as MutableSet<Int>).clear() }
  }

  @DependencyGraph
  interface MultibindingGraphWithElementsIntoSet {
    val ints: Set<Int>

    @Provides @ElementsIntoSet private fun provideInts(): Set<Int> = setOf(1, 2, 3)
  }

  @Test
  fun `multibindings - int set with scoped elements into set`() {
    val graph = createGraph<MultibindingGraphWithScopedElementsIntoSet>()
    assertEquals(setOf(0), graph.ints)

    // Subsequent calls have the same output
    assertEquals(setOf(0), graph.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> { (graph.ints as MutableSet<Int>).clear() }
  }

  @Singleton
  @DependencyGraph
  abstract class MultibindingGraphWithScopedElementsIntoSet {
    private var count = 0

    abstract val ints: Set<Int>

    @Provides
    @ElementsIntoSet
    @Singleton
    private fun provideInts(): Set<Int> = buildSet { add(count++) }
  }

  @Test
  fun `multibindings - int set with mix of scoped elements into set and individual providers`() {
    val graph = createGraph<MultibindingGraphWithMixOfScopedElementsIntoSetAndIndividualProviders>()
    assertEquals(setOf(2, 6, 10), graph.ints)
    assertEquals(setOf(3, 7, 10), graph.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> { (graph.ints as MutableSet<Int>).clear() }
  }

  @Singleton
  @DependencyGraph
  abstract class MultibindingGraphWithMixOfScopedElementsIntoSetAndIndividualProviders {
    private var count = 10
    private var unscopedCount1 = 1
    private var unscopedCount2 = 1

    abstract val ints: Set<Int>

    @Provides @IntoSet private fun provideInt1(): Int = 1 + unscopedCount1++

    @Provides @IntoSet private fun provideInt5(): Int = 5 + unscopedCount2++

    @Provides
    @ElementsIntoSet
    @Singleton
    private fun provideInts(): Set<Int> = buildSet { add(count++) }
  }

  @Test
  fun `multibindings - simple int map with one value`() {
    val graph = createGraph<MultibindingGraphWithSingleIntMap>()
    assertEquals(mapOf(1 to 1), graph.ints)

    // Each call yields a new map instance
    assertNotSame(graph.ints, graph.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> { (graph.ints as MutableMap<Int, Int>).clear() }
  }

  @DependencyGraph
  interface MultibindingGraphWithSingleIntMap {
    val ints: Map<Int, Int>

    @Provides @IntoMap @IntKey(1) private fun provideInt1(): Int = 1
  }

  @Test
  fun `multibindings - simple empty int map`() {
    val graph = createGraph<MultibindingGraphWithEmptyMap>()
    assertTrue(graph.ints.isEmpty())

    // Each call in this case is actually the same instance
    assertSame(emptyMap(), graph.ints)
  }

  @DependencyGraph
  interface MultibindingGraphWithEmptyMap {
    @Multibinds(allowEmpty = true) val ints: Map<Int, Int>
  }

  @Test
  fun `multibindings - int map with multiple values`() {
    val graph = createGraph<MultibindingGraphWithIntMap>()
    assertEquals(mapOf(1 to 1, 2 to 2), graph.ints)

    // Each call yields a new map instance
    assertNotSame(graph.ints, graph.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> { (graph.ints as MutableMap<Int, Int>).clear() }
  }

  @DependencyGraph
  interface MultibindingGraphWithIntMap {
    val ints: Map<Int, Int>

    @Provides @IntoMap @IntKey(1) private fun provideInt1(): Int = 1

    @Provides @IntoMap @IntKey(2) private fun provideInt2(): Int = 2
  }

  @Test
  fun `multibindings - map with scoped dependencies`() {
    val graph = createGraph<MultibindingGraphWithWithScopedMapDeps>()
    assertEquals(mapOf(1 to 0, 2 to 0), graph.ints)
    assertEquals(mapOf(1 to 0, 2 to 1), graph.ints)
    assertEquals(mapOf(1 to 0, 2 to 2), graph.ints)
  }

  @Singleton
  @DependencyGraph
  abstract class MultibindingGraphWithWithScopedMapDeps {
    private var scopedCount = 0
    private var unscopedCount = 0

    abstract val ints: Map<Int, Int>

    @Provides @Singleton @IntoMap @IntKey(1) private fun provideScopedInt(): Int = scopedCount++

    @Provides @IntoMap @IntKey(2) private fun provideUnscopedInt(): Int = unscopedCount++
  }

  @Test
  fun `multibindings - string map with multiple values`() {
    val graph = createGraph<MultibindingGraphWithStringMap>()
    assertEquals(mapOf("1" to 1, "2" to 2), graph.ints)

    // Each call yields a new map instance
    assertNotSame(graph.ints, graph.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> {
      (graph.ints as MutableMap<String, Int>).clear()
    }
  }

  @DependencyGraph
  interface MultibindingGraphWithStringMap {
    val ints: Map<String, Int>

    @Provides @IntoMap @StringKey("1") private fun provideInt1(): Int = 1

    @Provides @IntoMap @StringKey("2") private fun provideInt2(): Int = 2
  }

  @Test
  fun `multibindings - kclass map with multiple values`() {
    val graph = createGraph<MultibindingGraphWithKClassMap>()
    assertEquals<Map<KClass<*>, Int>>(mapOf(Int::class to 1, Float::class to 2), graph.ints)

    // Each call yields a new map instance
    assertNotSame(graph.ints, graph.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> {
      (graph.ints as MutableMap<KClass<*>, Int>).clear()
    }
  }

  @Singleton
  @DependencyGraph
  interface MultibindingGraphWithKClassMap {
    val ints: Map<KClass<*>, Int>

    @Provides @IntoMap @ClassKey(Int::class) fun provideMapInt1(): Int = 1

    @Provides @IntoMap @Singleton @ClassKey(Float::class) fun provideMapInt2(): Int = 2
  }

  @Test
  fun `multibindings - misc other map key types`() {
    val graph = createGraph<MultibindingGraphWithMultipleOtherMapKeyTypes>()
    assertEquals(mapOf(Seasoning.SPICY to 1, Seasoning.REGULAR to 2), graph.seasoningAmounts)
    assertEquals(mapOf(1 to 1, 2 to 2), graph.ints)
    assertEquals(mapOf("1" to 1, "2" to 2), graph.strings)
    // TODO WASM annotation classes don't implement equals correctly
    if (!isWasm()) {
      assertEquals(
        mapOf(
          MultibindingGraphWithMultipleOtherMapKeyTypes.WrappedSeasoningKey(Seasoning.SPICY) to 1,
          MultibindingGraphWithMultipleOtherMapKeyTypes.WrappedSeasoningKey(Seasoning.REGULAR) to 2,
        ),
        graph.wrappedSeasoningAmounts,
      )
    }
  }

  @DependencyGraph
  interface MultibindingGraphWithMultipleOtherMapKeyTypes {
    val seasoningAmounts: Map<Seasoning, Int>

    @Provides @IntoMap @SeasoningKey(Seasoning.SPICY) private fun provideSpicySeasoning(): Int = 1

    @Provides
    @IntoMap
    @SeasoningKey(Seasoning.REGULAR)
    private fun provideRegularSeasoning(): Int = 2

    @MapKey annotation class SeasoningKey(val value: Seasoning)

    val ints: Map<Int, Int>

    @Provides @IntoMap @IntKey(1) private fun provideIntKey1(): Int = 1

    @Provides @IntoMap @IntKey(2) private fun provideIntKey2(): Int = 2

    val strings: Map<String, Int>

    @Provides @IntoMap @StringKey("1") private fun provideStringKey1(): Int = 1

    @Provides @IntoMap @StringKey("2") private fun provideStringKey2(): Int = 2

    val wrappedSeasoningAmounts: Map<WrappedSeasoningKey, Int>

    @Provides
    @IntoMap
    @WrappedSeasoningKey(Seasoning.SPICY)
    private fun provideWrappedSpicySeasoning(): Int = 1

    @Provides
    @IntoMap
    @WrappedSeasoningKey(Seasoning.REGULAR)
    private fun provideWrappedRegularSeasoning(): Int = 2

    @MapKey(unwrapValue = false) annotation class WrappedSeasoningKey(val value: Seasoning)
  }

  @Test
  fun `optional dependencies - provider - found dependency uses it`() {
    val graph = createGraph<MessageProviderWithCharSequenceProvider>()
    assertEquals("Found", graph.message)
  }

  @DependencyGraph
  interface MessageProviderWithCharSequenceProvider : BaseMessageProviderWithDefault {
    @Provides private fun provideCharSequence(): CharSequence = "Found"
  }

  @Test
  fun `optional dependencies - provider - absent dependency uses default`() {
    val graph = createGraph<MessageProviderWithoutCharSequenceProvider>()
    assertEquals("Not found", graph.message)
  }

  @DependencyGraph
  interface MessageProviderWithoutCharSequenceProvider : BaseMessageProviderWithDefault

  interface BaseMessageProviderWithDefault {
    val message: String

    @Provides
    private fun provideMessage(input: CharSequence = "Not found"): String = input.toString()
  }

  @Test
  fun `optional dependencies - provider - default values with back references work`() {
    val graph = createGraph<OptionalDependenciesProviderWithBackReferencingDefault>()
    assertEquals("Not found: 3", graph.message)
  }

  @DependencyGraph
  interface OptionalDependenciesProviderWithBackReferencingDefault {
    val message: String

    @Provides private fun provideInt(): Int = 3

    @Provides
    private fun provideMessage(
      intValue: Int,
      input: CharSequence = "Not found: $intValue",
    ): String = input.toString()
  }

  @Test
  fun `optional dependencies - provider - default values with many back references`() {
    val graph = createGraph<OptionalDependenciesProviderWithManyDefaultBackReferences>()
    assertEquals("7", graph.message)
  }

  @DependencyGraph
  interface OptionalDependenciesProviderWithManyDefaultBackReferences {
    val message: String

    @Provides private fun provideInt(): Int = 3

    @Provides
    private fun provideMessage(
      int: Int = 2,
      long: Long = 4,
      input: CharSequence = (int + long).toString(),
    ): String {
      return input.toString()
    }
  }

  // TODO this needs more work to support
  //  @Test
  //  fun `optional dependencies - provider - default values with complex functions with back
  // refs`() {
  //    val graph = createGraph<OptionalDependenciesProviderWithFunctionBackReferences>()
  //    assertEquals("7", graph.message)
  //  }
  //
  //  @Graph
  //  interface OptionalDependenciesProviderWithFunctionBackReferences {
  //    val message: String
  //
  //    @Provides private fun provideInt(): Int = 3
  //
  //    @Provides
  //    private fun provideMessage(
  //      int: Int = 2,
  //      long: Long = 4,
  //      input: CharSequence = run {
  //        (int + long).toString()
  //      }
  //    ): String {
  //      return input.toString()
  //    }
  //  }

  @Test
  fun `optional dependencies - provider - default values from private references`() {
    val graph = createGraph<OptionalDependenciesProviderWithPrivateReferences>()
    assertEquals("Default message!", graph.message)
  }

  @DependencyGraph
  interface OptionalDependenciesProviderWithPrivateReferences {
    val message: String

    @Provides
    private fun provideMessage(message: CharSequence = DEFAULT_MESSAGE): String = message.toString()

    private companion object {
      private const val DEFAULT_MESSAGE = "Default message!"
    }
  }

  @Test
  fun `optional dependencies - provider - default values from instance references`() {
    val graph = createGraph<OptionalDependenciesProviderWithInstanceReferences>()
    assertEquals("Default message!", graph.defaultMessage)
  }

  @DependencyGraph
  interface OptionalDependenciesProviderWithInstanceReferences {
    val message: String

    val defaultMessage: String
      get() = DEFAULT_MESSAGE

    @Provides
    private fun provideMessage(message: CharSequence = defaultMessage): String = message.toString()

    private companion object {
      private const val DEFAULT_MESSAGE = "Default message!"
    }
  }

  @Test
  fun `optional dependencies - class - found dependency uses it`() {
    val graph = createGraph<MessageClassWithCharSequenceProvider>()
    assertEquals("Found", graph.message)
  }

  @DependencyGraph
  interface MessageClassWithCharSequenceProvider : BaseMessageClassWithDefault {
    @Provides private fun provideMessage(): String = "Found"
  }

  @Test
  fun `optional dependencies - class - absent dependency uses default`() {
    val graph = createGraph<MessageClassWithoutCharSequenceProvider>()
    assertEquals("Not found", graph.message)
  }

  @DependencyGraph interface MessageClassWithoutCharSequenceProvider : BaseMessageClassWithDefault

  interface BaseMessageClassWithDefault {
    val messageClass: MessageClass
    val message
      get() = messageClass.message

    @Inject class MessageClass(val message: String = "Not found")
  }

  @Test
  fun `optional dependencies - class - default values with back references work`() {
    val graph = createGraph<OptionalDependenciesClassWithBackReferencingDefault>()
    assertEquals("Not found: 3", graph.message)
  }

  @DependencyGraph
  interface OptionalDependenciesClassWithBackReferencingDefault {
    val messageClass: MessageClass
    val message: String
      get() = messageClass.message

    @Provides private fun provideInt(): Int = 3

    @Inject class MessageClass(intValue: Int, val message: String = "Not found: $intValue")
  }

  @Test
  fun `optional dependencies - class - default values with many back references`() {
    val graph = createGraph<OptionalDependenciesClassWithManyDefaultBackReferences>()
    assertEquals("7", graph.message)
  }

  @DependencyGraph
  interface OptionalDependenciesClassWithManyDefaultBackReferences {
    val messageClass: MessageClass
    val message: String
      get() = messageClass.message

    @Provides private fun provideInt(): Int = 3

    @Inject
    class MessageClass(int: Int = 2, long: Long = 4, val message: String = (int + long).toString())
  }

  @Test
  fun `optional dependencies - class - default values from private references`() {
    val graph = createGraph<OptionalDependenciesClassWithPrivateReferences>()
    assertEquals("Default message!", graph.message)
  }

  @DependencyGraph
  interface OptionalDependenciesClassWithPrivateReferences {
    val messageClass: MessageClass
    val message
      get() = messageClass.message

    @Inject
    class MessageClass(val message: String = DEFAULT_MESSAGE) {
      private companion object {
        private const val DEFAULT_MESSAGE = "Default message!"
      }
    }
  }

  @Test
  fun `graphs can use multiple scopes`() {
    // This graph supports multiple scopes and still respects their scoping requirementts
    val graph = createGraph<GraphWithMultipleScopes>()
    assertEquals(0, graph.intValue)
    assertEquals(0, graph.intValue)
    assertEquals(0L, graph.longValue)
    assertEquals(0L, graph.longValue)
  }

  @Singleton
  @DependencyGraph(AppScope::class)
  abstract class GraphWithMultipleScopes {
    private var intCounter = 0
    private var longCounter = 0L

    abstract val intValue: Int
    abstract val longValue: Long

    @Provides @Singleton private fun provideInt(): Int = intCounter++

    @Provides @SingleIn(AppScope::class) fun provideLong(): Long = longCounter++
  }

  @Test
  fun `graphs can inherit multiple scopes from supertypes`() {
    // This graph supports multiple scopes and still respects their scoping requirementts
    val graph = createGraph<GraphWithInheritedScopes>()
    assertEquals(0, graph.intValue)
    assertEquals(0, graph.intValue)
    assertEquals(0L, graph.longValue)
    assertEquals(0L, graph.longValue)
  }

  @Singleton interface SingletonBase

  @SingleIn(AppScope::class) interface AppScopeBase

  @DependencyGraph
  abstract class GraphWithInheritedScopes : SingletonBase, AppScopeBase {

    private var intCounter = 0
    private var longCounter = 0L

    abstract val intValue: Int
    abstract val longValue: Long

    @Provides @Singleton private fun provideInt(): Int = intCounter++

    @Provides @SingleIn(AppScope::class) fun provideLong(): Long = longCounter++
  }

  @Test
  fun `binds - properties`() {
    val graph = createGraph<GraphWithBindsProperties>()
    assertEquals(3, graph.number)
  }

  @DependencyGraph
  interface GraphWithBindsProperties {
    val number: Number

    @Provides
    val provideInt: Int
      get() = 3

    @Binds val Int.provideNumber: Number
  }

  @Test
  fun `binds - functions`() {
    val graph = createGraph<GraphWithBindsFunctions>()
    assertEquals(3, graph.number)
  }

  @DependencyGraph
  interface GraphWithBindsFunctions {
    val number: Number

    @Provides private fun provideInt(): Int = 3

    @Binds fun Int.provideNumber(): Number
  }

  @Test
  fun `binds - mix of functions and property`() {
    val graph = createGraph<BindsWithMixOfFunctionsAndProperties>()
    assertEquals(graph.string, graph.charSequence)
  }

  @DependencyGraph
  interface BindsWithMixOfFunctionsAndProperties {
    val string: String
    val charSequence: CharSequence

    @get:Binds val String.binds: CharSequence

    @Provides private fun provideValue(): String = "Hello, world!"
  }

  @Test
  fun `graph instances can be requested`() {
    val graph = createGraph<SelfRequestingGraph>()
    assertSame(graph, graph.self)
  }

  @DependencyGraph
  interface SelfRequestingGraph {
    val self: SelfRequestingGraph
  }

  // Regression test for https://github.com/ZacSweers/metro/issues/342
  @Test
  fun `assisted injection with scoped dependencies`() {
    val graph = createGraph<AssistedInjectGraphWithScopedDependencies>()
    val exampleClass1 = graph.factory.create(0)
    val exampleClass2 = graph.factory.create(1)
    assertSame(exampleClass1.singleton, exampleClass2.singleton)
  }

  @SingleIn(AppScope::class)
  @DependencyGraph
  interface AssistedInjectGraphWithScopedDependencies {
    val factory: ExampleClass.Factory

    @Inject @SingleIn(AppScope::class) class ExampleSingleton

    @AssistedInject
    class ExampleClass(@Assisted val intValue: Int, val singleton: ExampleSingleton) {
      @AssistedFactory
      fun interface Factory {
        fun create(intValue: Int): ExampleClass
      }
    }
  }

  // Regression test for https://github.com/ZacSweers/metro/issues/312
  @Test
  fun `where clauses work`() {
    val graph = createGraph<WhereClausesGraph>()
    assertNotNull(graph.manager)
  }

  @Inject class LocationAvailabilityManager<F> where F : CharSequence

  @DependencyGraph
  interface WhereClausesGraph {
    val manager: LocationAvailabilityManager<String>

    @Provides fun provideString(): String = "Hello"
  }

  // Regression test for https://github.com/ZacSweers/metro/issues/498
  @Test
  fun `type parameter with bounds`() {
    val graph = createGraph<TypeParameterBoundsGraph>()
    assertNotNull(graph.example)
  }

  @Inject class ExampleWithBoundTypeParameter<T : Any>

  @DependencyGraph
  interface TypeParameterBoundsGraph {
    val example: ExampleWithBoundTypeParameter<String>

    @Provides fun provideString(): String = "Hello"
  }

  enum class Seasoning {
    SPICY,
    REGULAR,
  }

  @Inject
  @Singleton
  class Cache(
    val fileSystem: FileSystem,
    @Named("cache-dir-name") val cacheDirName: Provider<String>,
  ) {
    val cacheDir = cacheDirName().toPath()
  }

  @Inject @Singleton class HttpClient(val cache: Cache)

  @Inject @Singleton class ApiClient(val httpClient: Lazy<HttpClient>)

  @Inject class Repository(val apiClient: ApiClient)

  @DependencyGraph
  interface StringGraph {

    val string: String

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(@Provides string: String): StringGraph
    }
  }
}

class FileSystem

class Path

fun String.toPath(): Path = Path()
