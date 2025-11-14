// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.LOG_PREFIX
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.ir.cache.IrCache
import dev.zacsweers.metro.compiler.ir.cache.IrCachesFactory
import dev.zacsweers.metro.compiler.ir.cache.IrThreadUnsafeCachesFactory
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.tracer
import java.io.File
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
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
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.parentDeclarationsWithSelf

internal interface IrMetroContext : IrPluginContext, CompatContext {
  val metroContext
    get() = this

  val pluginContext: IrPluginContext
  val metroSymbols: Symbols
  val options: MetroOptions
  val debug: Boolean
    get() = options.debug

  val lookupTracker: LookupTracker?
  val expectActualTracker: ExpectActualTracker

  val irTypeSystemContext: IrTypeSystemContext

  val reportsDir: Path?

  fun loggerFor(type: MetroLogger.Type): MetroLogger

  val logFile: Path?
  val traceLogFile: Path?
  val timingsFile: Path?
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

  fun logTrace(message: String) {
    @Suppress("DEPRECATION")
    messageCollector.report(CompilerMessageSeverity.LOGGING, "$LOG_PREFIX $message")
    traceLogFile?.appendText("$message\n")
  }

  fun logVerbose(message: String) {
    @Suppress("DEPRECATION")
    messageCollector.report(CompilerMessageSeverity.STRONG_WARNING, "$LOG_PREFIX $message")
  }

  fun logTiming(tag: String, description: String, durationMs: Long) {
    timingsFile?.appendText("\n$tag,$description,${durationMs}")
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
      val irSrc = dumpKotlinLike()
      buildString {
        append("IR source dump for ")
        appendLine(name)
        appendLine(irSrc)
      }
    }
  }

  companion object {
    private val reportsDirNameFormatter =
      DateTimeFormatterBuilder()
        .appendValue(ChronoField.YEAR, 4)
        .appendValue(ChronoField.MONTH_OF_YEAR, 2)
        .appendValue(ChronoField.DAY_OF_MONTH, 2)
        .appendLiteral('T')
        .appendValue(ChronoField.HOUR_OF_DAY, 2)
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .optionalStart()
        .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
        .optionalStart()
        .appendLiteral('_')
        .appendFraction(ChronoField.NANO_OF_SECOND, 1, 1, false)
        .toFormatter()

    operator fun invoke(
      pluginContext: IrPluginContext,
      messageCollector: MessageCollector,
      compatContext: CompatContext,
      symbols: Symbols,
      options: MetroOptions,
      lookupTracker: LookupTracker?,
      expectActualTracker: ExpectActualTracker,
    ): IrMetroContext {
      return SimpleIrMetroContext(
        compatContext,
        pluginContext,
        messageCollector,
        symbols,
        options,
        lookupTracker,
        expectActualTracker,
      )
    }

    private class SimpleIrMetroContext(
      compatContext: CompatContext,
      override val pluginContext: IrPluginContext,
      @Suppress("DEPRECATION")
      @Deprecated(
        "Consider using diagnosticReporter instead. See https://youtrack.jetbrains.com/issue/KT-78277 for more details"
      )
      override val messageCollector: MessageCollector,
      override val metroSymbols: Symbols,
      override val options: MetroOptions,
      lookupTracker: LookupTracker?,
      expectActualTracker: ExpectActualTracker,
    ) : IrMetroContext, IrPluginContext by pluginContext, CompatContext by compatContext {
      private var reportedErrors = 0

      override fun onErrorReported() {
        reportedErrors++
        if (reportedErrors >= options.maxIrErrorsCount) {
          // Exit processing as we've reached the max
          exitProcessing()
        }
      }

      override val lookupTracker: LookupTracker? =
        lookupTracker?.let {
          if (options.reportsDestination != null) {
            RecordingLookupTracker(this, lookupTracker)
          } else {
            lookupTracker
          }
        }

      override val expectActualTracker: ExpectActualTracker =
        if (options.reportsDestination != null) {
          RecordingExpectActualTracker(this, expectActualTracker)
        } else {
          expectActualTracker
        }

      override val irTypeSystemContext: IrTypeSystemContext =
        IrTypeSystemContextImpl(pluginContext.irBuiltIns)

      private val loggerCache = mutableMapOf<MetroLogger.Type, MetroLogger>()

      @OptIn(ExperimentalPathApi::class)
      override val reportsDir: Path? by lazy {
        options.reportsDestination
          ?.resolve(reportsDirNameFormatter.format(LocalDateTime.now()))
          ?.run {
            if (exists()) {
              deleteRecursively()
            }
            createDirectories()
          }
      }

      override val logFile: Path? by lazy {
        reportsDir?.let {
          it.resolve("log.txt").apply {
            deleteIfExists()
            createFile()
          }
        }
      }
      override val traceLogFile: Path? by lazy {
        reportsDir?.let {
          it.resolve("traceLog.txt").apply {
            deleteIfExists()
            createFile()
          }
        }
      }

      override val timingsFile: Path? by lazy {
        reportsDir?.let {
          it.resolve("timings.csv").apply {
            deleteIfExists()
            createFile()
            appendText("tag,description,durationMs")
          }
        }
      }

      override val lookupFile: Path? by lazy {
        reportsDir?.let {
          it.resolve("lookups.csv").apply {
            deleteIfExists()
            createFile()
            appendText("file,position,scopeFqName,scopeKind,name")
          }
        }
      }

      override val expectActualFile: Path? by lazy {
        reportsDir?.let {
          it.resolve("expectActualReports.csv").apply {
            deleteIfExists()
            createFile()
            appendText("expected,actual")
          }
        }
      }

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
  }
}

context(context: IrMetroContext)
internal fun writeDiagnostic(fileName: String, text: () -> String) {
  writeDiagnostic({ fileName }, text)
}

context(context: IrMetroContext)
internal fun writeDiagnostic(fileName: () -> String, text: () -> String) {
  context.reportsDir
    ?.resolve(fileName())
    ?.apply {
      // Ensure that the path leading up to the file has been created
      createParentDirectories()
      deleteIfExists()
    }
    ?.writeText(text())
}

context(context: IrMetroContext)
internal fun tracer(tag: String, description: String): Tracer =
  if (context.traceLogFile != null || context.timingsFile != null || context.debug) {
    check(tag.isNotBlank()) { "Tag must not be blank" }
    check(description.isNotBlank()) { "description must not be blank" }
    tracer(tag, description, context::logTrace, context::logTiming)
  } else {
    Tracer.NONE
  }
