// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Tests that if we have multiple concrete injected subtypes of a base type with member injections,
// we do not emit a useless diagnostic about the common parent member injection binding being
// somehow duplicated

@HasMemberInjections
abstract class Base {
  @Inject lateinit var string: String
}

class Impl1 : Base() {
  @Inject lateinit var string2: String
}

class Impl2 : Base() {
  @Inject lateinit var string2: String
}

@Inject class Impl3(private val string2: String) : Base()

@DependencyGraph
interface AppGraph {
  val impl1: MembersInjector<Impl1>
  val impl2: MembersInjector<Impl2>
  val impl3: Impl3

  @Provides fun provideString(): String = "Hello"
}
