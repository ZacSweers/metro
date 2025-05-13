// RENDER_DIAGNOSTICS_FULL_TEXT

abstract class ExampleGraph {
  // This is ok
  @Binds
  abstract val String.bindCharSequence: CharSequence

  // This is also ok
  @get:Binds
  abstract val String.bindCharSequence2: CharSequence

  @Provides
  fun <!PROVIDES_ERROR!>provideString<!>() = "Hello"

  companion object {
    @Provides
    fun <!PROVIDES_ERROR!>provideInt<!>() = 0
  }
}


interface ExampleGraphInterface {
  // This is ok
  @Binds
  val String.bindCharSequence: CharSequence

  // This is also ok
  @get:Binds
  val String.bindCharSequence2: CharSequence
}
