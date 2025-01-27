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

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.ir.parameters.ConstructorParameter
import dev.zacsweers.metro.compiler.ir.parameters.MembersInjectParameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.isWordPrefixRegex
import dev.zacsweers.metro.compiler.metroAnnotations
import java.util.TreeSet
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

internal sealed interface Binding {
  val typeKey: TypeKey
  val scope: IrAnnotation?
  // TODO reconcile dependencies vs parameters in collectBindings
  val dependencies: Map<TypeKey, Parameter>
  // Track the list of parameters, which may not have unique type keys
  val parameters: Parameters<out Parameter>
  val nameHint: String
  val contextualTypeKey: ContextualTypeKey
  val reportableLocation: CompilerMessageSourceLocation?

  sealed interface BindingWithAnnotations : Binding {
    val annotations: MetroAnnotations<IrAnnotation>
  }

  sealed interface InjectedClassBinding<T : InjectedClassBinding<T>> :
    BindingWithAnnotations, Binding {
    val type: IrClass

    fun withMapKey(mapKey: IrAnnotation?): T
  }

  @Poko
  class ConstructorInjected(
    @Poko.Skip override val type: IrClass,
    @Poko.Skip val injectedConstructor: IrConstructor,
    val isAssisted: Boolean,
    override val annotations: MetroAnnotations<IrAnnotation>,
    override val typeKey: TypeKey,
    override val parameters: Parameters<out Parameter>,
    override val dependencies: Map<TypeKey, Parameter> =
      parameters.nonInstanceParameters.associateBy { it.typeKey },
  ) : Binding, BindingWithAnnotations, InjectedClassBinding<ConstructorInjected> {
    override val scope: IrAnnotation?
      get() = annotations.scope

    override val nameHint: String = type.name.asString()
    override val contextualTypeKey: ContextualTypeKey =
      ContextualTypeKey(
        typeKey,
        isWrappedInProvider = false,
        isWrappedInLazy = false,
        isLazyWrappedInProvider = false,
        hasDefault = false,
      )

    override val reportableLocation: CompilerMessageSourceLocation
      get() = type.location()

    fun parameterFor(typeKey: TypeKey) =
      injectedConstructor.valueParameters[
          parameters.valueParameters.indexOfFirst { it.typeKey == typeKey }]

    override fun toString() = buildString {
      append("@Inject ")
      append(typeKey.render(short = true))
    }

    override fun withMapKey(mapKey: IrAnnotation?): ConstructorInjected {
      if (mapKey == null) return this
      return ConstructorInjected(
        type,
        injectedConstructor,
        isAssisted,
        annotations.copy(mapKeys = annotations.mapKeys + mapKey),
        typeKey,
        parameters,
        dependencies,
      )
    }
  }

  @Poko
  class Provided(
    @Poko.Skip val providerFunction: IrSimpleFunction,
    override val annotations: MetroAnnotations<IrAnnotation>,
    override val contextualTypeKey: ContextualTypeKey,
    override val parameters: Parameters<ConstructorParameter>,
    override val dependencies: Map<TypeKey, Parameter> =
      parameters.nonInstanceParameters.associateBy { it.typeKey },
    val aliasedType: ContextualTypeKey?,
    val callableId: CallableId = providerFunction.callableId,
  ) : Binding, BindingWithAnnotations {
    override val scope: IrAnnotation?
      get() = annotations.scope

    val intoSet: Boolean
      get() = annotations.isIntoSet

    val elementsIntoSet: Boolean
      get() = annotations.isElementsIntoSet

    // TODO are both necessary? Is there any case where only one is specified?
    val intoMap: Boolean
      get() = annotations.isIntoMap

    val mapKey: IrAnnotation? = annotations.mapKeys.singleOrNull()
    override val typeKey: TypeKey = contextualTypeKey.typeKey

    val isIntoMultibinding
      get() = annotations.isIntoMultibinding

    override val nameHint: String = providerFunction.name.asString()

    override val reportableLocation: CompilerMessageSourceLocation
      get() = providerFunction.location()

    fun parameterFor(typeKey: TypeKey): IrValueParameter {
      return parameters.allParameters.find { it.typeKey == typeKey }?.ir
        ?: error(
          "No value parameter found for key $typeKey in ${providerFunction.kotlinFqName.asString()}."
        )
    }

    override fun toString() = buildString {
      if (annotations.isBinds) {
        append("@Binds ")
      } else {
        append("@Provides ")
      }
      if (intoSet) {
        append("@IntoSet ")
      } else if (elementsIntoSet) {
        append("@ElementsIntoSet ")
      } else if (intoMap || mapKey != null) {
        append("@IntoMap ")
        mapKey?.let {
          append(it.toString())
          append(' ')
        }
      }
      append(providerFunction.kotlinFqName.asString())
      append(": ")
      append(typeKey.render(short = true))
    }
  }

  @Poko
  class Assisted(
    @Poko.Skip override val type: IrClass,
    val target: ConstructorInjected,
    @Poko.Skip val function: IrSimpleFunction,
    override val annotations: MetroAnnotations<IrAnnotation>,
    override val parameters: Parameters<out Parameter>,
    override val typeKey: TypeKey,
  ) : Binding, BindingWithAnnotations, InjectedClassBinding<Assisted> {
    // Dependencies are handled by the target class
    override val dependencies: Map<TypeKey, Parameter> = emptyMap()
    override val nameHint: String = type.name.asString()
    override val scope: IrAnnotation? = null
    override val contextualTypeKey: ContextualTypeKey = ContextualTypeKey(typeKey)
    override val reportableLocation: CompilerMessageSourceLocation
      get() = type.location()

    override fun withMapKey(mapKey: IrAnnotation?): Assisted {
      if (mapKey == null) return this
      return Assisted(
        type,
        target,
        function,
        annotations.copy(mapKeys = annotations.mapKeys + mapKey),
        parameters,
        typeKey,
      )
    }
  }

  data class BoundInstance(
    override val typeKey: TypeKey,
    override val nameHint: String,
    override val reportableLocation: CompilerMessageSourceLocation?,
  ) : Binding {
    constructor(
      parameter: Parameter
    ) : this(parameter.typeKey, "${parameter.name.asString()}Instance", parameter.location)

    override val scope: IrAnnotation? = null
    override val dependencies: Map<TypeKey, Parameter> = emptyMap()
    override val parameters: Parameters<out Parameter> = Parameters.empty()
    override val contextualTypeKey: ContextualTypeKey = ContextualTypeKey(typeKey)
  }

  data class Absent(override val typeKey: TypeKey) : Binding {
    override val scope: IrAnnotation? = null
    override val nameHint: String
      get() = error("Should never be called")

    override val dependencies: Map<TypeKey, Parameter> = emptyMap()
    override val parameters: Parameters<out Parameter> = Parameters.empty()
    override val contextualTypeKey: ContextualTypeKey = ContextualTypeKey(typeKey)

    override val reportableLocation: CompilerMessageSourceLocation? = null
  }

  data class GraphDependency(
    val graph: IrClass,
    val getter: IrSimpleFunction,
    override val typeKey: TypeKey,
  ) : Binding {
    override val scope: IrAnnotation? = null
    override val nameHint: String = buildString {
      append(graph.name)
      val property = getter.correspondingPropertySymbol
      if (property != null) {
        val propName = property.owner.name.asString()
        if (!isWordPrefixRegex.matches(propName)) {
          append("Get")
        }
        append(propName.capitalizeUS())
      } else {
        append(getter.name.capitalizeUS())
      }
    }
    override val dependencies: Map<TypeKey, Parameter> = emptyMap()
    override val parameters: Parameters<out Parameter> = Parameters.empty()
    override val contextualTypeKey: ContextualTypeKey = ContextualTypeKey(typeKey)

    override val reportableLocation: CompilerMessageSourceLocation?
      get() = getter.locationOrNull()
  }

  // TODO sets
  //  unscoped always initializes inline? Dagger sometimes generates private getters
  //  - @multibinds methods can never be scoped
  //  - their providers can't go into providerFields - would cause duplicates. Need to look up by
  //   nameHint
  @Poko
  class Multibinding(
    override val typeKey: TypeKey,
    @Poko.Skip val declaration: IrSimpleFunction?,
    val isSet: Boolean,
    val isMap: Boolean,
    // Reconcile this with dependencies?
    // Sorted for consistency
    val sourceBindings: MutableSet<Provided> =
      TreeSet(
        compareBy<Binding> { it.typeKey }
          .thenBy { it.nameHint }
          .thenBy { it.scope }
          .thenBy { it.parameters }
      ),
  ) : Binding {
    override val scope: IrAnnotation? = null
    override val dependencies: Map<TypeKey, Parameter> = buildMap {
      for (binding in sourceBindings) {
        putAll(binding.dependencies)
      }
    }

    override val parameters: Parameters<out Parameter> =
      if (sourceBindings.isEmpty()) {
        Parameters.empty()
      } else {
        sourceBindings
          .map { it.parameters }
          .reduce { current, next -> current.mergeValueParametersWith(next) }
      }

    override val nameHint: String
      get() = error("Should never be called")

    override val contextualTypeKey: ContextualTypeKey = ContextualTypeKey(typeKey)

    override val reportableLocation: CompilerMessageSourceLocation? = null

    companion object {
      fun create(
        metroContext: IrMetroContext,
        typeKey: TypeKey,
        declaration: IrSimpleFunction?,
      ): Multibinding {
        val rawType = typeKey.type.rawType()

        val isSet =
          rawType.implements(
            metroContext.pluginContext,
            metroContext.pluginContext.irBuiltIns.setClass.owner.classId!!,
          )
        val isMap = !isSet

        return Multibinding(typeKey, isSet = isSet, isMap = isMap, declaration = declaration)
      }
    }
  }

  data class MembersInjected(
    override val contextualTypeKey: ContextualTypeKey,
    override val parameters: Parameters<MembersInjectParameter>,
    override val reportableLocation: CompilerMessageSourceLocation?,
    val function: IrFunction,
    val isFromInjectorFunction: Boolean,
    val targetClassId: ClassId,
  ) : Binding {
    override val typeKey: TypeKey = contextualTypeKey.typeKey

    override val dependencies: Map<TypeKey, Parameter> =
      parameters.nonInstanceParameters.associateBy { it.typeKey }
    override val scope: IrAnnotation? = null

    override val nameHint: String = "${typeKey.type.rawType().name}MembersInjector"
  }

  companion object {
    /** Creates an expected class binding for the given [contextKey] or fails processing. */
    fun IrMetroContext.createInjectedClassBindingOrFail(
      contextKey: ContextualTypeKey,
      bindingStack: BindingStack,
      bindingGraph: BindingGraph,
      allowAbsent: Boolean = true,
    ): Binding {
      val key = contextKey.typeKey
      val irClass = key.type.rawType()
      val classAnnotations = irClass.metroAnnotations(symbols.classIds)
      val injectableConstructor =
        irClass.findInjectableConstructor(onlyUsePrimaryConstructor = classAnnotations.isInject)
      val binding =
        if (injectableConstructor != null) {
          val parameters = injectableConstructor.parameters(metroContext)
          ConstructorInjected(
            type = irClass,
            injectedConstructor = injectableConstructor,
            annotations = classAnnotations,
            isAssisted = parameters.valueParameters.any { it.isAssisted },
            typeKey = key,
            parameters = parameters,
          )
        } else if (classAnnotations.isAssistedFactory) {
          val function = irClass.singleAbstractFunction(metroContext)
          val targetContextualTypeKey =
            ContextualTypeKey.from(metroContext, function, classAnnotations)
          val bindingStackEntry = BindingStack.Entry.injectedAt(contextKey, function)
          val targetBinding =
            bindingStack.withEntry(bindingStackEntry) {
              bindingGraph.getOrCreateBinding(targetContextualTypeKey, bindingStack)
            } as ConstructorInjected
          Assisted(
            type = irClass,
            function = function,
            annotations = classAnnotations,
            typeKey = key,
            parameters = function.parameters(metroContext),
            target = targetBinding,
          )
        } else if (allowAbsent && contextKey.hasDefault) {
          Absent(key)
        } else {
          // TODO if a key with a different qualifier exists, consider mentioning it?
          val declarationToReport = bindingStack.lastEntryOrGraph
          val message = buildString {
            append(
              "[Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: "
            )
            appendLine(key.render(short = false))
            appendLine()
            appendBindingStack(bindingStack, short = false)
            if (metroContext.debug) {
              appendLine(
                bindingGraph.dumpGraph(bindingStack.graph.kotlinFqName.asString(), short = false)
              )
            }
          }

          declarationToReport.reportError(message)

          exitProcessing()
        }

      return binding
    }
  }
}
