object InlinedObject

@BindingContainer
object Bindings {
  @Provides fun provideObject(): InlinedObject = InlinedObject
}

@DependencyGraph(bindingContainers = [Bindings::class])
interface AppGraph {
  val objectProvider: Provider<InlinedObject>
}
