// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.LOG_PREFIX
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.tracer
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
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
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.parentDeclarationsWithSelf
import org.jetbrains.kotlin.name.ClassId

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
   * Generic caching machinery. Add new caches as extension functions that encapsulate the
   * [cacheKey] and types.
   *
   * @param cacheKey A unique string identifier for this cache
   * @param key The key to cache under
   * @param compute The computation to perform if not cached
   */
  fun <K : Any, V : Any> getOrComputeCached(cacheKey: String, key: K, compute: () -> V): V

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

      override val reportsDir: Path? by lazy { options.reportsDestination?.createDirectories() }

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

      private val genericCaches: ConcurrentHashMap<String, ConcurrentHashMap<*, *>> =
        ConcurrentHashMap()

      override fun <K : Any, V : Any> getOrComputeCached(
        cacheKey: String,
        key: K,
        compute: () -> V,
      ): V {
        @Suppress("UNCHECKED_CAST")
        val cache =
          genericCaches.getOrPut(cacheKey) { ConcurrentHashMap<K, V>() } as MutableMap<K, V>
        return cache.getOrPut(key, compute)
      }
    }
  }
}

// Cache keys for IrMetroContext caches
private const val CACHE_TYPE_REMAPPERS = "type-remappers"
private const val CACHE_SUPERTYPES = "ir-type-supertypes"
private const val CACHE_SUPERTYPE_CLASS_IDS = "ir-type-supertype-class-ids"

/** Gets or computes a cached [TypeRemapper] for the given class and subtype. */
context(context: IrMetroContext)
internal fun getOrComputeTypeRemapper(
  classId: ClassId,
  subtype: IrType,
  compute: () -> TypeRemapper,
): TypeRemapper {
  val cacheKey = classId to subtype
  return context.getOrComputeCached(CACHE_TYPE_REMAPPERS, cacheKey, compute)
}

/**
 * Retrieves all supertypes of this [IrType], using a cache to avoid expensive recomputation.
 * Returns an empty set if this is not a simple type or has no raw type.
 *
 * This implementation is incremental: it caches at each level of the type hierarchy, so shared
 * ancestors are only computed once and reused across different types.
 */
context(context: IrMetroContext)
internal fun IrType.getOrComputeSupertypes(): Set<IrType> {
  return context.getOrComputeCached(CACHE_SUPERTYPES, this) {
    if (this !is IrSimpleType) return@getOrComputeCached emptySet()
    val rawTypeClass = rawTypeOrNull() ?: return@getOrComputeCached emptySet()

    // Use a set to avoid duplicates (e.g., from diamond inheritance)
    val result = mutableSetOf<IrType>()

    // Add this type itself
    result.add(this)

    // Recursively add supertypes of each immediate supertype
    // This leverages the cache at each level of the hierarchy
    for (superType in rawTypeClass.superTypes) {
      result.addAll(superType.getOrComputeSupertypes())
    }

    result
  }
}

/**
 * Retrieves all supertype ClassIds of this [IrType], using a cache for O(1) lookups. Computed
 * lazily from the cached supertypes.
 */
context(context: IrMetroContext)
internal fun IrType.getOrComputeSupertypeClassIds(): Set<ClassId> {
  return context.getOrComputeCached(CACHE_SUPERTYPE_CLASS_IDS, this) {
    // Derive ClassIds from the cached supertypes
    getOrComputeSupertypes().mapNotNullTo(mutableSetOf()) { it.rawTypeOrNull()?.classId }
  }
}

/** Checks if this [IrType] implements or extends the given [classId]. */
context(context: IrMetroContext)
internal fun IrType.implements(classId: ClassId): Boolean {
  return classId in getOrComputeSupertypeClassIds()
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
