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
package dev.zacsweers.lattice.compiler.ir

import dev.zacsweers.lattice.compiler.LOG_PREFIX
import dev.zacsweers.lattice.compiler.LatticeLogger
import dev.zacsweers.lattice.compiler.LatticeOptions
import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.lattice.compiler.mapToSet
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
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
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
internal interface LatticeTransformerContext {
  val latticeContext
    get() = this

  val pluginContext: IrPluginContext
  val messageCollector: MessageCollector
  val symbols: LatticeSymbols
  val options: LatticeOptions
  val debug: Boolean
    get() = options.debug

  fun loggerFor(type: LatticeLogger.Type): LatticeLogger

  fun LatticeTransformerContext.log(message: String) {
    messageCollector.report(CompilerMessageSeverity.LOGGING, "$LOG_PREFIX $message")
  }

  fun LatticeTransformerContext.logVerbose(message: String) {
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

  fun IrClass.dumpToLatticeLog() {
    val name =
      parentDeclarationsWithSelf.filterIsInstance<IrClass>().toList().asReversed().joinToString(
        separator = "."
      ) {
        it.name.asString()
      }
    dumpToLatticeLog(name = name)
  }

  fun IrElement.dumpToLatticeLog(name: String) {
    loggerFor(LatticeLogger.Type.GeneratedFactories).log {
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

  fun irType(
    classId: ClassId,
    nullable: Boolean = false,
    arguments: List<IrTypeArgument> = emptyList(),
  ) = pluginContext.irType(classId, nullable, arguments)

  fun IrProperty?.qualifierAnnotation(): IrAnnotation? {
    if (this == null) return null
    return allAnnotations
      .annotationsAnnotatedWith(symbols.qualifierAnnotations)
      .singleOrNull()
      ?.let(::IrAnnotation)
  }

  fun IrAnnotationContainer?.qualifierAnnotation() =
    annotationsAnnotatedWith(symbols.qualifierAnnotations).singleOrNull()?.let(::IrAnnotation)

  fun IrAnnotationContainer?.scopeAnnotation() = scopeAnnotations().singleOrNull()

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

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun List<IrConstructorCall>?.annotationsAnnotatedWith(
    annotationsToLookFor: Collection<ClassId>
  ): Set<IrConstructorCall> {
    if (this == null) return emptySet()
    return filterTo(LinkedHashSet()) {
      it.type.classOrNull?.owner?.isAnnotatedWithAny(annotationsToLookFor) == true
    }
  }

  val IrClass.isQualifierAnnotation: Boolean
    get() = isAnnotatedWithAny(symbols.qualifierAnnotations)

  val IrClass.isScopeAnnotation: Boolean
    get() = isAnnotatedWithAny(symbols.scopeAnnotations)

  @OptIn(UnsafeDuringIrConstructionAPI::class)
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
        typeHint = type.wrapInProvider(symbols.latticeFactory),
      )
      .apply { putTypeArgument(0, type) }
  }

  companion object {
    operator fun invoke(
      pluginContext: IrPluginContext,
      messageCollector: MessageCollector,
      symbols: LatticeSymbols,
      options: LatticeOptions,
    ): LatticeTransformerContext =
      SimpleLatticeTransformerContext(pluginContext, messageCollector, symbols, options)

    private class SimpleLatticeTransformerContext(
      override val pluginContext: IrPluginContext,
      override val messageCollector: MessageCollector,
      override val symbols: LatticeSymbols,
      override val options: LatticeOptions,
    ) : LatticeTransformerContext {
      private val loggerCache = mutableMapOf<LatticeLogger.Type, LatticeLogger>()

      override fun loggerFor(type: LatticeLogger.Type): LatticeLogger {
        return loggerCache.getOrPut(type) {
          if (type in options.enabledLoggers) {
            LatticeLogger(type, System.out::println)
          } else {
            LatticeLogger.NONE
          }
        }
      }
    }
  }
}
