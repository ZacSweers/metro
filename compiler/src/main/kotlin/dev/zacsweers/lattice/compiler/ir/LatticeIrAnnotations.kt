package dev.zacsweers.lattice.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.lattice.compiler.LatticeClassIds
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.classId

@Poko
internal class LatticeIrAnnotations(
  val isDependencyGraph: Boolean,
  val isDependencyGraphFactory: Boolean,
  val isInject: Boolean,
  val isAssistedInject: Boolean,
  val isProvides: Boolean,
  val isBinds: Boolean,
  val isBindsInstance: Boolean,
  val isIntoSet: Boolean,
  val isElementsIntoSet: Boolean,
  val isIntoMap: Boolean,
  val assisted: IrAnnotation?,
  val scope: IrAnnotation?,
  val qualifier: IrAnnotation?,
  val mapKeys: Set<IrAnnotation>,
) {
  val isScoped
    get() = scope != null

  val isQualified
    get() = qualifier != null

  val isAssisted
    get() = assisted != null
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrAnnotationContainer.latticeAnnotations(ids: LatticeClassIds): LatticeIrAnnotations {
  var isDependencyGraph = false
  var isDependencyGraphFactory = false
  var isInject = false
  var isAssistedInject = false
  var isProvides = false
  var isBinds = false
  var isBindsInstance = false
  var isIntoSet = false
  var isElementsIntoSet = false
  var isIntoMap = false
  var assisted: IrAnnotation? = null
  var scope: IrAnnotation? = null
  var qualifier: IrAnnotation? = null
  var mapKeys = mutableSetOf<IrAnnotation>()

  for (it in annotations) {
    val annotationClass = it.type.classOrNull?.owner ?: continue
    val classId = annotationClass.classId ?: continue

    when (this) {
      is IrValueParameter -> {
        // Only BindsInstance and Assisted go here
        if (classId in ids.bindsInstanceAnnotations) {
          isBindsInstance = true
          continue
        } else if (classId in ids.assistedAnnotations) {
          assisted = expectNullAndSet("assisted", assisted, it.asIrAnnotation())
          continue
        }
      }

      is IrFunction,
      is IrProperty -> {
        // Binds, Provides
        if (classId in ids.bindsAnnotations) {
          isBinds = true
          continue
        } else if (classId in ids.providesAnnotations) {
          isProvides = true
          continue
        } else if (classId in ids.intoSetAnnotations) {
          isIntoSet = true
          continue
        } else if (classId in ids.elementsIntoSetAnnotations) {
          isElementsIntoSet = true
          continue
        } else if (classId in ids.intoMapAnnotations) {
          isIntoMap = true
          continue
        }
      }

      is IrClass -> {
        // AssistedFactory, DependencyGraph, DependencyGraph.Factory
        if (classId in ids.assistedFactoryAnnotations) {
          isBinds = true
          continue
        } else if (classId in ids.dependencyGraphAnnotations) {
          isDependencyGraph = true
          continue
        } else if (classId in ids.dependencyGraphFactoryAnnotations) {
          isDependencyGraphFactory = true
          continue
        }
      }
    }

    // Everything below applies to multiple targets

    if (classId in ids.injectAnnotations) {
      isInject = true
      continue
    } else if (classId in ids.assistedInjectAnnotations) {
      isAssistedInject = true
      continue
    }

    if (annotationClass.isAnnotatedWithAny(ids.scopeAnnotations)) {
      scope = expectNullAndSet("scope", scope, it.asIrAnnotation())
      continue
    } else if (annotationClass.isAnnotatedWithAny(ids.qualifierAnnotations)) {
      qualifier = expectNullAndSet("qualifier", qualifier, it.asIrAnnotation())
      continue
    } else if (annotationClass.isAnnotatedWithAny(ids.mapKeyAnnotations)) {
      mapKeys += it.asIrAnnotation()
      continue
    }
  }
  return LatticeIrAnnotations(
    isDependencyGraph = isDependencyGraph,
    isDependencyGraphFactory = isDependencyGraphFactory,
    isInject = isInject,
    isAssistedInject = isAssistedInject,
    isProvides = isProvides,
    isBinds = isBinds,
    isBindsInstance = isBindsInstance,
    isIntoSet = isIntoSet,
    isElementsIntoSet = isElementsIntoSet,
    isIntoMap = isIntoMap,
    assisted = assisted,
    scope = scope,
    qualifier = qualifier,
    mapKeys = mapKeys,
  )
}

internal fun <T> expectNullAndSet(type: String, current: T?, value: T): T {
  check(current == null) { "Multiple $type annotations found! Found $current and $value." }
  return value
}
