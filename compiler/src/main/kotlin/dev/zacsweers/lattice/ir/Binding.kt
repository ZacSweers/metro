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
package dev.zacsweers.lattice.ir

import dev.zacsweers.lattice.capitalizeUS
import dev.zacsweers.lattice.ir.parameters.Parameter
import dev.zacsweers.lattice.ir.parameters.Parameters
import dev.zacsweers.lattice.isWordPrefixRegex
import java.util.TreeSet
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.classId

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

  data class ConstructorInjected(
    val type: IrClass,
    val injectedConstructor: IrConstructor,
    val isAssisted: Boolean,
    override val typeKey: TypeKey,
    override val parameters: Parameters<out Parameter>,
    override val scope: IrAnnotation? = null,
    override val dependencies: Map<TypeKey, Parameter> =
      parameters.nonInstanceParameters.associateBy { it.typeKey },
  ) : Binding {
    override val nameHint: String = type.name.asString()
    override val contextualTypeKey: ContextualTypeKey =
      ContextualTypeKey(typeKey, false, false, false, false)

    override val reportableLocation: CompilerMessageSourceLocation
      get() = type.location()

    fun parameterFor(typeKey: TypeKey) =
      injectedConstructor.valueParameters[
          parameters.valueParameters.indexOfFirst { it.typeKey == typeKey }]
  }

  data class Provided(
    val providerFunction: IrSimpleFunction,
    override val contextualTypeKey: ContextualTypeKey,
    override val parameters: Parameters<out Parameter>,
    override val scope: IrAnnotation? = null,
    override val dependencies: Map<TypeKey, Parameter> =
      parameters.nonInstanceParameters.associateBy { it.typeKey },
    val intoSet: Boolean,
    val elementsIntoSet: Boolean,
    // TODO are both necessary? Is there any case where only one is specified?
    val intoMap: Boolean,
    val mapKey: IrAnnotation?,
    val aliasedType: ContextualTypeKey?,
  ) : Binding {
    override val typeKey: TypeKey = contextualTypeKey.typeKey
    val isMultibindingProvider
      get() = intoSet || elementsIntoSet || mapKey != null || intoMap

    override val nameHint: String = providerFunction.name.asString()

    override val reportableLocation: CompilerMessageSourceLocation
      get() = providerFunction.location()

    fun parameterFor(typeKey: TypeKey): IrValueParameter {
      return providerFunction.valueParameters[
          parameters.valueParameters.indexOfFirst { it.typeKey == typeKey }]
    }
  }

  data class Assisted(
    val type: IrClass,
    val target: ConstructorInjected,
    val function: IrSimpleFunction,
    override val parameters: Parameters<out Parameter>,
    override val typeKey: TypeKey,
  ) : Binding {
    // Dependencies are handled by the target class
    override val dependencies: Map<TypeKey, Parameter> = emptyMap()
    override val nameHint: String = type.name.asString()
    override val scope: IrAnnotation? = null
    override val contextualTypeKey: ContextualTypeKey =
      ContextualTypeKey(typeKey, false, false, false, false)
    override val reportableLocation: CompilerMessageSourceLocation
      get() = type.location()
  }

  data class BoundInstance(val parameter: Parameter) : Binding {
    override val typeKey: TypeKey = parameter.typeKey
    override val scope: IrAnnotation? = null
    override val nameHint: String = "${parameter.name.asString()}Instance"
    override val dependencies: Map<TypeKey, Parameter> = emptyMap()
    override val parameters: Parameters<out Parameter> = Parameters.empty()
    override val contextualTypeKey: ContextualTypeKey =
      ContextualTypeKey(typeKey, false, false, false, false)

    override val reportableLocation: CompilerMessageSourceLocation?
      get() = parameter.location
  }

  data class Absent(override val typeKey: TypeKey) : Binding {
    override val scope: IrAnnotation? = null
    override val nameHint: String
      get() = error("Should never be called")

    override val dependencies: Map<TypeKey, Parameter> = emptyMap()
    override val parameters: Parameters<out Parameter> = Parameters.empty()
    override val contextualTypeKey: ContextualTypeKey =
      ContextualTypeKey(typeKey, false, false, false, false)

    override val reportableLocation: CompilerMessageSourceLocation? = null
  }

  data class GraphDependency(
    val graph: IrClass,
    val getter: IrSimpleFunction,
    override val typeKey: TypeKey,
  ) : Binding {
    override val scope: IrAnnotation? = null
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    override val nameHint: String = buildString {
      append(graph.name.asString())
      val property = getter.correspondingPropertySymbol
      if (property != null) {
        val propName = property.owner.name.asString()
        if (!isWordPrefixRegex.matches(propName)) {
          append("Get")
        }
        append(propName.capitalizeUS())
      } else {
        append(getter.name.asString())
      }
    }
    override val dependencies: Map<TypeKey, Parameter> = emptyMap()
    override val parameters: Parameters<out Parameter> = Parameters.empty()
    override val contextualTypeKey: ContextualTypeKey =
      ContextualTypeKey(typeKey, false, false, false, false)

    override val reportableLocation: CompilerMessageSourceLocation
      get() = getter.location()
  }

  // TODO sets
  //  unscoped always initializes inline? Dagger sometimes generates private getters
  //  - @multibinds methods can never be scoped
  //  - their providers can't go into providerFields - would cause duplicates. Need to look up by
  //   nameHint
  data class Multibinding(
    override val typeKey: TypeKey,
    val isSet: Boolean,
    val isMap: Boolean,
    // Reconcile this with dependencies?
    // Sorted for consistency
    val providers: MutableSet<Provided> =
      TreeSet(
        compareBy<Provided> { it.typeKey }
          .thenBy { it.nameHint }
          .thenBy { it.scope }
          .thenBy { it.parameters }
      ),
  ) : Binding {
    override val scope: IrAnnotation? = null
    override val dependencies: Map<TypeKey, Parameter>
      get() = emptyMap()

    override val parameters: Parameters<out Parameter>
      get() = Parameters.empty()

    override val nameHint: String
      get() = error("Should never be called")

    override val contextualTypeKey: ContextualTypeKey =
      ContextualTypeKey(typeKey, false, false, false, false)

    override val reportableLocation: CompilerMessageSourceLocation? = null

    companion object {
      @OptIn(UnsafeDuringIrConstructionAPI::class)
      fun create(pluginContext: IrPluginContext, typeKey: TypeKey): Multibinding {
        val isSet =
          typeKey.type
            .rawType()
            .implements(pluginContext, pluginContext.irBuiltIns.setClass.owner.classId!!)
        val isMap =
          !isSet &&
            typeKey.type
              .rawType()
              .implements(pluginContext, pluginContext.irBuiltIns.mapClass.owner.classId!!)

        check(isSet xor isMap) { "Multibinding was somehow not a set or map" }

        return Multibinding(typeKey, isSet = isSet, isMap = isMap)
      }
    }
  }
}
