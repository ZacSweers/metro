package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.TypeKey
import dev.zacsweers.metro.compiler.ir.getConstBooleanArgumentOrNull
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.getAnnotationStringValue
import org.jetbrains.kotlin.name.CallableId

internal class ProviderFactory(
  val context: IrMetroContext,
  sourceTypeKey: TypeKey,
  val clazz: IrClass,
  sourceCallable: IrSimpleFunction?,
  sourceAnnotations: MetroAnnotations<IrAnnotation>?,
) {
  val callableId: CallableId
  val providesFunction: IrSimpleFunction
  val annotations: MetroAnnotations<IrAnnotation>
  val typeKey: TypeKey
  val isPropertyAccessor: Boolean

  init {
    val providesCallableIdAnno =
      clazz.getAnnotation(Symbols.FqNames.providesCallableIdClass)
        ?: error(
          "No @ProvidesCallableId found on class ${clazz.classId}. This is a bug in the Metro compiler."
        )
    val callableName = providesCallableIdAnno.getAnnotationStringValue("callableName")
    callableId = CallableId(clazz.classIdOrFail.parentClassId!!, callableName.asName())
    isPropertyAccessor =
      providesCallableIdAnno.getConstBooleanArgumentOrNull("isPropertyAccessor".asName()) ?: false
    providesFunction = sourceCallable
      ?: context.pluginContext.referenceFunctions(callableId).firstOrNull()?.owner
        ?: error("No matching provider function found for $callableId")
    annotations = sourceAnnotations ?: providesFunction.metroAnnotations(context.symbols.classIds)
    typeKey = sourceTypeKey.copy(qualifier = annotations.qualifier)
  }

  val parameters by unsafeLazy {
    providesFunction.parameters(context)
  }
}
