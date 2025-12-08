// ENABLE_DAGGER_KSP
// Test direct invocation checks for Dagger provider factories with various visibilities

// FILE: ProtectedClass.java
import jakarta.inject.Inject;

public class ProtectedClass {
  @Inject protected ProtectedClass() {}
}

// FILE: PackagePrivateClass.java
import jakarta.inject.Inject;

public class PackagePrivateClass {
  @Inject PackagePrivateClass() {}
}

// FILE: InternalModule.kt
import dagger.Module
import dagger.Provides

@Module
class ModuleWithInternal {
  @Provides internal fun provideInt(): Int = 42
}

// FILE: ExampleGraph.kt
@DependencyGraph(bindingContainers = [ModuleWithInternal::class])
interface ExampleGraph {
  val protectedClass: ProtectedClass
  val packagePrivateClass: PackagePrivateClass
  val int: Int
}