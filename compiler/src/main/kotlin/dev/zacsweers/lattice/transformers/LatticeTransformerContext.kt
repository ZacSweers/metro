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
package dev.zacsweers.lattice.transformers

import dev.zacsweers.lattice.LOG_PREFIX
import dev.zacsweers.lattice.LatticeSymbols
import dev.zacsweers.lattice.ir.IrAnnotation
import dev.zacsweers.lattice.ir.addAnnotation
import dev.zacsweers.lattice.ir.irType
import dev.zacsweers.lattice.ir.isAnnotatedWithAny
import dev.zacsweers.lattice.ir.locationIn
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.KotlinLikeDumpOptions
import org.jetbrains.kotlin.ir.util.VisibilityPrintingStrategy
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.platform.jvm.isJvm

internal interface LatticeTransformerContext {
  val pluginContext: IrPluginContext
  val messageCollector: MessageCollector
  val symbols: LatticeSymbols
  val debug: Boolean

  fun LatticeTransformerContext.log(message: String) {
    messageCollector.report(CompilerMessageSeverity.LOGGING, "$LOG_PREFIX $message")
  }

  fun IrDeclaration.reportError(message: String) {
    val location = this.locationIn(file)
    messageCollector.report(CompilerMessageSeverity.ERROR, message, location)
  }

  fun reportError(message: String, location: CompilerMessageLocation) {
    messageCollector.report(CompilerMessageSeverity.ERROR, message, location)
  }

  fun IrClass.dumpToLatticeLog() {
    dumpToLatticeLog(name = name.asString())
  }

  fun IrElement.dumpToLatticeLog(name: String) {
    if (debug) {
      val irSrc =
        dumpKotlinLike(
          KotlinLikeDumpOptions(visibilityPrintingStrategy = VisibilityPrintingStrategy.ALWAYS)
        )
      messageCollector.report(
        CompilerMessageSeverity.STRONG_WARNING,
        "LATTICE: Dumping current IR src for ${name}\n$irSrc",
      )
    }
  }

  fun irType(
    classId: ClassId,
    nullable: Boolean = false,
    arguments: List<IrTypeArgument> = emptyList(),
  ) = pluginContext.irType(classId, nullable, arguments)

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  fun IrSimpleFunction.markJvmStatic() {
    if (pluginContext.platform.isJvm()) {
      addAnnotation(symbols.jvmStatic.typeWith(), symbols.jvmStatic.constructors.single())
    }
  }

  fun IrAnnotationContainer?.qualifierAnnotation() =
    annotationsAnnotatedWith(symbols.qualifierAnnotations).singleOrNull()?.let(::IrAnnotation)

  fun IrAnnotationContainer?.scopeAnnotation() =
    annotationsAnnotatedWith(symbols.scopeAnnotations).singleOrNull()?.let(::IrAnnotation)

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun IrAnnotationContainer?.annotationsAnnotatedWith(
    annotationsToLookFor: Collection<ClassId>
  ): Set<IrConstructorCall> {
    if (this == null) return emptySet()
    return annotations.filterTo(LinkedHashSet()) {
      it.type.classOrNull?.owner?.isAnnotatedWithAny(annotationsToLookFor) == true
    }
  }

  val IrClass.isQualifierAnnotation: Boolean
    get() = isAnnotatedWithAny(symbols.qualifierAnnotations)

  val IrClass.isScopeAnnotation: Boolean
    get() = isAnnotatedWithAny(symbols.scopeAnnotations)

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  fun IrClass.findInjectableConstructor(): IrConstructor? {
    // TODO FIR error if primary constructor is missing but class annotated with inject
    return if (isAnnotatedWithAny(symbols.injectAnnotations)) {
      primaryConstructor
    } else {
      constructors.singleOrNull { constructor ->
        constructor.isAnnotatedWithAny(symbols.injectAnnotations)
      }
    }
  }

  companion object {
    operator fun invoke(
      pluginContext: IrPluginContext,
      messageCollector: MessageCollector,
      symbols: LatticeSymbols,
      debug: Boolean,
    ): LatticeTransformerContext =
      SimpleLatticeTransformerContext(pluginContext, messageCollector, symbols, debug)
  }
}

private class SimpleLatticeTransformerContext(
  override val pluginContext: IrPluginContext,
  override val messageCollector: MessageCollector,
  override val symbols: LatticeSymbols,
  override val debug: Boolean,
) : LatticeTransformerContext
