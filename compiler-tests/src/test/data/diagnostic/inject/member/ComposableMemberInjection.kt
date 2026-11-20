// RENDER_DIAGNOSTICS_FULL_TEXT
// FILE: Composable.kt
package androidx.compose.runtime

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER, AnnotationTarget.PROPERTY_GETTER)
annotation class Composable

// FILE: Example.kt
import androidx.compose.runtime.Composable

class ExampleClass {
  @Inject @Composable fun <!MEMBERS_INJECT_ERROR!>setValue<!>(value: Int) {}
}

class AllowedGetter {
  @Inject var value: Int = 0
    @Composable get
}

class AllowedGetterSite {
  @Inject @get:Composable var value: Int = 0
}
