// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import androidx.tracing.AbstractTraceDriver
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.compiler.LOG_PREFIX
import dev.zacsweers.metro.compiler.MessageRenderer
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.IrGeneratedDeclarationsRegistrarCompat
import dev.zacsweers.metro.compiler.createDiagnosticReportPath
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.ir.cache.IrCache
import dev.zacsweers.metro.compiler.ir.cache.IrCachesFactory
import dev.zacsweers.metro.compiler.ir.cache.IrThreadUnsafeCachesFactory
import dev.zacsweers.metro.compiler.symbols.Symbols
import java.io.File
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.components.Position
import org.jetbrains.kotlin.incremental.components.ScopeKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.util.parentDeclarationsWithSelf

internal interface IrMetroContext : IrPluginContext, CompatContext {
  // TODO inline extension?
  val metroContext
    get() = this

  val pluginContext: IrPluginContext
  val metadataDeclarationRegistrarCompat: IrGeneratedDeclarationsRegistrarCompat
  val metroSymbols: Symbols
  val options: MetroOptions
  val debug: Boolean
    get() = options.debug

  val lookupTracker: LookupTracker?
  val expectActualTracker: ExpectActualTracker

  val messageRenderer: MessageRenderer

  val irTypeSystemContext: IrTypeSystemContext

  val reportsDir: Path?

  val traceDriver: AbstractTraceDriver

  fun loggerFor(type: MetroLogger.Type): MetroLogger

  val logFile: Path?
  val lookupFile: Path?
  val expectActualFile: Path?

  /**
   * Generic caching machinery. Add new caches as extension functions that encapsulate the [key] and
   * types.
   *
   * @param key A unique string identifier for this cache
   */
  fun <K : Any, V : Any, C> getOrCreateIrCache(
    key: Any,
    createCache: (IrCachesFactory) -> IrCache<K, V, C>,
  ): IrCache<K, V, C>

  fun onErrorReported()

  fun log(message: String) {
    @Suppress("DEPRECATION")
    messageCollector.report(CompilerMessageSeverity.LOGGING, "$LOG_PREFIX $message")
    logFile?.appendText("$message\n")
  }

  /**
   * Deferred-evaluation log. The message is only built when [logFile] is set. Prefer this over
   * [log] for hot paths (e.g. per-class transformer work) where building the message is non-trivial
   * (FQ-name traversals, `buildString`, etc.).
   */
  fun log(message: () -> String) {
    val file = logFile ?: return
    val rendered = message()
    @Suppress("DEPRECATION")
    messageCollector.report(CompilerMessageSeverity.LOGGING, "$LOG_PREFIX $rendered")
    file.appendText("$rendered\n")
  }

  fun logVerbose(message: String) {
    @Suppress("DEPRECATION")
    messageCollector.report(CompilerMessageSeverity.STRONG_WARNING, "$LOG_PREFIX $message")
  }

  fun logLookup(
    filePath: String,
    position: Position,
    scopeFqName: String,
    scopeKind: ScopeKind,
    name: String,
  ) {
    lookupFile?.appendText(
      "\n${filePath.substringAfterLast(File.separatorChar)},${position.line}:${position.column},$scopeFqName,$scopeKind,$name"
    )
  }

  fun logExpectActualReport(expectedFile: File, actualFile: File?) {
    expectActualFile?.appendText("\n${expectedFile.name},${actualFile?.name}")
  }

  fun IrClass.dumpToMetroLog() {
    val name =
      parentDeclarationsWithSelf.filterIsInstance<IrClass>().toList().asReversed().joinToString(
        separator = "."
      ) {
        it.name.asString()
      }
    dumpToMetroLog(name = name)
  }

  fun IrElement.dumpToMetroLog(name: String) {
    loggerFor(MetroLogger.Type.GeneratedFactories).log {
      val irSrc = betterDumpKotlinLike()
      buildString {
        append("IR source dump for ")
        appendLine(name)
        appendLine(irSrc)
      }
    }
  }
}

@Inject
@SingleIn(IrScope::class)
@ContributesBinding(IrScope::class, binding = binding<IrMetroContext>())
internal class IrMetroContextImpl(
  compatContext: CompatContext,
  override val pluginContext: IrPluginContext,
  @Suppress("DEPRECATION")
  @Deprecated(
    "Consider using diagnosticReporter instead. See https://youtrack.jetbrains.com/issue/KT-78277 for more details"
  )
  override val messageCollector: MessageCollector,
  symbols: Symbols,
  override val options: MetroOptions,
  rawLookupTracker: LookupTracker?,
  rawExpectActualTracker: ExpectActualTracker,
  override val traceDriver: AbstractTraceDriver,
  override val messageRenderer: MessageRenderer,
  override val irTypeSystemContext: IrTypeSystemContext,
  override val metadataDeclarationRegistrarCompat: IrGeneratedDeclarationsRegistrarCompat,
  @ReportFile("log.txt") logFile: Lazy<Path?>,
  @ReportFile("lookups.csv") lookupFile: Lazy<Path?>,
  @ReportFile("expectActualReports.csv") expectActualFile: Lazy<Path?>,
) : IrMetroContext, IrPluginContext by pluginContext, CompatContext by compatContext {
  override val metroSymbols: Symbols = symbols

  override val logFile: Path? by logFile
  override val lookupFile: Path? by lookupFile
  override val expectActualFile: Path? by expectActualFile

  private var reportedErrors = 0

  override fun onErrorReported() {
    reportedErrors++
    if (reportedErrors >= options.maxIrErrorsCount) {
      // Exit processing as we've reached the max
      exitProcessing()
    }
  }

  override val lookupTracker: LookupTracker? = rawLookupTracker?.let {
    if (options.reportsEnabled) RecordingLookupTracker(this, it) else it
  }

  override val expectActualTracker: ExpectActualTracker =
    if (options.reportsEnabled) RecordingExpectActualTracker(this, rawExpectActualTracker)
    else rawExpectActualTracker

  override val reportsDir: Path?
    get() = options.reportsDir.value

  private val loggerCache = mutableMapOf<MetroLogger.Type, MetroLogger>()

  override fun loggerFor(type: MetroLogger.Type): MetroLogger {
    return loggerCache.getOrPut(type) {
      if (type in options.enabledLoggers) {
        MetroLogger(type, System.out::println)
      } else {
        MetroLogger.NONE
      }
    }
  }

  private val genericCaches: HashMap<Any, IrCache<*, *, *>> = HashMap()

  override fun <K : Any, V : Any, C> getOrCreateIrCache(
    key: Any,
    createCache: (IrCachesFactory) -> IrCache<K, V, C>,
  ): IrCache<K, V, C> {
    @Suppress("UNCHECKED_CAST")
    return genericCaches.getOrPut(key) { createCache(IrThreadUnsafeCachesFactory) }
      as IrCache<K, V, C>
  }
}

/** Builds a diagnostic message string using the [MessageRenderer.MessageBuilder] DSL. */
internal inline fun IrMetroContext.renderDiagnostic(
  body: MessageRenderer.MessageBuilder.() -> Unit
): String = messageRenderer.buildMessage(body)

/** See the other [writeDiagnostic] */
context(context: IrMetroContext)
internal fun writeDiagnostic(diagnosticKey: String, fileName: String, text: () -> String) {
  writeDiagnostic(diagnosticKey, { fileName }, text)
}

/**
 * @param diagnosticKey A string identifier for the category of diagnostic being generated. This
 *   will be treated as a prefix path segment. E.g. a key of "keys-populated" will result in
 *   <reports-folder>/keys-populated/<fileName>
 */
context(context: IrMetroContext)
internal fun writeDiagnostic(diagnosticKey: String, fileName: () -> String, text: () -> String) {
  context.reportsDir
    ?.resolve(createDiagnosticReportPath(diagnosticKey, fileName()))
    ?.apply {
      // Ensure that the path leading up to the file has been created
      createParentDirectories()
      deleteIfExists()
    }
    ?.writeText(text())
}
