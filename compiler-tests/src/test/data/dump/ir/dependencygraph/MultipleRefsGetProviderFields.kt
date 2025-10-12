@DependencyGraph
interface AppGraph {
  val long: Long
  val string: String

  @Provides fun provideLong(holder: IntHolder): Long = holder.int.toLong()
  @Provides fun provideString(holder: IntHolder): String = holder.int.toString()
}

// Should save a field because it's used multiple times
@Inject class IntHolder {
  val int: Int = 3
}