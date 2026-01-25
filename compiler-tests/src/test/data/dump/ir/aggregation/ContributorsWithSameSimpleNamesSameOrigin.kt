// https://github.com/ZacSweers/metro/issues/1728
// Tests that we generate a `bindsAsFoo2` if we allocate the same name more than once for same simple tye

// FILE: File1.kt
package com.mecharip.base

interface Foo {
  fun foo()
}

// FILE: File2.kt
package com.mecharip.sharedkit

interface Foo : com.mecharip.base.Foo {
  fun bar()
}


// FILE: File3.kt

@Inject
@ContributesBinding(AppScope::class, binding<com.mecharip.sharedkit.Foo>())
@ContributesBinding(AppScope::class, binding<com.mecharip.base.Foo>())
class FooImpl : com.mecharip.sharedkit.Foo {
  override fun bar() {
  }

  override fun foo() {
  }
}