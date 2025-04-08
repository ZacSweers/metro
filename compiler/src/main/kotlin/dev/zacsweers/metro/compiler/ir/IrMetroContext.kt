// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.LOG_PREFIX
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.mapToSet
import kotlin.system.measureTimeMillis
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCallConstructor
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
  val symbols: Symbols
  val options: MetroOptions
  val debug: Boolean
    get() = options.debug

  val irTypeSystemContext: IrTypeSystemContext

  fun loggerFor(type: MetroLogger.Type): MetroLogger

  fun IrMetroContext.log(message: String) {
    messageCollector.report(CompilerMessageSeverity.LOGGING, "$LOG_PREFIX $message")
  }

  fun IrMetroContext.logVerbose(message: String) {
    messageCollector.report(CompilerMessageSeverity.STRONG_WARNING, "$LOG_PREFIX $message")
  }

  fun IrDeclaration.reportError(message: String) {
    messageCollector.report(CompilerMessageSeverity.ERROR, message, locationOrNull())
  }

  fun reportError(message: String, location: CompilerMessageSourceLocation) {
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

  fun IrAnnotationContainer.mapKeyAnnotation() =
    annotationsIn(symbols.mapKeyAnnotations).singleOrNull()?.let(::IrAnnotation)

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
    return irCallConstructor(symbols.instanceFactoryConstructorSymbol, listOf(type)).apply {
      putValueArgument(0, arg)
    }
  }

  companion object {
    operator fun invoke(
      pluginContext: IrPluginContext,
      messageCollector: MessageCollector,
      symbols: Symbols,
      options: MetroOptions,
    ): IrMetroContext = SimpleIrMetroContext(pluginContext, messageCollector, symbols, options)

    private class SimpleIrMetroContext(
      override val pluginContext: IrPluginContext,
      override val messageCollector: MessageCollector,
      override val symbols: Symbols,
      override val options: MetroOptions,
    ) : IrMetroContext {
      override val irTypeSystemContext: IrTypeSystemContext =
        IrTypeSystemContextImpl(pluginContext.irBuiltIns)
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
    }
  }
}

internal inline fun IrMetroContext.timedComputation(name: String, block: () -> Unit) {
  val result = measureTimeMillis(block)
  log("$name took ${result}ms")
}
