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
    fun create(): DeltaFeatureLevelThreeGraphExtensionNameWithLongerSuffix
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
