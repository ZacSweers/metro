// WITH_DAGGER

// MODULE: lib
// FILE: Library.kt
import javax.inject.Qualifier
import kotlin.annotation.AnnotationRetention.BINARY

interface EnabledProvider {
  @Qualifier @Retention(BINARY) private annotation class FlipperEnabled

  @FlipperEnabled
  @Provides
  private fun provideEnabled(): Boolean = true

  @Provides
  private fun provideEnabledValue(@FlipperEnabled enabled: Boolean): String = enabled.toString()
}

// MODULE: main(lib)
// FILE: Main.kt

@DependencyGraph
interface ExampleGraph : EnabledProvider {
  val value: String
}

fun box(): String {
  assertEquals("true", createGraph<ExampleGraph>().value)
  return "OK"
}
