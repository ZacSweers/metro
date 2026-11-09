// METRO_JVM_ONLY
// ENABLE_DAGGER_INTEROP
// Control for ConvertedProviderClassFileNameLength with short names.

class Holder(
  val target: Target
)

class Target

@DependencyGraph
interface Alpha {
  fun betaFactory(): Beta.Factory

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(
      @Includes holder: Holder
    ): Alpha
  }
}

@GraphExtension
interface Beta {
  fun gammaFactory(): Gamma.Factory

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): Beta
  }
}

@GraphExtension
interface Gamma {
  fun deltaFactory(): Delta.Factory

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): Gamma
  }
}

@GraphExtension
interface Delta {
  fun epsilonFactory(): Epsilon.Factory

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): Delta
  }
}

@GraphExtension
interface Epsilon {
  fun zetaFactory(): Zeta.Factory

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): Epsilon
  }
}

@GraphExtension
interface Zeta {
  fun etaFactory(): Eta.Factory

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): Zeta
  }
}

@GraphExtension
interface Eta {
  val targetProvider: javax.inject.Provider<Target>

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): Eta
  }
}

fun Class<*>.nestedClassNamesRecursive(): List<String> =
  declaredClasses.flatMap { listOf(it.name) + it.nestedClassNamesRecursive() }

fun classFileNameBytes(binaryName: String): Int =
  (binaryName.substringAfterLast('.') + ".class").encodeToByteArray().size

fun box(): String {
  val root =
    createGraphFactory<Alpha.Factory>()
      .create(
        Holder(Target())
      )
  val innermost =
    root
      .betaFactory()
      .create()
      .gammaFactory()
      .create()
      .deltaFactory()
      .create()
      .epsilonFactory()
      .create()
      .zetaFactory()
      .create()
      .etaFactory()
      .create()
  val generatedNames =
    listOf(innermost.javaClass.name) + innermost.javaClass.nestedClassNamesRecursive()
  val overLimit = generatedNames.filter { classFileNameBytes(it) > 255 }
  assertTrue(
    overLimit.isEmpty(),
    "class file names over 255 bytes: " +
      overLimit.joinToString { "$it (${classFileNameBytes(it)} bytes)" },
  )
  assertNotNull(innermost.targetProvider.get())
  return "OK"
}
