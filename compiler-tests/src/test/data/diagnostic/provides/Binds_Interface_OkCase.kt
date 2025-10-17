interface ExampleGraph {
  @Binds val Int.bind: Number
  @Binds fun String.bind(): CharSequence
}
