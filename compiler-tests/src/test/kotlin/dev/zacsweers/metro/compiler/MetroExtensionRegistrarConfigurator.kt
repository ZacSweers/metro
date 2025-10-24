// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroFirExtensionRegistrar
import dev.zacsweers.metro.compiler.interop.Ksp2AdditionalSourceProvider
import dev.zacsweers.metro.compiler.interop.configureAnvilAnnotations
import dev.zacsweers.metro.compiler.interop.configureDaggerAnnotations
import dev.zacsweers.metro.compiler.interop.configureDaggerInterop
import dev.zacsweers.metro.compiler.ir.MetroIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.model.singleOrZeroValue
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices

fun TestConfigurationBuilder.configurePlugin() {
  useConfigurators(::MetroExtensionRegistrarConfigurator, ::MetroRuntimeEnvironmentConfigurator)

  useDirectives(MetroDirectives)

  useCustomRuntimeClasspathProviders(::MetroRuntimeClassPathProvider)

  useSourcePreprocessor(::MetroDefaultImportPreprocessor)

  configureAnvilAnnotations()
  configureDaggerAnnotations()
  configureDaggerInterop()
  useAdditionalSourceProviders(::Ksp2AdditionalSourceProvider)
}

class MetroExtensionRegistrarConfigurator(testServices: TestServices) :
  EnvironmentConfigurator(testServices) {
  @OptIn(ExperimentalCompilerApi::class)
  override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
    module: TestModule,
    configuration: CompilerConfiguration,
  ) {
    val transformProvidersToPrivate =
      MetroDirectives.DISABLE_TRANSFORM_PROVIDERS_TO_PRIVATE !in module.directives
    val addDaggerAnnotations =
      MetroDirectives.WITH_DAGGER in module.directives ||
        MetroDirectives.ENABLE_DAGGER_INTEROP in module.directives ||
        MetroDirectives.ENABLE_DAGGER_KSP in module.directives ||
        MetroDirectives.ENABLE_ANVIL_KSP in module.directives

    val optionDefaults = MetroOptions()

    val options =
      MetroOptions(
        enableDaggerRuntimeInterop = MetroDirectives.enableDaggerRuntimeInterop(module.directives),
        generateAssistedFactories =
          MetroDirectives.GENERATE_ASSISTED_FACTORIES in module.directives,
        transformProvidersToPrivate = transformProvidersToPrivate,
        enableTopLevelFunctionInjection =
          MetroDirectives.ENABLE_TOP_LEVEL_FUNCTION_INJECTION in module.directives,
        shrinkUnusedBindings =
          module.directives.singleOrZeroValue(MetroDirectives.SHRINK_UNUSED_BINDINGS)
            ?: optionDefaults.shrinkUnusedBindings,
        chunkFieldInits =
          module.directives.singleOrZeroValue(MetroDirectives.CHUNK_FIELD_INITS)
            ?: optionDefaults.chunkFieldInits,
        enableFullBindingGraphValidation =
          MetroDirectives.ENABLE_FULL_BINDING_GRAPH_VALIDATION in module.directives,
        enableGraphImplClassAsReturnType =
          MetroDirectives.ENABLE_GRAPH_IMPL_CLASS_AS_RETURN_TYPE in module.directives,
        generateJvmContributionHintsInFir =
          MetroDirectives.GENERATE_JVM_CONTRIBUTION_HINTS_IN_FIR in module.directives,
        publicProviderSeverity =
          if (transformProvidersToPrivate) {
            MetroOptions.DiagnosticSeverity.NONE
          } else {
            module.directives.singleOrZeroValue(MetroDirectives.PUBLIC_PROVIDER_SEVERITY)
              ?: optionDefaults.publicProviderSeverity
          },
        optionalBindingBehavior =
          module.directives.singleOrZeroValue(MetroDirectives.OPTIONAL_DEPENDENCY_BEHAVIOR)
            ?: optionDefaults.optionalBindingBehavior,
        interopAnnotationsNamedArgSeverity =
          module.directives.singleOrZeroValue(
            MetroDirectives.INTEROP_ANNOTATIONS_NAMED_ARG_SEVERITY
          ) ?: optionDefaults.interopAnnotationsNamedArgSeverity,
        maxIrErrorsCount =
          module.directives.singleOrZeroValue(MetroDirectives.MAX_IR_ERRORS_COUNT) ?: 20,
        contributesAsInject = MetroDirectives.CONTRIBUTES_AS_INJECT in module.directives,
        enableDaggerAnvilInterop =
          MetroDirectives.WITH_ANVIL in module.directives ||
            MetroDirectives.ENABLE_ANVIL_KSP in module.directives,
        customGraphAnnotations =
          buildSet {
            if (
              MetroDirectives.WITH_ANVIL in module.directives ||
                MetroDirectives.ENABLE_ANVIL_KSP in module.directives
            ) {
              add(ClassId.fromString("com/squareup/anvil/annotations/MergeComponent"))
            }
            if (addDaggerAnnotations) {
              add(ClassId.fromString("dagger/Component"))
            }
          },
        customAssistedAnnotations =
          buildSet {
            if (addDaggerAnnotations) {
              add(ClassId.fromString("dagger/assisted/Assisted"))
            }
          },
        customAssistedFactoryAnnotations =
          buildSet {
            if (addDaggerAnnotations) {
              add(ClassId.fromString("dagger/assisted/AssistedFactory"))
            }
          },
        customAssistedInjectAnnotations =
          buildSet {
            if (addDaggerAnnotations) {
              add(ClassId.fromString("dagger/assisted/AssistedInject"))
            }
          },
        customGraphFactoryAnnotations =
          buildSet {
            if (
              MetroDirectives.WITH_ANVIL in module.directives ||
                MetroDirectives.ENABLE_ANVIL_KSP in module.directives
            ) {
              add(ClassId.fromString("com/squareup/anvil/annotations/MergeComponent.Factory"))
            }
            if (addDaggerAnnotations) {
              add(ClassId.fromString("dagger/Component.Factory"))
            }
          },
        customContributesToAnnotations =
          buildSet {
            if (
              MetroDirectives.WITH_ANVIL in module.directives ||
                MetroDirectives.ENABLE_ANVIL_KSP in module.directives
            ) {
              add(ClassId.fromString("com/squareup/anvil/annotations/ContributesTo"))
            }
          },
        customContributesBindingAnnotations =
          buildSet {
            if (
              MetroDirectives.WITH_ANVIL in module.directives ||
                MetroDirectives.ENABLE_ANVIL_KSP in module.directives
            ) {
              add(ClassId.fromString("com/squareup/anvil/annotations/ContributesBinding"))
            }
          },
        customContributesIntoSetAnnotations =
          buildSet {
            if (
              MetroDirectives.WITH_ANVIL in module.directives ||
                MetroDirectives.ENABLE_ANVIL_KSP in module.directives
            ) {
              add(ClassId.fromString("com/squareup/anvil/annotations/ContributesMultibinding"))
            }
          },
        customGraphExtensionAnnotations =
          buildSet {
            if (MetroDirectives.ENABLE_DAGGER_INTEROP in module.directives) {
              add(ClassId.fromString("dagger/Subcomponent"))
            }
            if (
              MetroDirectives.WITH_ANVIL in module.directives ||
                MetroDirectives.ENABLE_ANVIL_KSP in module.directives
            ) {
              add(ClassId.fromString("com/squareup/anvil/annotations/ContributesSubcomponent"))
            }
          },
        customGraphExtensionFactoryAnnotations =
          buildSet {
            if (MetroDirectives.ENABLE_DAGGER_INTEROP in module.directives) {
              add(ClassId.fromString("dagger/Subcomponent.Factory"))
            }
          },
        customInjectAnnotations =
          buildSet {
            if (addDaggerAnnotations) {
              add(ClassId.fromString("javax/inject/Inject"))
              add(ClassId.fromString("jakarta/inject/Inject"))
            }
          },
        customQualifierAnnotations =
          buildSet {
            if (addDaggerAnnotations) {
              add(ClassId.fromString("javax/inject/Qualifier"))
              add(ClassId.fromString("jakarta/inject/Qualifier"))
            }
          },
        customProviderTypes =
          buildSet {
            if (addDaggerAnnotations) {
              add(ClassId.fromString("javax/inject/Provider"))
              add(ClassId.fromString("jakarta/inject/Provider"))
              add(ClassId.fromString("dagger/internal/Provider"))
            }
          },
        customProvidesAnnotations =
          buildSet {
            if (addDaggerAnnotations) {
              add(ClassId.fromString("dagger/Provides"))
            }
          },
        customBindsAnnotations =
          buildSet {
            if (addDaggerAnnotations) {
              add(ClassId.fromString("dagger/Binds"))
            }
          },
        customLazyTypes =
          buildSet {
            if (addDaggerAnnotations) {
              add(ClassId.fromString("dagger/Lazy"))
            }
          },
        customBindingContainerAnnotations =
          buildSet {
            if (addDaggerAnnotations) {
              add(ClassId.fromString("dagger/Module"))
            }
          },
        customMultibindsAnnotations =
          buildSet {
            if (addDaggerAnnotations) {
              add(ClassId.fromString("dagger/multibindings/Multibinds"))
            }
          },
        customMapKeyAnnotations =
          buildSet {
            if (addDaggerAnnotations) {
              add(ClassId.fromString("dagger/MapKey"))
            }
          },
        customOriginAnnotations =
          buildSet {
            if (MetroDirectives.WITH_KI_ANVIL in module.directives) {
              add(
                ClassId.fromString("software/amazon/lastmile/kotlin/inject/anvil/internal/Origin")
              )
            }
          },
        // TODO other dagger annotations/types not yet implemented
      )
    val classIds = ClassIds.fromOptions(options)
    val compatContext = CompatContext.getInstance()
    FirExtensionRegistrarAdapter.registerExtension(
      MetroFirExtensionRegistrar(classIds, options, compatContext)
    )
    IrGenerationExtension.registerExtension(
      MetroIrGenerationExtension(
        messageCollector = configuration.messageCollector,
        classIds = classIds,
        options = options,
        // TODO ever support this in tests?
        lookupTracker = null,
        expectActualTracker = ExpectActualTracker.DoNothing,
        compatContext = compatContext,
      )
    )
  }
}
