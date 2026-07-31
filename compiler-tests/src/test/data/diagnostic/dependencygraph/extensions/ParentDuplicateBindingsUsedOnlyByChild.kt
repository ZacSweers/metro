// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// https://github.com/ZacSweers/metro/issues/2610

@DependencyGraph(
  AppScope::class,
  bindingContainers = [BindingContainer1::class, BindingContainer2::class],
)
interface <!DUPLICATE_BINDING!>AppGraph<!> {
  val bindingContainer1Marker: BindingContainer1Marker
  val bindingContainer2Marker: BindingContainer2Marker

  fun child(): ChildGraph
}

@GraphExtension
interface ChildGraph {
  val tag: String
}

@BindingContainer
class BindingContainer1 {
  @Provides
  fun provideMarker(): BindingContainer1Marker = BindingContainer1Marker()

  @Provides
  fun provideTag(): String = "a"

  @Provides
  fun provideTag2(): String = "b"
}

@BindingContainer
class BindingContainer2 {
  @Provides
  fun provideMarker(): BindingContainer2Marker = BindingContainer2Marker()

  @Provides
  fun provideTag(): String = "c"
}

class BindingContainer1Marker

class BindingContainer2Marker
