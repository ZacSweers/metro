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
import dev.zacsweers.lattice.annotations.BindsInstance
import dev.zacsweers.lattice.annotations.Component
import dev.zacsweers.lattice.annotations.Inject
import dev.zacsweers.lattice.annotations.Named
import dev.zacsweers.lattice.annotations.Provides
import dev.zacsweers.lattice.annotations.Singleton
import dev.zacsweers.lattice.createComponent
import dev.zacsweers.lattice.createComponentFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class ComponentProcessingTest {

  @Singleton
  @Component
  interface ComplexDependenciesComponent {

    val repository: Repository
    val apiClient: ApiClient

    @Provides fun provideFileSystem(): FileSystem = FakeFileSystem()

    @Named("cache-dir-name") @Provides fun provideCacheDirName(): String = "cache"

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

    @Provides fun count(): Int = callCount++

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

    @Provides fun provideInt(): Int = counter++
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

    @Provides fun provideValue(string: String): CharSequence = string

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
    val component = factory.create(1, 2)

    assertEquals(component.value1, 1)
    assertEquals(component.value2, 2)
  }

  @Component
  interface ComponentWithDifferentBindsInstanceTypeQualifiers {

    val value1: Int
    @Named("value2") val value2: Int

    @Component.Factory
    fun interface Factory {
      fun create(
        @BindsInstance value1: Int,
        @BindsInstance @Named("value2") value2: Int,
      ): ComponentWithDifferentBindsInstanceTypeQualifiers
    }
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
