// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer.MemberInjectClass
import dev.zacsweers.metro.compiler.metroAnnotations
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.isObject

internal class ClassBindingLookup(
  private val metroContext: IrMetroContext,
  private val sourceGraph: IrClass,
  private val findClassFactory: (IrClass) -> ClassFactory?,
  private val findMemberInjectors: (IrClass) -> List<MemberInjectClass>,
) {

  /** Creates an expected class binding for the given [contextKey] or returns null. */
  internal fun lookup(
    contextKey: IrContextualTypeKey,
    currentBindings: Set<IrTypeKey>,
    stack: IrBindingStack,
  ): Set<Binding> =
    with(metroContext) {
      val key = contextKey.typeKey
      val irClass = key.type.rawType()

      val classAnnotations = irClass.metroAnnotations(symbols.classIds)

      val bindings = mutableSetOf<Binding>()
      if (irClass.isObject) {
        irClass.getSimpleFunction(Symbols.StringNames.MIRROR_FUNCTION)?.owner?.let {
          // We don't actually call this function but it stores information about qualifier/scope
          // annotations, so reference it here so IC triggers
          trackFunctionCall(sourceGraph, it)
        }
        bindings += Binding.ObjectClass(irClass, classAnnotations, key)
        return bindings
      }

      val classFactory = findClassFactory(irClass)
      if (classFactory != null) {
        // We don't actually call this function but it stores information about qualifier/scope
        // annotations, so reference it here so IC triggers
        trackFunctionCall(sourceGraph, classFactory.function)

        val remapper = irClass.deepRemapperFor(key.type)
        val mappedFactory = classFactory.remapTypes(remapper)

        // Not sure this can ever happen but report a detailed error in case.
        if (
          irClass.typeParameters.isNotEmpty() &&
            (key.type as? IrSimpleType)?.arguments.isNullOrEmpty()
        ) {
          val message = buildString {
            appendLine(
              "Class factory for type ${key.type} has type parameters but no type arguments provided at calling site."
            )
            appendBindingStack(stack)
          }
          irClass.reportError(message)
          exitProcessing()
        }

        val injectedMembers = mutableSetOf<IrContextualTypeKey>()
        for (generatedInjector in findMemberInjectors(irClass)) {
          val mappedTypeKey = generatedInjector.typeKey.remapTypes(remapper)
          if (mappedTypeKey !in currentBindings) {
            // Remap type args using the same remapper used for the class
            val remappedParameters = generatedInjector.mergedParameters(remapper)
            val contextKey = IrContextualTypeKey(mappedTypeKey)
            injectedMembers += contextKey

            bindings +=
              Binding.MembersInjected(
                contextKey,
                // Need to look up the injector class and gather all params
                parameters = remappedParameters,
                reportableLocation = irClass.location(),
                function = null,
                isFromInjectorFunction = true,
                targetClassId = irClass.classIdOrFail,
              )
          }
        }

        bindings +=
          Binding.ConstructorInjected(
            type = irClass,
            classFactory = mappedFactory,
            annotations = classAnnotations,
            typeKey = key,
            injectedMembers = injectedMembers,
          )
      } else if (classAnnotations.isAssistedFactory) {
        val function = irClass.singleAbstractFunction(metroContext).asMemberOf(key.type)
        val targetContextualTypeKey = IrContextualTypeKey.from(metroContext, function)
        bindings +=
          Binding.Assisted(
            type = irClass,
            function = function,
            annotations = classAnnotations,
            typeKey = key,
            parameters = function.parameters(metroContext),
            target = targetContextualTypeKey,
          )
      } else if (contextKey.hasDefault) {
        bindings += Binding.Absent(key)
      } else {
        // Do nothing
      }
      return bindings
    }
}
