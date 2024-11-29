package dev.zacsweers.lattice.test.integration

import dev.zacsweers.lattice.Provider
import dev.zacsweers.lattice.annotations.Component
import dev.zacsweers.lattice.annotations.Inject
import dev.zacsweers.lattice.annotations.Named
import dev.zacsweers.lattice.annotations.Provides
import dev.zacsweers.lattice.annotations.Singleton
import dev.zacsweers.lattice.createComponentFactory
import kotlin.test.Test
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
