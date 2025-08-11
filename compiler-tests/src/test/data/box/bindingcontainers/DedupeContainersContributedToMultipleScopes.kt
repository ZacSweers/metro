// https://github.com/ZacSweers/metro/issues/861
interface Box {
  val int: Int
}

class RealBox(override val int: Int) : Box

@Scope annotation class AppScope

@Scope annotation class InternalDebugAppScope

@Scope annotation class InternalReleaseAppScope

@Scope annotation class ProductionAppScope

@Qualifier annotation class EncryptedBox

@BindingContainer
@ContributesTo(InternalReleaseAppScope::class)
@ContributesTo(ProductionAppScope::class)
object BoxContainer {
  @EncryptedBox
  @Provides
  fun provideEncryptedBox(): Box {
    return RealBox(int = 1)
  }
}

@DependencyGraph(scope = AppScope::class, additionalScopes = [InternalReleaseAppScope::class])
interface VariantAppComponent {
  @EncryptedBox val encryptedBox: Box
}

fun box(): String {
  val graph = createGraph<VariantAppComponent>()
  assertEquals(1, graph.encryptedBox.int)
  return "OK"
}
