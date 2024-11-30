package dev.zacsweers.lattice.test.integration

import dev.zacsweers.lattice.Provider
import dev.zacsweers.lattice.annotations.Component
import dev.zacsweers.lattice.annotations.Inject
import dev.zacsweers.lattice.annotations.Named
import dev.zacsweers.lattice.annotations.Provides
import dev.zacsweers.lattice.annotations.Singleton
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

    @Component.Factory
    fun interface Factory {
      fun create(): ProviderTypesAsAccessorsComponent
    }
  }

  @Test
  fun `different provider types as accessors`() {
    val component = createComponentFactory<ProviderTypesAsAccessorsComponent.Factory>().create()

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
}
