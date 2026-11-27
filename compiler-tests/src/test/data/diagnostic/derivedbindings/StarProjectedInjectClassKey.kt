// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

// A generic `@Binds` can alias a key to a star-projected key for a generic `@Inject` class. A star
// has no type to substitute for the class's type parameter, so the class doesn't satisfy the key.
interface IThing<A, B>

@Inject class Thing<A, B>(val value: B) : IThing<A, B>

@BindingContainer
interface ThingBindings {
  @Binds fun <T> Thing<T, *>.asIThing(): IThing<T, *>
}

@DependencyGraph(bindingContainers = [ThingBindings::class])
interface AppGraph {
  val iThing: IThing<String, *>
  val <!MISSING_BINDING!>thing<!>: Thing<String, *>

  // A default value still applies when the class can't satisfy the key.
  val consumer: Consumer
}

@Inject class Consumer(val thing: Thing<String, *> = Thing<String, Int>(0))
