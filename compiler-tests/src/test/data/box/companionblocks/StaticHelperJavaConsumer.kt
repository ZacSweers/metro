// MIN_COMPILER_VERSION: 2.5.0-Beta1
// METRO_JVM_ONLY
// MODULE: lib
// COMPANION_MODE: COMPANION_BLOCK
// FILE: InjectedValue.kt

@Inject
class InjectedValue private constructor(val value: String) {
  @Inject lateinit var values: List<String>
}

@Inject
class BoundedValue<T : CharSequence> private constructor(val value: T) {
  @Inject lateinit var values: List<T>
}

// MODULE: main(lib)
// COMPANION_MODE: COMPANION_BLOCK
// FILE: JavaHelpers.java

public final class JavaHelpers {
  public static InjectedValue create() {
    InjectedValue value = InjectedValue.MetroFactory.newInstance("java");
    InjectedValue.MetroMembersInjector.injectValues(
        value, java.util.Collections.singletonList("java-members"));
    return value;
  }

  public static BoundedValue<String> createBounded() {
    BoundedValue<String> value = BoundedValue.MetroFactory.newInstance("bounded");
    BoundedValue.MetroMembersInjector.injectValues(
        value, java.util.Collections.singletonList("bounded-members"));
    return value;
  }
}

// FILE: Main.kt

fun box(): String {
  val value = JavaHelpers.create()
  assertEquals("java", value.value)
  assertEquals(listOf("java-members"), value.values)
  val bounded = JavaHelpers.createBounded()
  assertEquals("bounded", bounded.value)
  assertEquals(listOf("bounded-members"), bounded.values)
  return "OK"
}
