/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.LOG_PREFIX
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.mapToSet
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
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
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.KotlinLikeDumpOptions
import org.jetbrains.kotlin.ir.util.VisibilityPrintingStrategy
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.parentClassOrNull
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

  fun loggerFor(type: MetroLogger.Type): MetroLogger

  fun IrMetroContext.log(message: String) {
    messageCollector.report(CompilerMessageSeverity.LOGGING, "$LOG_PREFIX $message")
  }

  fun IrMetroContext.logVerbose(message: String) {
    messageCollector.report(CompilerMessageSeverity.STRONG_WARNING, "$LOG_PREFIX $message")
  }

  fun IrDeclaration.reportError(message: String) {
    val location =
      this.locationOrNull()
        ?: error(
          "No location for ${dumpKotlinLike()} in class\n${parentClassOrNull?.dumpKotlinLike()}.\n\nMessage:\n$message"
        )
    messageCollector.report(CompilerMessageSeverity.ERROR, message, location)
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

  // InstanceFactory.create(...)
  fun IrBuilderWithScope.instanceFactory(type: IrType, arg: IrExpression): IrExpression {
    return irInvoke(
        dispatchReceiver = irGetObject(symbols.instanceFactoryCompanionObject),
        callee = symbols.instanceFactoryCreate,
        args = listOf(arg),
        typeHint = type.wrapInProvider(symbols.metroFactory),
      )
      .apply { putTypeArgument(0, type) }
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
