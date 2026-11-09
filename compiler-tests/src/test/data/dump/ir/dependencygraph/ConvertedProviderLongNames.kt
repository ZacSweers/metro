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
