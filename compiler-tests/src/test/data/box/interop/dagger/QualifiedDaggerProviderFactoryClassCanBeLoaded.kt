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

  @Multibinds
  public abstract Set<String> strings();

  @Binds
  public abstract CharSequence charSeq(@Named("qualifier") String value);

  @Provides @Named("qualifier")
  public static String provideString() {
    return "hello";
  }

  // Ensure nested classes work
  @Module
  public static class InnerModule {
    public InnerModule() {

    }

    @Provides
    public int provideInt() {
      return 3;
    }
  }
}

// FILE: ExampleGraph.kt
import javax.inject.Named

@DependencyGraph(bindingContainers = [ExampleModule::class, ExampleModule.InnerModule::class])
interface ExampleGraph {
  @Named("qualifier") val value: String
  val int: Int
  val strings: Set<String>
  val charSequence: CharSequence
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("hello", graph.value)
  assertEquals("hello", graph.charSequence)
  assertTrue(graph.strings.isEmpty())
  assertEquals(3, graph.int)
  return "OK"
}
