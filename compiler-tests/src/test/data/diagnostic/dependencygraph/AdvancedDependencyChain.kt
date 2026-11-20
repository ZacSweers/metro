// METRO_JVM_ONLY
// RUN_PIPELINE_TILL: BACKEND

import java.nio.file.FileSystem
import java.nio.file.FileSystems

// Integration tests exercise the repository built by this dependency chain.

@DependencyGraph(AppScope::class)
interface ExampleGraph {

  val repository: Repository

  @Provides
  fun provideFileSystem(): FileSystem = FileSystems.getDefault()

  @Named("cache-dir-name")
  @Provides
  fun provideCacheDirName(): String = "cache"
}

@Inject @SingleIn(AppScope::class) class Cache(fileSystem: FileSystem, @Named("cache-dir-name") cacheDirName: () -> String)
@Inject @SingleIn(AppScope::class) class HttpClient(cache: Cache)
@Inject @SingleIn(AppScope::class) class ApiClient(httpClient: Lazy<HttpClient>)
@Inject class Repository(apiClient: ApiClient)
