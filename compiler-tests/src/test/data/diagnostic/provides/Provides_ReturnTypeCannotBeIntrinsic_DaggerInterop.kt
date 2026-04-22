// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DAGGER_INTEROP

import dagger.Lazy as DaggerLazy

interface ExampleGraph {
  val string: String

  // dagger.Lazy is an intrinsic type (under Dagger runtime interop it's treated as kotlin.Lazy)
  @Provides fun provideLazy(): <!INTRINSIC_BINDING_ERROR!>DaggerLazy<String><!> = DaggerLazy { "" }
}
