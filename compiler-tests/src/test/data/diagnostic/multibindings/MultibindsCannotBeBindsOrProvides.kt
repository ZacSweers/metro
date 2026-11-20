// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface ExampleGraph {
  @Multibinds @Binds @Named("qualified") val Set<Int>.<!MULTIBINDS_ERROR!>intsProp<!>: Set<Int>
}

// A @Provides declaration with @Multibinds hits the abstract declaration check first.
