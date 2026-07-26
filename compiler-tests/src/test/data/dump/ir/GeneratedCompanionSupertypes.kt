// COMPILER_VERSION: 2.4.20-Beta1
// GENERATE_CLASSES_IN_IR: true
// ENABLE_PROVIDER_INLINING: false
// DUMP_IR

@BindingContainer
object Bindings {
  @Provides fun provideString(value: Int): String = value.toString()
}

@Inject class Injected(value: Int)

@AssistedInject class AssistedTarget(@Assisted val value: String)

@AssistedFactory
fun interface AssistedTargetFactory {
  fun create(value: String): AssistedTarget
}
