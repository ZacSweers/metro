// ENABLE_DAGGER_INTEROP
// RENDER_DIAGNOSTICS_FULL_TEXT

import dagger.multibindings.LazyClassKey

interface ExampleGraph {
  <!DAGGER_LAZY_CLASS_KEY_ERROR!>@LazyClassKey(String::class)<!>
  @Provides
  @IntoMap
  fun provideString(): String {
    return "hello"
  }

  <!DAGGER_LAZY_CLASS_KEY_ERROR!>@get:LazyClassKey(Int::class)<!>
  @Binds
  @IntoMap
  val Int.bindInt: Number
}
