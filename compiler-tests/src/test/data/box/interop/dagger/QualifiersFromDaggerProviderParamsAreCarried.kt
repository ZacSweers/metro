// ENABLE_DAGGER_KSP

// FILE: ExampleModule.java
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import javax.inject.Named;
import java.util.Set;

@Module
public abstract class ExampleModule {
  public ExampleModule() {

  }

  @Provides
  public static String provideString(@Named("qualifier") String value) {
    return value;
  }
}

// FILE: ExampleGraph.kt
import javax.inject.Named

@DependencyGraph(bindingContainers = [ExampleModule::class])
interface ExampleGraph {
  val value: String

  @Provides @Named("qualifier") fun provideQualifiedString(): String = "hello"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("hello", graph.value)
  return "OK"
}
