// METRO_JVM_ONLY
// ENABLE_DAGGER_INTEROP
// Generated class file names under a deep chain of graph extensions must stay within 255 bytes.

class DependencyTargetHolderForNameLengthChecks(
  val target: DependencyTargetForConvertedProviderNameLength
)

class DependencyTargetForConvertedProviderNameLength

@DependencyGraph
interface AlphaRootGraphForNameLengthChecks {
  fun betaFactory(): BetaFeatureLevelOneGraphExtensionName.Factory

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(
      @Includes holder: DependencyTargetHolderForNameLengthChecks
    ): AlphaRootGraphForNameLengthChecks
  }
}

@GraphExtension
interface BetaFeatureLevelOneGraphExtensionName {
  fun gammaFactory(): GammaFeatureLevelTwoGraphExtensionNameWithAdditionalDescriptiveText.Factory

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): BetaFeatureLevelOneGraphExtensionName
  }
}

@GraphExtension
interface GammaFeatureLevelTwoGraphExtensionNameWithAdditionalDescriptiveText {
  fun deltaFactory(): DeltaFeatureLevelThreeGraphExtensionNameWithLongerSuffix.Factory
  fun thetaFactory(): ThetaFeatureLevelThreeGraphExtensionNameWithLongerSuffix.Factory

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): GammaFeatureLevelTwoGraphExtensionNameWithAdditionalDescriptiveText
  }
}

@GraphExtension
interface DeltaFeatureLevelThreeGraphExtensionNameWithLongerSuffix {
  fun epsilonFactory(): EpsilonFeatureLevelFourGraphExtensionName.Factory

  @GraphExtension.Factory
  fun interface Factory {
    fun create(@Provides value: String): DeltaFeatureLevelThreeGraphExtensionNameWithLongerSuffix
  }
}

@GraphExtension
interface ThetaFeatureLevelThreeGraphExtensionNameWithLongerSuffix {
  fun epsilonFactory(): EpsilonFeatureLevelFourGraphExtensionName.Factory

  @GraphExtension.Factory
  fun interface Factory {
    fun create(@Provides value: String): ThetaFeatureLevelThreeGraphExtensionNameWithLongerSuffix
  }
}

@GraphExtension
interface EpsilonFeatureLevelFourGraphExtensionName {
  fun zetaFactory(): ZetaFeatureLevelFiveGraphExtensionName.Factory

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): EpsilonFeatureLevelFourGraphExtensionName
  }
}

@GraphExtension
interface ZetaFeatureLevelFiveGraphExtensionName {
  fun etaFactory(): EtaFeatureLevelSixGraphExtensionName.Factory

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ZetaFeatureLevelFiveGraphExtensionName
  }
}

@GraphExtension
interface EtaFeatureLevelSixGraphExtensionName {
  val inheritedValue: String
  val targetProvider: javax.inject.Provider<DependencyTargetForConvertedProviderNameLength>

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): EtaFeatureLevelSixGraphExtensionName
  }
}

fun Class<*>.nestedClassNamesRecursive(): List<String> =
  declaredClasses.flatMap { listOf(it.name) + it.nestedClassNamesRecursive() }

fun classFileNameBytes(binaryName: String): Int =
  (binaryName.substringAfterLast('.') + ".class").encodeToByteArray().size

fun box(): String {
  val root =
    createGraphFactory<AlphaRootGraphForNameLengthChecks.Factory>()
      .create(
        DependencyTargetHolderForNameLengthChecks(DependencyTargetForConvertedProviderNameLength())
      )
  val gamma = root.betaFactory().create().gammaFactory().create()
  val epsilon = gamma.deltaFactory().create("from Delta").epsilonFactory().create()
  val otherEpsilon = gamma.thetaFactory().create("from Theta").epsilonFactory().create()
  // Both branches emit an Epsilon implementation under Gamma.
  assertEquals(gamma.javaClass, epsilon.javaClass.enclosingClass)
  assertEquals(gamma.javaClass, otherEpsilon.javaClass.enclosingClass)
  assertNotEquals(epsilon.javaClass, otherEpsilon.javaClass)
  val innermost = epsilon.zetaFactory().create().etaFactory().create()
  val generatedNames =
    listOf(innermost.javaClass.name) + innermost.javaClass.nestedClassNamesRecursive()
  val overLimit = generatedNames.filter { classFileNameBytes(it) > 255 }
  assertTrue(
    overLimit.isEmpty(),
    "class file names over 255 bytes: " +
      overLimit.joinToString { "$it (${classFileNameBytes(it)} bytes)" },
  )
  assertNotNull(innermost.targetProvider.get())
  assertEquals("from Delta", innermost.inheritedValue)
  val otherInnermost = otherEpsilon.zetaFactory().create().etaFactory().create()
  assertEquals("from Theta", otherInnermost.inheritedValue)
  return "OK"
}
