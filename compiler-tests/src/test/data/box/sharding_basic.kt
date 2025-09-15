// COMPILER_OPTIONS: -Xplugin-option=metro:sharding.enabled=true -Xplugin-option=metro:sharding.keysPerShard=5
// TEST_TARGET: jvm

// Basic sharding test with various binding types

// Constructor injected types
@Inject class DatabaseService
@Inject class NetworkService
@Inject class LoggingService
@Inject class CacheService
@Inject class AuthService(val database: DatabaseService)
@Inject class UserRepository(val auth: AuthService, val network: NetworkService)
@Inject class AnalyticsService(val logging: LoggingService)

// Binding container with @Provides methods
@BindingContainer
class AppModule {
  @Provides fun provideConfig(): String = "production"
  
  @Provides fun provideApiUrl(config: String): String = "https://api.example.com/$config"
  
  @Provides fun provideTimeout(): Int = 30000
  
  @Provides fun provideDebugFlag(): Boolean = false
}

// Multibinding contributions
@BindingContainer
interface FeatureModule {
  @Binds @IntoSet fun bindFeature1(impl: Feature1): Feature
  @Binds @IntoSet fun bindFeature2(impl: Feature2): Feature
}

interface Feature
@Inject class Feature1 : Feature
@Inject class Feature2 : Feature

@DependencyGraph(bindingContainers = [AppModule::class, FeatureModule::class])
interface BasicShardedGraph {
  // Constructor injected
  val databaseService: DatabaseService
  val networkService: NetworkService
  val loggingService: LoggingService
  val cacheService: CacheService
  val authService: AuthService
  val userRepository: UserRepository
  val analyticsService: AnalyticsService
  
  // Module provided
  val config: String
  val apiUrl: String
  val timeout: Int
  val debugFlag: Boolean
  
  // Multibindings
  val features: Set<Feature>

  @BindingContainer
  val appModule: AppModule

  @BindingContainer
  val featureModule: FeatureModule
}

fun box(): String {
  val graph = createGraph<BasicShardedGraph>()
  
  // Verify constructor injected services
  assertNotNull(graph.databaseService)
  assertNotNull(graph.networkService)
  assertNotNull(graph.loggingService)
  assertNotNull(graph.cacheService)
  assertNotNull(graph.authService)
  assertNotNull(graph.userRepository)
  assertNotNull(graph.analyticsService)
  
  // Verify dependencies are wired correctly
  assertEquals(graph.databaseService, graph.authService.database)
  assertEquals(graph.authService, graph.userRepository.auth)
  assertEquals(graph.networkService, graph.userRepository.network)
  assertEquals(graph.loggingService, graph.analyticsService.logging)
  
  // Verify module provisions
  assertEquals("production", graph.config)
  assertEquals("https://api.example.com/production", graph.apiUrl)
  assertEquals(30000, graph.timeout)
  assertEquals(false, graph.debugFlag)
  
  // Verify multibindings
  assertEquals(2, graph.features.size)
  assertTrue(graph.features.any { it is Feature1 })
  assertTrue(graph.features.any { it is Feature2 })
  
  return "OK"
}