// LANGUAGE: +ContextParameters
// ENABLE_TOP_LEVEL_FUNCTION_INJECTION
// CHECK_BYTECODE_TEXT
// MODULE: lib
// FILE: Composable.kt
package androidx.compose.runtime

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER, AnnotationTarget.PROPERTY_GETTER)
annotation class Composable

@MustBeDocumented
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.BINARY)
@StableMarker
annotation class Stable

@MustBeDocumented
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class StableMarker

// FILE: App.kt
import androidx.compose.runtime.Composable

interface Modifier {
  companion object : Modifier
}

interface SharedTransitionScope
interface Clock
interface MyUiComponentClass

@Inject
@Composable
context(@Assisted sharedTransitionScope: SharedTransitionScope, ui: MyUiComponentClass)
fun App(clock: Clock, @Assisted modifier: Modifier = Modifier): List<Any> =
  listOf(sharedTransitionScope, ui, clock, modifier)

@Inject
@Composable
fun Plain(): String = "plain"

@Inject
@Composable
context(ui: MyUiComponentClass)
fun InjectedContext(clock: Clock): List<Any> = listOf(ui, clock)

interface OtherUiComponent

@Inject
@Composable
context(_: MyUiComponentClass, _: OtherUiComponent)
fun UnusedContexts(clock: Clock): Clock = clock

@DependencyGraph
interface ExampleGraph {
  val app: App
  val plain: Plain
  val injectedContext: InjectedContext
  val unusedContexts: UnusedContexts
  @Provides fun provideOtherUiComponent(): OtherUiComponent = object : OtherUiComponent {}
  @Provides fun provideClock(): Clock = object : Clock {}
  @Provides fun provideUiComponent(): MyUiComponentClass = object : MyUiComponentClass {}
}

// The generated invoke carries the Compose annotation into the compiled library.
// @App.class:
// 1 @Landroidx/compose/runtime/Composable;

// @Plain.class:
// 1 @Landroidx/compose/runtime/Composable;
// @InjectedContext.class:
// 1 @Landroidx/compose/runtime/Composable;
// @UnusedContexts.class:
// 1 @Landroidx/compose/runtime/Composable;

// MODULE: main(lib)
fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val scope = object : SharedTransitionScope {}

  // A downstream call must find the assisted context and the default in the compiled library.
  val args = with(scope) { graph.app.invoke() }
  assertSame(scope, args[0])
  assertIs<MyUiComponentClass>(args[1])
  assertIs<Clock>(args[2])
  assertSame(Modifier, args[3])

  assertEquals("plain", graph.plain.invoke())
  val injected = graph.injectedContext.invoke()
  assertIs<MyUiComponentClass>(injected[0])
  assertIs<Clock>(injected[1])
  assertIs<Clock>(graph.unusedContexts.invoke())
  return "OK"
}
