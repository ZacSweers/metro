// More of a regression test for cases where we propagate field qualifiers
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// MODULE: lib
// ENABLE_DAGGER_KSP

// FILE: Dependency.java
public interface Dependency {
}

// FILE: ExampleClass.java
import javax.inject.Inject;
import javax.inject.Named;

public class ExampleClass {
  @Inject @Named("qualified") public Dependency qualified;
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP

// FILE: DependencyImpl.kt
@ContributesBinding(AppScope::class)
@Inject
class DependencyImpl : Dependency

// FILE: ExampleGraph.kt
@DependencyGraph(AppScope::class)
interface <!METRO_ERROR!>ExampleGraph<!> {
  @Provides fun provideString(): String = "Hello"
  @Provides @javax.inject.Named("qualified") fun provideQualifiedString(): String = "Hello"

  fun inject(example: ExampleClass)
}
