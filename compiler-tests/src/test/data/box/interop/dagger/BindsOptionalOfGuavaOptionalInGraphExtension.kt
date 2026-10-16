// ENABLE_DAGGER_INTEROP
import com.google.common.base.Optional

interface FeatureScope

interface Feature

@dagger.Module
interface ParentModule {
  @dagger.BindsOptionalOf fun optionalFeature(): Feature
}

@GraphExtension(FeatureScope::class)
interface FeatureGraph {
  val inherited: Optional<Feature>

  @GraphExtension.Factory
  interface Factory {
    fun create(): FeatureGraph
  }
}

@DependencyGraph(AppScope::class, bindingContainers = [ParentModule::class])
interface ExampleGraph {
  val parentOptional: Optional<Feature>

  val featureGraphFactory: FeatureGraph.Factory
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  if (graph.parentOptional.isPresent) return "FAIL: parent"
  if (graph.featureGraphFactory.create().inherited.isPresent) return "FAIL: inherited"
  return "OK"
}
