// ENABLE_HILT_INTEROP
// ENABLE_DAGGER_INTEROP

// Verifies the dynamic component->scope mapping via a user-declared `@DefineComponent`.
// The Android Hilt components live in hilt-android (not hilt-core), so we exercise the same
// resolution path through a custom component declared in source.

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
  @DaggerProvides fun provideTag(): String = "feature"
}

@DependencyGraph(FeatureScoped::class)
interface FeatureGraph {
  val tag: String
}

fun box(): String {
  val graph = createGraph<FeatureGraph>()
  assertEquals("feature", graph.tag)
  return "OK"
}
