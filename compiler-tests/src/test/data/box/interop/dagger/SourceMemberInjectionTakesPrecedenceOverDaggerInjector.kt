// Regression test for https://github.com/ZacSweers/metro/issues/2731
//
// The Android reproducer compiles this target with both Metro and Dagger. Dagger's generated Java
// members injector exposes a platform type, which Metro then writes as nullable in its own factory
// metadata. The Dagger-named Java carrier below uses an explicit nullable annotation to model that
// binary type deterministically in the test harness.

// MODULE: lib
// ENABLE_DAGGER_INTEROP

// FILE: InjectedTarget.kt
import javax.inject.Inject

class Dependency

class InjectedTarget @Inject constructor() {
  @Inject lateinit var dependency: Dependency
}

// FILE: org/jetbrains/annotations/Nullable.java
package org.jetbrains.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE_USE})
public @interface Nullable {}

// FILE: InjectedTarget_MembersInjector.java
import dagger.internal.InjectedFieldSignature;
import org.jetbrains.annotations.Nullable;

public final class InjectedTarget_MembersInjector {
  @InjectedFieldSignature("InjectedTarget.dependency")
  public static void injectDependency(
      InjectedTarget instance, @Nullable Dependency dependency) {
    instance.dependency = dependency;
  }
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP

// FILE: Main.kt
@DependencyGraph
interface AppGraph {
  val target: InjectedTarget

  @Provides fun dependency(): Dependency = Dependency()
}

fun box(): String {
  assertNotNull(createGraph<AppGraph>().target.dependency)
  return "OK"
}
