// https://github.com/ZacSweers/metro/issues/858
interface Box {
  val int: Int
}

class RealBox(override val int: Int) : Box

@Scope
annotation class AppScope

@BindingContainer
@ContributesTo(AppScope::class)
object AppScopeContainer {
  @Provides
  @SingleIn(AppScope::class)
  fun provideBox(): Box {
    return RealBox(int = 1)
  }
}

@Scope
annotation class EncryptedAppScope

@Qualifier
annotation class EncryptedBox

@BindingContainer
@ContributesTo(EncryptedAppScope::class)
object EncryptedAppScopeContainer {
  @Provides
  @EncryptedBox
  @SingleIn(AppScope::class)
  fun provideEncryptedBox(box: Box): Box {
    return RealBox(int = box.int + 1)
  }
}

@DependencyGraph(
  scope = AppScope::class,
  additionalScopes = [
    EncryptedAppScope::class,
  ],
)
interface VariantAppComponent {
  @EncryptedBox val encryptedBox: Box
}

fun box(): String {
  val graph = createGraph<VariantAppComponent>()
  assertEquals(graph.encryptedBox.int, 2)
  return "OK"
}