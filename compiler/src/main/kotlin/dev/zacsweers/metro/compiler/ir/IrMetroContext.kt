// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.LOG_PREFIX
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.tracer
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.KotlinLikeDumpOptions
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.VisibilityPrintingStrategy
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.parentDeclarationsWithSelf
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.ClassId

// TODO make this extend IrPluginContext?
internal interface IrMetroContext {
  val metroContext
    get() = this

  val pluginContext: IrPluginContext
  val messageCollector: MessageCollector
  val diagnosticReporter: IrDiagnosticReporter
  val symbols: Symbols
  val options: MetroOptions
  val debug: Boolean
    get() = options.debug

  val lookupTracker: LookupTracker?

  val irTypeSystemContext: IrTypeSystemContext

  val reportsDir: Path?

  fun loggerFor(type: MetroLogger.Type): MetroLogger

  val logFile: Path?
  val traceLogFile: Path?
  val timingsFile: Path?

  val typeRemapperCache: MutableMap<Pair<ClassId, IrType>, TypeRemapper>

  fun log(message: String) {
    messageCollector.report(CompilerMessageSeverity.LOGGING, "$LOG_PREFIX $message")
    logFile?.appendText("\n$message")
  }

  fun logTrace(message: String) {
    messageCollector.report(CompilerMessageSeverity.LOGGING, "$LOG_PREFIX $message")
    traceLogFile?.appendText("$message\n")
  }

  fun logVerbose(message: String) {
    messageCollector.report(CompilerMessageSeverity.STRONG_WARNING, "$LOG_PREFIX $message")
  }

  fun logTiming(tag: String, description: String, durationMs: Long) {
    timingsFile?.appendText("\n$tag,$description,${durationMs}")
  }

  fun IrDeclaration.reportError(message: String) {
    messageCollector.report(CompilerMessageSeverity.ERROR, message, locationOrNull())
  }

  fun IrDeclaration.reportErrorDiagnostic(message: String) {
    TODO()
  }

  fun reportError(message: String, location: CompilerMessageSourceLocation?) {
    messageCollector.report(CompilerMessageSeverity.ERROR, message, location)
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
      val irSrc =
        dumpKotlinLike(
          KotlinLikeDumpOptions(visibilityPrintingStrategy = VisibilityPrintingStrategy.ALWAYS)
        )
      buildString {
        append("IR source dump for ")
        appendLine(name)
        appendLine(irSrc)
      }
    }
  }

  fun IrProperty?.qualifierAnnotation(): IrAnnotation? {
    if (this == null) return null
    return allAnnotations
      .annotationsAnnotatedWith(symbols.qualifierAnnotations)
      .singleOrNull()
      ?.let(::IrAnnotation)
  }

  fun IrAnnotationContainer?.qualifierAnnotation() =
    annotationsAnnotatedWith(symbols.qualifierAnnotations).singleOrNull()?.let(::IrAnnotation)

  fun IrAnnotationContainer?.scopeAnnotations() =
    annotationsAnnotatedWith(symbols.scopeAnnotations).mapToSet(::IrAnnotation)

  /** Returns the `@MapKey` annotation itself, not any annotations annotated _with_ `@MapKey`. */
  fun IrAnnotationContainer.explicitMapKeyAnnotation() =
    annotationsIn(symbols.mapKeyAnnotations).singleOrNull()?.let(::IrAnnotation)

  fun IrAnnotationContainer.mapKeyAnnotation() =
    annotationsAnnotatedWith(symbols.mapKeyAnnotations).singleOrNull()?.let(::IrAnnotation)

  private fun IrAnnotationContainer?.annotationsAnnotatedWith(
    annotationsToLookFor: Collection<ClassId>
  ): Set<IrConstructorCall> {
    if (this == null) return emptySet()
    return annotations.annotationsAnnotatedWith(annotationsToLookFor)
  }

  private fun List<IrConstructorCall>?.annotationsAnnotatedWith(
    annotationsToLookFor: Collection<ClassId>
  ): Set<IrConstructorCall> {
    if (this == null) return emptySet()
    return filterTo(LinkedHashSet()) {
      it.type.classOrNull?.owner?.isAnnotatedWithAny(annotationsToLookFor) == true
    }
  }

  fun IrClass.findInjectableConstructor(onlyUsePrimaryConstructor: Boolean): IrConstructor? {
    return if (onlyUsePrimaryConstructor || isAnnotatedWithAny(symbols.injectAnnotations)) {
      primaryConstructor
    } else {
      constructors.singleOrNull { constructor ->
        constructor.isAnnotatedWithAny(symbols.injectAnnotations)
      }
    }
  }

  // InstanceFactory(...)
  fun IrBuilderWithScope.instanceFactory(type: IrType, arg: IrExpression): IrExpression {
    return irInvoke(
      irGetObject(symbols.instanceFactoryCompanionObject),
      callee = symbols.instanceFactoryInvoke,
      typeArgs = listOf(type),
      args = listOf(arg),
    )
  }

  companion object {
    operator fun invoke(
      pluginContext: IrPluginContext,
      messageCollector: MessageCollector,
      symbols: Symbols,
      options: MetroOptions,
      lookupTracker: LookupTracker?,
    ): IrMetroContext =
      SimpleIrMetroContext(pluginContext, messageCollector, symbols, options, lookupTracker)

    private class SimpleIrMetroContext(
      override val pluginContext: IrPluginContext,
      override val messageCollector: MessageCollector,
      override val symbols: Symbols,
      override val options: MetroOptions,
      override val lookupTracker: LookupTracker?,
    ) : IrMetroContext {
      override val diagnosticReporter: IrDiagnosticReporter = pluginContext.diagnosticReporter
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

      override fun loggerFor(type: MetroLogger.Type): MetroLogger {
        return loggerCache.getOrPut(type) {
          if (type in options.enabledLoggers) {
            MetroLogger(type, System.out::println)
          } else {
            MetroLogger.NONE
          }
        }
      }

      override val typeRemapperCache: MutableMap<Pair<ClassId, IrType>, TypeRemapper> =
        mutableMapOf()
    }
  }
}

internal fun IrMetroContext.writeDiagnostic(fileName: String, text: () -> String) {
  writeDiagnostic({ fileName }, text)
}

internal fun IrMetroContext.writeDiagnostic(fileName: () -> String, text: () -> String) {
  reportsDir?.resolve(fileName())?.apply { deleteIfExists() }?.writeText(text())
}

internal fun IrMetroContext.tracer(tag: String, description: String): Tracer =
  if (traceLogFile != null || timingsFile != null || debug) {
    check(tag.isNotBlank()) { "Tag must not be blank" }
    check(description.isNotBlank()) { "description must not be blank" }
    tracer(tag, description, ::logTrace, ::logTiming)
  } else {
    Tracer.NONE
  }
