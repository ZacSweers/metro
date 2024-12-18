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
package dev.zacsweers.lattice.test.integration

import dev.zacsweers.lattice.Provider
import dev.zacsweers.lattice.annotations.Assisted
import dev.zacsweers.lattice.annotations.AssistedFactory
import dev.zacsweers.lattice.annotations.AssistedInject
import dev.zacsweers.lattice.annotations.BindsInstance
import dev.zacsweers.lattice.annotations.Component
import dev.zacsweers.lattice.annotations.Inject
import dev.zacsweers.lattice.annotations.Named
import dev.zacsweers.lattice.annotations.Provides
import dev.zacsweers.lattice.annotations.Singleton
import dev.zacsweers.lattice.annotations.multibindings.ClassKey
import dev.zacsweers.lattice.annotations.multibindings.ElementsIntoSet
import dev.zacsweers.lattice.annotations.multibindings.IntKey
import dev.zacsweers.lattice.annotations.multibindings.IntoMap
import dev.zacsweers.lattice.annotations.multibindings.IntoSet
import dev.zacsweers.lattice.annotations.multibindings.LongKey
import dev.zacsweers.lattice.annotations.multibindings.MapKey
import dev.zacsweers.lattice.annotations.multibindings.Multibinds
import dev.zacsweers.lattice.annotations.multibindings.StringKey
import dev.zacsweers.lattice.createComponent
import dev.zacsweers.lattice.createComponentFactory
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class ComponentProcessingTest {

  @Singleton
  @Component
  interface ComplexDependenciesComponent {

    val repository: Repository
    val apiClient: ApiClient

    @Provides private fun provideFileSystem(): FileSystem = FakeFileSystem()

    @Named("cache-dir-name") @Provides private fun provideCacheDirName(): String = "cache"

    @Component.Factory
    fun interface Factory {
      fun create(): ComplexDependenciesComponent
    }
  }

  @Test
  fun `complex dependencies setup`() {
    val component = createComponentFactory<ComplexDependenciesComponent.Factory>().create()

    // Scoped bindings always use the same instance
    val apiClient = component.apiClient
    assertSame(component.apiClient, apiClient)

    // Calling repository creates a new repository each time
    val repository1 = component.repository
    val repository2 = component.repository
    assertNotSame(repository1, repository2)

    // Scoped dependencies use the same instance
    assertSame(repository1.apiClient, apiClient)
    assertSame(repository2.apiClient, apiClient)
  }

  @Component
  abstract class ProviderTypesComponent {

    var callCount = 0

    abstract val counter: Counter

    @Provides private fun count(): Int = callCount++

    @Component.Factory
    fun interface Factory {
      fun create(): ProviderTypesComponent
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
  fun `different provider types`() {
    val component = createComponentFactory<ProviderTypesComponent.Factory>().create()
    val counter = component.counter

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

  @Component
  abstract class ProviderTypesAsAccessorsComponent {

    var counter = 0

    abstract val scalar: Int
    abstract val providedValue: Provider<Int>
    abstract val lazyValue: Lazy<Int>
    abstract val providedLazies: Provider<Lazy<Int>>

    @Provides private fun provideInt(): Int = counter++
  }

  @Test
  fun `different provider types as accessors`() {
    val component = createComponent<ProviderTypesAsAccessorsComponent>()

    assertEquals(0, component.scalar)
    assertEquals(1, component.providedValue())
    assertEquals(2, component.providedValue())
    val lazyValue = component.lazyValue
    assertEquals(3, lazyValue.value)
    assertEquals(3, lazyValue.value)
    val providedLazyValue = component.providedLazies()
    assertEquals(4, providedLazyValue.value)
    assertEquals(4, providedLazyValue.value)
    val providedLazyValue2 = component.providedLazies()
    assertEquals(5, providedLazyValue2.value)
    assertEquals(5, providedLazyValue2.value)
  }

  @Test
  fun `simple component dependencies`() {
    val stringComponent = createComponentFactory<StringComponent.Factory>().create("Hello, world!")

    val component =
      createComponentFactory<ComponentWithDependencies.Factory>().create(stringComponent)

    assertEquals("Hello, world!", component.value())
  }

  @Component
  interface ComponentWithDependencies {

    fun value(): CharSequence

    @Provides private fun provideValue(string: String): CharSequence = string

    @Component.Factory
    fun interface Factory {
      fun create(stringComponent: StringComponent): ComponentWithDependencies
    }
  }

  @Test
  fun `component factories can inherit abstract functions from base types`() {
    val component =
      createComponentFactory<ComponentWithInheritingAbstractFunction.Factory>()
        .create("Hello, world!")

    assertEquals("Hello, world!", component.value)
  }

  interface BaseFactory<T> {
    fun create(@BindsInstance value: String): T
  }

  @Component
  interface ComponentWithInheritingAbstractFunction {
    val value: String

    @Component.Factory interface Factory : BaseFactory<ComponentWithInheritingAbstractFunction>
  }

  @Test
  fun `component factories should merge overlapping interfaces`() {
    val value =
      createComponentFactory<ComponentCreatorWithMergeableInterfaces.Factory>().create(3).value

    assertEquals(value, 3)
  }

  @Component
  interface ComponentCreatorWithMergeableInterfaces {
    val value: Int

    interface BaseFactory1<T> {
      fun create(@BindsInstance value: Int): T
    }

    interface BaseFactory2<T> {
      fun create(@BindsInstance value: Int): T
    }

    @Component.Factory
    interface Factory :
      BaseFactory1<ComponentCreatorWithMergeableInterfaces>,
      BaseFactory2<ComponentCreatorWithMergeableInterfaces>
  }

  @Test
  fun `component factories should merge overlapping interfaces where only the abstract override has the bindsinstance`() {
    val value =
      createComponentFactory<
          ComponentCreatorWithMergeableInterfacesWhereOnlyTheOverrideHasTheBindsInstance.Factory
        >()
        .create(3)
        .value

    assertEquals(value, 3)
  }

  // Also covers overrides with different return types
  @Component
  interface ComponentCreatorWithMergeableInterfacesWhereOnlyTheOverrideHasTheBindsInstance {
    val value: Int

    interface BaseFactory1<T> {
      fun create(value: Int): T
    }

    interface BaseFactory2<T> {
      fun create(value: Int): T
    }

    @Component.Factory
    interface Factory :
      BaseFactory1<ComponentCreatorWithMergeableInterfacesWhereOnlyTheOverrideHasTheBindsInstance>,
      BaseFactory2<ComponentCreatorWithMergeableInterfacesWhereOnlyTheOverrideHasTheBindsInstance> {
      override fun create(
        @BindsInstance value: Int
      ): ComponentCreatorWithMergeableInterfacesWhereOnlyTheOverrideHasTheBindsInstance
    }
  }

  @Test
  fun `component factories should understand partially-implemented supertypes`() {
    val factory =
      createComponentFactory<ComponentCreatorWithIntermediateOverriddenDefaultFunctions.Factory>()
    val value1 = factory.create1().value

    assertEquals(value1, 0)

    val value2 = factory.create2(3).value

    assertEquals(value2, 3)
  }

  @Component
  interface ComponentCreatorWithIntermediateOverriddenDefaultFunctions {
    val value: Int

    interface BaseFactory1<T> {
      fun create1(): T
    }

    interface BaseFactory2<T> : BaseFactory1<T> {
      override fun create1(): T = create2(0)

      fun create2(@BindsInstance value: Int): T
    }

    @Component.Factory
    interface Factory : BaseFactory2<ComponentCreatorWithIntermediateOverriddenDefaultFunctions>
  }

  @Test
  fun `bindsinstance params with same types but different qualifiers are ok`() {
    val factory =
      createComponentFactory<ComponentWithDifferentBindsInstanceTypeQualifiers.Factory>()
    val component = factory.create(1, 2, 3)

    assertEquals(component.value1, 1)
    assertEquals(component.value2, 2)
    assertEquals(component.value3, 3)
  }

  @Component
  interface ComponentWithDifferentBindsInstanceTypeQualifiers {

    val value1: Int
    @Named("value2") val value2: Int
    @Named("value3") val value3: Int

    @Component.Factory
    fun interface Factory {
      fun create(
        @BindsInstance value1: Int,
        @BindsInstance @Named("value2") value2: Int,
        @BindsInstance @Named("value3") value3: Int,
      ): ComponentWithDifferentBindsInstanceTypeQualifiers
    }
  }

  @Test
  fun `basic assisted injection`() {
    val component =
      createComponentFactory<AssistedInjectComponent.Factory>().create("Hello, world!")
    val factory1 = component.factory
    val exampleClass1 = factory1.create(3)
    assertEquals("Hello, world!", exampleClass1.message)
    assertEquals(3, exampleClass1.intValue)

    val factory2 = component.factory2
    val exampleClass2 = factory2.create(4)
    assertEquals("Hello, world!", exampleClass2.message)
    assertEquals(4, exampleClass2.intValue)
  }

  @Component
  interface AssistedInjectComponent {
    val factory: ExampleClass.Factory
    val factory2: ExampleClass.Factory2

    @Component.Factory
    interface Factory {
      fun create(@BindsInstance message: String): AssistedInjectComponent
    }

    class ExampleClass
    @AssistedInject
    constructor(@Assisted val intValue: Int, val message: String) {
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
  fun `assisted injection with custom assisted keys`() {
    val component = createComponent<AssistedInjectComponentWithCustomAssistedKeys>()
    val factory = component.factory
    val exampleClass = factory.create(2, 1)
    assertEquals(1, exampleClass.intValue1)
    assertEquals(2, exampleClass.intValue2)
  }

  @Component
  interface AssistedInjectComponentWithCustomAssistedKeys {
    val factory: ExampleClass.Factory

    class ExampleClass
    @AssistedInject
    constructor(@Assisted("1") val intValue1: Int, @Assisted("2") val intValue2: Int) {
      @AssistedFactory
      fun interface Factory {
        fun create(@Assisted("2") intValue2: Int, @Assisted("1") intValue1: Int): ExampleClass
      }
    }
  }

  @Test
  fun `assisted injection with generic factory supertype`() {
    val component = createComponent<AssistedInjectComponentWithGenericFactorySupertype>()
    val factory = component.factory
    val exampleClass = factory.create(2)
    assertEquals(2, exampleClass.intValue)
  }

  @Component
  interface AssistedInjectComponentWithGenericFactorySupertype {
    val factory: ExampleClass.Factory

    class ExampleClass @AssistedInject constructor(@Assisted val intValue: Int) {
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
    val component = createComponent<AssistedInjectComponentDiamondInheritance>()
    val factory = component.factory
    val exampleClass = factory.create(2)
    assertEquals(2, exampleClass.intValue)
  }

  @Component
  interface AssistedInjectComponentDiamondInheritance {
    val factory: ExampleClass.Factory

    class ExampleClass @AssistedInject constructor(@Assisted val intValue: Int) {
      fun interface GrandParentBaseFactory<T> {
        fun create(intValue: Int): T
      }

      fun interface BaseFactory<T> : GrandParentBaseFactory<T> {
        override fun create(intValue: Int): T
      }

      fun interface BaseFactory2<T> : GrandParentBaseFactory<T> {
        override fun create(intValue: Int): T
      }

      @AssistedFactory
      fun interface Factory : BaseFactory<ExampleClass>, BaseFactory2<ExampleClass>
    }
  }

  @Test
  fun `assisted injection - factories can be accessed via component dependencies`() {
    val dependentComponent =
      createComponent<ComponentUsingDepFromDependentComponent.DependentComponent>()
    val component =
      createComponentFactory<ComponentUsingDepFromDependentComponent.Factory>()
        .create(dependentComponent)
    val factory = component.factory
    val exampleClass = factory.create(2)
    assertEquals(2, exampleClass.intValue)
    assertEquals("Hello, world!", exampleClass.message)
  }

  @Component
  interface ComponentUsingDepFromDependentComponent {
    val factory: ExampleClass.Factory

    @Component.Factory
    interface Factory {
      fun create(dependentComponent: DependentComponent): ComponentUsingDepFromDependentComponent
    }

    class ExampleClass
    @AssistedInject
    constructor(@Assisted val intValue: Int, val message: String) {
      @AssistedFactory
      fun interface Factory {
        fun create(intValue: Int): ExampleClass
      }
    }

    @Component
    interface DependentComponent {
      val message: String

      @Provides private fun provideMessage(): String = "Hello, world!"
    }
  }

  @Test
  fun `multibindings - simple int set with one value`() {
    val component = createComponent<MultibindingComponentWithSingleIntSet>()
    assertEquals(setOf(1), component.ints)

    // Each call yields a new set instance
    assertNotSame(component.ints, component.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> { (component.ints as MutableSet<Int>).clear() }
  }

  @Component
  interface MultibindingComponentWithSingleIntSet {
    val ints: Set<Int>

    @Provides @IntoSet private fun provideInt1(): Int = 1
  }

  @Test
  fun `multibindings - simple empty int set`() {
    val component = createComponent<MultibindingComponentWithEmptySet>()
    assertTrue(component.ints.isEmpty())

    // Each call in this case is actually the same instance
    assertSame(emptySet(), component.ints)
  }

  @Component
  interface MultibindingComponentWithEmptySet {
    @Multibinds val ints: Set<Int>
  }

  @Test
  fun `multibindings - int set with multiple values`() {
    val component = createComponent<MultibindingComponentWithIntSet>()
    assertEquals(setOf(1, 2), component.ints)

    // Each call yields a new set instance
    assertNotSame(component.ints, component.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> { (component.ints as MutableSet<Int>).clear() }
  }

  @Component
  interface MultibindingComponentWithIntSet {
    val ints: Set<Int>

    @Provides @IntoSet private fun provideInt1(): Int = 1

    @Provides @IntoSet private fun provideInt2(): Int = 2
  }

  @Test
  fun `multibindings - int set with elements into set`() {
    val component = createComponent<MultibindingComponentWithElementsIntoSet>()
    assertEquals(setOf(1, 2, 3), component.ints)

    // Each call yields a new set instance
    assertNotSame(component.ints, component.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> { (component.ints as MutableSet<Int>).clear() }
  }

  @Component
  interface MultibindingComponentWithElementsIntoSet {
    val ints: Set<Int>

    @Provides @ElementsIntoSet private fun provideInts(): Set<Int> = setOf(1, 2, 3)
  }

  @Test
  fun `multibindings - int set with scoped elements into set`() {
    val component = createComponent<MultibindingComponentWithScopedElementsIntoSet>()
    assertEquals(setOf(0), component.ints)

    // Subsequent calls have the same output
    assertEquals(setOf(0), component.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> { (component.ints as MutableSet<Int>).clear() }
  }

  @Singleton
  @Component
  abstract class MultibindingComponentWithScopedElementsIntoSet {
    private var count = 0

    abstract val ints: Set<Int>

    @Provides
    @ElementsIntoSet
    @Singleton
    private fun provideInts(): Set<Int> = buildSet { add(count++) }
  }

  @Test
  fun `multibindings - int set with mix of scoped elements into set and individual providers`() {
    val component =
      createComponent<MultibindingComponentWithMixOfScopedElementsIntoSetAndIndividualProviders>()
    assertEquals(setOf(2, 7, 10), component.ints)
    assertEquals(setOf(4, 9, 10), component.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> { (component.ints as MutableSet<Int>).clear() }
  }

  @Singleton
  @Component
  abstract class MultibindingComponentWithMixOfScopedElementsIntoSetAndIndividualProviders {
    private var count = 10
    private var unscopedCount = 1

    abstract val ints: Set<Int>

    @Provides @IntoSet private fun provideInt1(): Int = 1 + unscopedCount++

    @Provides @IntoSet private fun provideInt5(): Int = 5 + unscopedCount++

    @Provides
    @ElementsIntoSet
    @Singleton
    private fun provideInts(): Set<Int> = buildSet { add(count++) }
  }

  @Test
  fun `multibindings - set with scoped dependencies`() {
    val component = createComponent<MultibindingComponentWithWithScopedSetDeps>()
    assertEquals(setOf(0), component.ints)
    assertEquals(setOf(0, 1), component.ints)
    assertEquals(setOf(0, 2), component.ints)
  }

  @Singleton
  @Component
  abstract class MultibindingComponentWithWithScopedSetDeps {
    private var scopedCount = 0
    private var unscopedCount = 0

    abstract val ints: Set<Int>

    @Provides @Singleton @IntoSet private fun provideScopedInt(): Int = scopedCount++

    @Provides @IntoSet private fun provideUnscopedInt(): Int = unscopedCount++
  }

  @Test
  fun `multibindings - simple int map with one value`() {
    val component = createComponent<MultibindingComponentWithSingleIntMap>()
    assertEquals(mapOf(1 to 1), component.ints)

    // Each call yields a new map instance
    assertNotSame(component.ints, component.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> {
      (component.ints as MutableMap<Int, Int>).clear()
    }
  }

  @Component
  interface MultibindingComponentWithSingleIntMap {
    val ints: Map<Int, Int>

    @Provides @IntoMap @IntKey(1) private fun provideInt1(): Int = 1
  }

  @Test
  fun `multibindings - simple empty int map`() {
    val component = createComponent<MultibindingComponentWithEmptyMap>()
    assertTrue(component.ints.isEmpty())

    // Each call in this case is actually the same instance
    assertSame(emptyMap(), component.ints)
  }

  @Component
  interface MultibindingComponentWithEmptyMap {
    @Multibinds val ints: Map<Int, Int>
  }

  @Test
  fun `multibindings - int map with multiple values`() {
    val component = createComponent<MultibindingComponentWithIntMap>()
    assertEquals(mapOf(1 to 1, 2 to 2), component.ints)

    // Each call yields a new map instance
    assertNotSame(component.ints, component.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> {
      (component.ints as MutableMap<Int, Int>).clear()
    }
  }

  @Component
  interface MultibindingComponentWithIntMap {
    val ints: Map<Int, Int>

    @Provides @IntoMap @IntKey(1) private fun provideInt1(): Int = 1

    @Provides @IntoMap @IntKey(2) private fun provideInt2(): Int = 2
  }

  @Test
  fun `multibindings - map with scoped dependencies`() {
    val component = createComponent<MultibindingComponentWithWithScopedMapDeps>()
    assertEquals(mapOf(1 to 0, 2 to 0), component.ints)
    assertEquals(mapOf(1 to 0, 2 to 1), component.ints)
    assertEquals(mapOf(1 to 0, 2 to 2), component.ints)
  }

  @Singleton
  @Component
  abstract class MultibindingComponentWithWithScopedMapDeps {
    private var scopedCount = 0
    private var unscopedCount = 0

    abstract val ints: Map<Int, Int>

    @Provides @Singleton @IntoMap @IntKey(1) private fun provideScopedInt(): Int = scopedCount++

    @Provides @IntoMap @IntKey(2) private fun provideUnscopedInt(): Int = unscopedCount++
  }

  @Test
  fun `multibindings - string map with multiple values`() {
    val component = createComponent<MultibindingComponentWithStringMap>()
    assertEquals(mapOf("1" to 1, "2" to 2), component.ints)

    // Each call yields a new map instance
    assertNotSame(component.ints, component.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> {
      (component.ints as MutableMap<String, Int>).clear()
    }
  }

  @Component
  interface MultibindingComponentWithStringMap {
    val ints: Map<String, Int>

    @Provides @IntoMap @StringKey("1") private fun provideInt1(): Int = 1

    @Provides @IntoMap @StringKey("2") private fun provideInt2(): Int = 2
  }

  @Test
  fun `multibindings - kclass map with multiple values`() {
    val component = createComponent<MultibindingComponentWithKClassMap>()
    assertEquals<Map<KClass<*>, Int>>(mapOf(Int::class to 1, Float::class to 2), component.ints)

    // Each call yields a new map instance
    assertNotSame(component.ints, component.ints)

    // Ensure we return immutable types
    assertFailsWith<UnsupportedOperationException> {
      (component.ints as MutableMap<KClass<*>, Int>).clear()
    }
  }

  @Singleton
  @Component
  interface MultibindingComponentWithKClassMap {
    val ints: Map<KClass<*>, Int>

    @Provides @IntoMap @ClassKey(Int::class) private fun provideMapInt1() = 1

    @Provides @IntoMap @Singleton @ClassKey(Float::class) private fun provideMapInt2() = 2
  }

  @Test
  fun `multibindings - misc other map key types`() {
    val component = createComponent<MultibindingComponentWithMultipleOtherMapKeyTypes>()
    assertEquals(mapOf(Seasoning.SPICY to 1, Seasoning.REGULAR to 2), component.seasoningAmounts)
    assertEquals(mapOf(1L to 1, 2L to 2), component.longs)
    assertEquals(mapOf("1" to 1, "2" to 2), component.strings)
    assertEquals(
      mapOf(
        MultibindingComponentWithMultipleOtherMapKeyTypes.WrappedSeasoningKey(Seasoning.SPICY) to 1,
        MultibindingComponentWithMultipleOtherMapKeyTypes.WrappedSeasoningKey(Seasoning.REGULAR) to
          2,
      ),
      component.wrappedSeasoningAmounts,
    )
  }

  @Component
  interface MultibindingComponentWithMultipleOtherMapKeyTypes {
    val seasoningAmounts: Map<Seasoning, Int>

    @Provides @IntoMap @SeasoningKey(Seasoning.SPICY) private fun provideSpicySeasoning() = 1

    @Provides @IntoMap @SeasoningKey(Seasoning.REGULAR) private fun provideRegularSeasoning() = 2

    @MapKey annotation class SeasoningKey(val value: Seasoning)

    val longs: Map<Long, Int>

    @Provides @IntoMap @LongKey(1) private fun provideLongKey1() = 1

    @Provides @IntoMap @LongKey(2) private fun provideLongKey2() = 2

    val strings: Map<String, Int>

    @Provides @IntoMap @StringKey("1") private fun provideStringKey1() = 1

    @Provides @IntoMap @StringKey("2") private fun provideStringKey2() = 2

    val wrappedSeasoningAmounts: Map<WrappedSeasoningKey, Int>

    @Provides
    @IntoMap
    @WrappedSeasoningKey(Seasoning.SPICY)
    private fun provideWrappedSpicySeasoning() = 1

    @Provides
    @IntoMap
    @WrappedSeasoningKey(Seasoning.REGULAR)
    private fun provideWrappedRegularSeasoning() = 2

    @MapKey(unwrapValue = false) annotation class WrappedSeasoningKey(val value: Seasoning)
  }

  @Test
  fun `multibindings - map with different scoped provider values`() {
    val component = createComponent<MultibindingComponentWithWithScopedMapProviderDeps>()

    var unscopedCount = 0
    fun validate(body: () -> Map<Int, Provider<Int>>) {
      // Scoped int (key = 1) never increments no matter how many times we call the provider
      assertEquals(mapOf(1 to 0, 2 to unscopedCount++), body().mapValues { it.value() })
      assertEquals(mapOf(1 to 0, 2 to unscopedCount++), body().mapValues { it.value() })
      assertEquals(mapOf(1 to 0, 2 to unscopedCount++), body().mapValues { it.value() })
    }

    validate(component::ints)
    validate { component.providerInts() }
    validate { component.lazyInts.value }
  }

  @Singleton
  @Component
  abstract class MultibindingComponentWithWithScopedMapProviderDeps {
    private var scopedCount = 0
    private var unscopedCount = 0

    abstract val ints: Map<Int, Provider<Int>>
    abstract val providerInts: Provider<Map<Int, Provider<Int>>>
    abstract val lazyInts: Lazy<Map<Int, Provider<Int>>>

    @Provides @Singleton @IntoMap @IntKey(1) private fun provideScopedInt(): Int = scopedCount++

    @Provides @IntoMap @IntKey(2) private fun provideUnscopedInt(): Int = unscopedCount++
  }

  @Test
  fun `optional dependencies - provider - found dependency uses it`() {
    val component = createComponent<MessageProviderWithCharSequenceProvider>()
    assertEquals("Found", component.message)
  }

  @Component
  interface MessageProviderWithCharSequenceProvider : BaseMessageProviderWithDefault {
    @Provides private fun provideCharSequence(): CharSequence = "Found"
  }

  @Test
  fun `optional dependencies - provider - absent dependency uses default`() {
    val component = createComponent<MessageProviderWithoutCharSequenceProvider>()
    assertEquals("Not found", component.message)
  }

  @Component interface MessageProviderWithoutCharSequenceProvider : BaseMessageProviderWithDefault

  interface BaseMessageProviderWithDefault {
    val message: String

    @Provides
    private fun provideMessage(input: CharSequence = "Not found"): String = input.toString()
  }

  @Test
  fun `optional dependencies - provider - default values with back references work`() {
    val component = createComponent<OptionalDependenciesProviderWithBackReferencingDefault>()
    assertEquals("Not found: 3", component.message)
  }

  @Component
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
    val component = createComponent<OptionalDependenciesProviderWithManyDefaultBackReferences>()
    assertEquals("7", component.message)
  }

  @Component
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

  @Test
  fun `optional dependencies - provider - default values from private references`() {
    val component = createComponent<OptionalDependenciesProviderWithPrivateReferences>()
    assertEquals("Default message!", component.message)
  }

  @Component
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
    val component = createComponent<OptionalDependenciesProviderWithInstanceReferences>()
    assertEquals("Default message!", component.defaultMessage)
  }

  @Component
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
    val component = createComponent<MessageClassWithCharSequenceProvider>()
    assertEquals("Found", component.message)
  }

  @Component
  interface MessageClassWithCharSequenceProvider : BaseMessageClassWithDefault {
    @Provides private fun provideMessage(): String = "Found"
  }

  @Test
  fun `optional dependencies - class - absent dependency uses default`() {
    val component = createComponent<MessageClassWithoutCharSequenceProvider>()
    assertEquals("Not found", component.message)
  }

  @Component interface MessageClassWithoutCharSequenceProvider : BaseMessageClassWithDefault

  interface BaseMessageClassWithDefault {
    val messageClass: MessageClass
    val message
      get() = messageClass.message

    @Inject class MessageClass(val message: String = "Not found")
  }

  @Test
  fun `optional dependencies - class - default values with back references work`() {
    val component = createComponent<OptionalDependenciesClassWithBackReferencingDefault>()
    assertEquals("Not found: 3", component.message)
  }

  @Component
  interface OptionalDependenciesClassWithBackReferencingDefault {
    val messageClass: MessageClass
    val message: String
      get() = messageClass.message

    @Provides private fun provideInt(): Int = 3

    @Inject class MessageClass(intValue: Int, val message: String = "Not found: $intValue")
  }

  @Test
  fun `optional dependencies - class - default values with many back references`() {
    val component = createComponent<OptionalDependenciesClassWithManyDefaultBackReferences>()
    assertEquals("7", component.message)
  }

  @Component
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
    val component = createComponent<OptionalDependenciesClassWithPrivateReferences>()
    assertEquals("Default message!", component.message)
  }

  @Component
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

  @Component
  interface StringComponent {

    val string: String

    @Component.Factory
    fun interface Factory {
      fun create(@BindsInstance string: String): StringComponent
    }
  }
}
