// https://github.com/ZacSweers/metro/issues/1427

object ExampleConstants {
  const val CONTROLLER = "controller"
}

object DeepLinkStrategyId {
  const val ACTION_DISPLAY = "actiondisplay"
}

private const val KEY_2 = "${ExampleConstants.CONTROLLER}2${DeepLinkStrategyId.ACTION_DISPLAY}"

@BindingContainer
@ContributesTo(AppScope::class)
object ExampleContainer {
  @Provides
  @IntoMap
  @StringKey(ExampleConstants.CONTROLLER + DeepLinkStrategyId.ACTION_DISPLAY)
  fun provideString(): String = "hello"
  @Provides
  @IntoMap
  @StringKey(KEY_2)
  fun provideString2(): String = "hello2"
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val strings: Map<String, String>
}

fun box(): String {
  val strings = createGraph<AppGraph>().strings
  assertEquals("hello", strings[ExampleConstants.CONTROLLER + DeepLinkStrategyId.ACTION_DISPLAY])
  assertEquals("hello2", strings[KEY_2])
  return "OK"
}