// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import androidx.tracing.AbstractTraceDriver
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.MemberNamingStrategy
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.ir.MetroIrContributionExtension
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.IrGeneratedDeclarationsRegistrarCompat
import dev.zacsweers.metro.compiler.diagnostics.render.DiagnosticRenderer
import dev.zacsweers.metro.compiler.diagnostics.render.SourceFileCache
import dev.zacsweers.metro.compiler.diagnostics.render.renderProfileFor
import dev.zacsweers.metro.compiler.diagnostics.render.resolveDiagnosticsRenderMode
import dev.zacsweers.metro.compiler.ir.graph.expressions.BindingExpressionDecorator
import dev.zacsweers.metro.compiler.ir.graph.expressions.RuntimeTracingBindingExpressionDecorator
import dev.zacsweers.metro.compiler.tracing.TraceContext
import dev.zacsweers.metro.compiler.tracing.TraceScope
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.concurrent.ForkJoinPool
import kotlin.io.path.appendText
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl

internal abstract class IrScope private constructor()

@Qualifier internal annotation class SyntheticGraphs

@Qualifier internal annotation class ReportFile(val name: String)

@DependencyGraph(IrScope::class)
internal interface IrDependencyGraph {

  val pipeline: MetroIrPipeline

  @Provides
  @SingleIn(IrScope::class)
  fun provideForkJoinPool(options: MetroOptions): ForkJoinPool? {
    return if (options.parallelThreads > 0) {
      ForkJoinPool(options.parallelThreads)
    } else {
      null
    }
  }

  @Provides
  @SyntheticGraphs
  @SingleIn(IrScope::class)
  fun provideSyntheticGraphs(): MutableList<GraphToProcess> = mutableListOf()

  @Provides
  @SingleIn(IrScope::class)
  fun provideTraceDriver(
    traceContext: TraceContext,
    moduleFragment: IrModuleFragment,
  ): AbstractTraceDriver {
    // One IR driver per fragment (per IrScope), with filename `<id>-ir-<moduleName>.perfetto-trace`
    // sharing the holder's compilation id.
    return traceContext.newIrDriver(moduleFragment.name.asString())
  }

  @Provides
  @SingleIn(IrScope::class)
  fun provideDiagnosticRenderer(
    options: MetroOptions,
    sourceFileCache: SourceFileCache,
  ): DiagnosticRenderer =
    DiagnosticRenderer(
      renderProfileFor(options.resolveDiagnosticsRenderMode()),
      sourceLines = sourceFileCache::linesFor,
    )

  @Provides
  @SingleIn(IrScope::class)
  fun provideIrTypeSystemContext(pluginContext: IrPluginContext): IrTypeSystemContext =
    IrTypeSystemContextImpl(pluginContext.irBuiltIns)

  @Provides
  @SingleIn(IrScope::class)
  fun provideMetadataRegistrar(
    pluginContext: IrPluginContext,
    compatContext: CompatContext,
  ): IrGeneratedDeclarationsRegistrarCompat =
    compatContext.createIrGeneratedDeclarationsRegistrar(pluginContext)

  /**
   * Base [MemberNamer] for generated graph/factory/members-injector members in this compilation,
   * derived from [MetroOptions.memberNamingStrategy]. Nested-shard generation may override locally
   * to [MemberNamer.Minimal] when the strategy is not [MemberNamingStrategy.DESCRIPTIVE].
   */
  @Provides
  fun provideMemberNamer(options: MetroOptions): MemberNamer =
    when (options.memberNamingStrategy) {
      MemberNamingStrategy.DESCRIPTIVE -> MemberNamer.Descriptive
      MemberNamingStrategy.TYPED -> MemberNamer.Typed
      MemberNamingStrategy.MINIMAL -> MemberNamer.Minimal
    }

  @Provides
  @SingleIn(IrScope::class)
  fun provideBindingExpressionDecorator(
    context: IrMetroContext,
    realDecorator: () -> RuntimeTracingBindingExpressionDecorator,
  ): BindingExpressionDecorator {
    return if (context.runtimeTracingAvailable()) {
      realDecorator()
    } else {
      BindingExpressionDecorator.None
    }
  }

  @Provides
  @SingleIn(IrScope::class)
  @ReportFile("log.txt")
  fun provideLogFile(options: MetroOptions): Path? =
    options.reportsDir.value?.resolve("log.txt")?.apply {
      deleteIfExists()
      createFile()
    }

  @Provides
  @SingleIn(IrScope::class)
  @ReportFile("lookups.csv")
  fun provideLookupFile(options: MetroOptions): Path? =
    options.reportsDir.value?.resolve("lookups.csv")?.apply {
      deleteIfExists()
      createFile()
      appendText("file,position,scopeFqName,scopeKind,name")
    }

  @Provides
  @SingleIn(IrScope::class)
  @ReportFile("expectActualReports.csv")
  fun provideExpectActualFile(options: MetroOptions): Path? =
    options.reportsDir.value?.resolve("expectActualReports.csv")?.apply {
      deleteIfExists()
      createFile()
      appendText("expected,actual")
    }

  @Provides
  @SingleIn(IrScope::class)
  fun provideTraceScope(
    traceDriver: AbstractTraceDriver,
    moduleFragment: IrModuleFragment,
  ): TraceScope {
    val moduleName =
      moduleFragment.name.asString().removePrefix("<").removeSuffix(">").ifBlank { "ir" }
    return TraceScope(traceDriver.tracer, "ir-$moduleName")
  }

  @Provides
  @SingleIn(IrScope::class)
  fun provideBuiltinsFinder(
    pluginContext: IrPluginContext,
    compatContext: CompatContext,
  ): CompatContext.DeclarationFinderCompat =
    with(compatContext) { pluginContext.finderForBuiltinsCompat() }

  @Provides
  @SingleIn(IrScope::class)
  fun provideIrContributionExtensions(
    pluginContext: IrPluginContext,
    compatContext: CompatContext,
    options: MetroOptions,
  ): List<MetroIrContributionExtension> {
    return ServiceLoader.load(
        MetroIrContributionExtension.Factory::class.java,
        MetroIrContributionExtension.Factory::class.java.classLoader,
      )
      .mapNotNull { factory ->
        try {
          factory.create(pluginContext, compatContext, options)
        } catch (e: Exception) {
          if (options.debug) {
            System.err.println(
              "[Metro] Failed to load external IR contribution extension from ${factory::class}: ${e.message}"
            )
          }
          null
        }
      }
  }

  @DependencyGraph.Factory
  interface Factory {
    fun create(
      @Provides messageCollector: MessageCollector,
      @Provides classIds: ClassIds,
      @Provides options: MetroOptions,
      @Provides lookupTracker: LookupTracker?,
      @Provides expectActualTracker: ExpectActualTracker,
      @Provides compatContext: CompatContext,
      @Provides moduleFragment: IrModuleFragment,
      @Provides pluginContext: IrPluginContext,
      @Provides traceContext: TraceContext,
    ): IrDependencyGraph
  }
}
