// ENABLE_HILT_INTEROP
// ENABLE_DAGGER_INTEROP

// Verifies custom `@DefineComponent` scope mapping and concrete Hilt scope compatibility.

import dagger.Module
import dagger.Provides as DaggerProvides
import dagger.hilt.DefineComponent
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Scope

@Scope @Retention(AnnotationRetention.RUNTIME) annotation class FeatureScoped

@FeatureScoped @DefineComponent(parent = SingletonComponent::class) interface FeatureComponent

@Module
@InstallIn(FeatureComponent::class)
class FeatureModule {
  @FeatureScoped
  @DaggerProvides fun provideTag(): String = "feature"
}

@FeatureScoped
@DependencyGraph(FeatureScoped::class)
interface FeatureGraph {
  val tag: String
}

fun box(): String {
  val graph = createGraph<FeatureGraph>()
  assertEquals("feature", graph.tag)
  return "OK"
}
