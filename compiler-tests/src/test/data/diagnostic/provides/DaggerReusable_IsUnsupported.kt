// WITH_DAGGER
// RENDER_DIAGNOSTICS_FULL_TEXT

import dagger.Reusable

interface ExampleGraph {
  <!DAGGER_REUSABLE_ERROR!>@Reusable<!>
  @Provides
  fun provideInt(): Int {
    return 0
  }

  <!DAGGER_REUSABLE_ERROR!>@Reusable<!>
  @Binds
  val Int.bind: Number
}
