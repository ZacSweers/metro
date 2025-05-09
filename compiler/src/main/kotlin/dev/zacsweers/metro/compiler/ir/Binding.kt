// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.graph.BaseBinding
import dev.zacsweers.metro.compiler.ir.Binding.Absent
import dev.zacsweers.metro.compiler.ir.Binding.Assisted
import dev.zacsweers.metro.compiler.ir.Binding.ConstructorInjected
import dev.zacsweers.metro.compiler.ir.Binding.ObjectClass
import dev.zacsweers.metro.compiler.ir.parameters.ConstructorParameter
import dev.zacsweers.metro.compiler.ir.parameters.MembersInjectParameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.transformers.ProviderFactory
import dev.zacsweers.metro.compiler.isWordPrefixRegex
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.render
import java.util.TreeSet
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

internal sealed interface Binding : BaseBinding<IrType, IrTypeKey, IrContextualTypeKey> {
  override val typeKey: IrTypeKey
    get() = contextualTypeKey.typeKey

  val scope: IrAnnotation?
  // TODO reconcile parametersByKey vs parameters in collectBindings
  val parametersByKey: Map<IrTypeKey, Parameter>
  // Track the list of parameters, which may not have unique type keys
  val parameters: Parameters<out Parameter>
  val nameHint: String
  override val contextualTypeKey: IrContextualTypeKey
  val reportableLocation: CompilerMessageSourceLocation?

  override fun renderLocationDiagnostic(): String {
    // First check if we have the contributing file and line number
    return reportableLocation?.render()
      // Or the fully-qualified contributing class name
      ?: parametersByKey.entries.firstOrNull()?.key?.toString()
      // Or print the full set of info we know about the binding
      ?: buildString {
        val binding = this@Binding
        appendLine("Unknown source location, this may be generated by the compiler.")
        appendLine("└─ Here's some additional information we have for the binding:")
        appendLine("   ├─ Binding type: ${binding.javaClass.simpleName}")
        appendLine("   └─ Binding information: $binding")
      }
  }

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
    override val typeKey: IrTypeKey,
    override val parameters: Parameters<out Parameter>,
    override val parametersByKey: Map<IrTypeKey, Parameter> =
      parameters.nonInstanceParameters.associateBy { it.typeKey },
  ) : Binding, BindingWithAnnotations, InjectedClassBinding<ConstructorInjected> {
    override val dependencies: List<IrContextualTypeKey> =
      parameters.nonInstanceParameters.filterNot { it.isAssisted }.map { it.contextualTypeKey }

    override val scope: IrAnnotation?
      get() = annotations.scope

    override val nameHint: String = type.name.asString()
    override val contextualTypeKey: IrContextualTypeKey = IrContextualTypeKey.create(typeKey)

    override val reportableLocation: CompilerMessageSourceLocation?
      get() = type.locationOrNull()

    fun parameterFor(typeKey: IrTypeKey) =
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
        parametersByKey,
      )
    }
  }

  class ObjectClass(
    @Poko.Skip override val type: IrClass,
    override val annotations: MetroAnnotations<IrAnnotation>,
    override val typeKey: IrTypeKey,
  ) : Binding, BindingWithAnnotations, InjectedClassBinding<ObjectClass> {
    override val dependencies: List<IrContextualTypeKey> = emptyList()
    override val scope: IrAnnotation? = null
    override val parameters: Parameters<out Parameter> = Parameters.empty()
    override val parametersByKey: Map<IrTypeKey, Parameter> = emptyMap()

    override val nameHint: String = type.name.asString()
    override val contextualTypeKey: IrContextualTypeKey = IrContextualTypeKey.create(typeKey)

    override val reportableLocation: CompilerMessageSourceLocation?
      get() = type.locationOrNull()

    override fun toString() = buildString {
      append("@Inject ")
      append(typeKey.render(short = true))
    }

    override fun withMapKey(mapKey: IrAnnotation?): ObjectClass {
      if (mapKey == null) return this
      return ObjectClass(type, annotations.copy(mapKeys = annotations.mapKeys + mapKey), typeKey)
    }
  }

  @Poko
  class Provided(
    @Poko.Skip val providerFactory: ProviderFactory,
    override val annotations: MetroAnnotations<IrAnnotation>,
    override val contextualTypeKey: IrContextualTypeKey,
    override val parameters: Parameters<ConstructorParameter>,
  ) : Binding, BindingWithAnnotations {
    override val dependencies: List<IrContextualTypeKey> =
      parameters.nonInstanceParameters.map { it.contextualTypeKey }

    override val parametersByKey: Map<IrTypeKey, Parameter> =
      parameters.nonInstanceParameters.associateBy { it.typeKey }

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
    override val typeKey: IrTypeKey = contextualTypeKey.typeKey

    val isIntoMultibinding
      get() = annotations.isIntoMultibinding

    override val nameHint: String = providerFactory.callableId.callableName.asString()

    override val reportableLocation: CompilerMessageSourceLocation?
      get() = providerFactory.providesFunction.locationOrNull()

    fun parameterFor(typeKey: IrTypeKey): IrValueParameter {
      return parameters.allParameters.find { it.typeKey == typeKey }?.ir
        ?: error(
          "No value parameter found for key $typeKey in ${providerFactory.callableId.asSingleFqName().asString()}."
        )
    }

    override fun toString() = buildString {
      append("@Provides ")
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
      append(
        providerFactory.callableId.render(
          short = false,
          isProperty = providerFactory.isPropertyAccessor,
        )
      )
      append(": ")
      append(typeKey.render(short = false))
    }
  }

  /** Represents an aliased binding, i.e. `@Binds`. Can be a multibinding. */
  @Poko
  class Alias(
    override val typeKey: IrTypeKey,
    val aliasedType: IrTypeKey,
    @Poko.Skip val ir: IrSimpleFunction?,
    override val parameters: Parameters<out Parameter>,
    override val annotations: MetroAnnotations<IrAnnotation>,
  ) : Binding, BindingWithAnnotations {
    fun aliasedBinding(graph: IrBindingGraph, stack: IrBindingStack): Binding {
      // O(1) lookup at this point
      return graph.requireBinding(contextualTypeKey.withTypeKey(aliasedType), stack)
    }

    override val scope: IrAnnotation? = null
    override val parametersByKey: Map<IrTypeKey, Parameter> =
      parameters.nonInstanceParameters.associateBy { it.typeKey }
    override val dependencies: List<IrContextualTypeKey> =
      listOf(IrContextualTypeKey.create(aliasedType))
    override val nameHint: String = ir?.name?.asString() ?: typeKey.type.rawType().name.asString()
    override val contextualTypeKey: IrContextualTypeKey = IrContextualTypeKey(typeKey)
    // TODO dedupe with the below render()
    override val reportableLocation: CompilerMessageSourceLocation?
      get() {
        if (ir == null) return null
        return (ir.overriddenSymbolsSequence().lastOrNull()?.owner ?: ir).let {
          val isMetroContribution =
            it.parentClassOrNull?.hasAnnotation(Symbols.ClassIds.metroContribution) == true
          if (isMetroContribution) {
            // If it's a contribution, the source is
            // SourceClass.$$MetroContributionScopeName.bindingFunction
            //                                          ^^^
            it.parentAsClass.parentAsClass.locationOrNull()
          } else {
            it.locationOrNull()
          }
        }
      }

    override fun renderLocationDiagnostic(): String {
      if ((annotations.isIntoMultibinding || annotations.isBinds) && ir != null) {
        (ir.overriddenSymbolsSequence().lastOrNull()?.owner ?: ir).let {
          val isMetroContribution =
            it.parentClassOrNull?.hasAnnotation(Symbols.ClassIds.metroContribution) == true
          if (isMetroContribution) {
            // If it's a contribution, the source is
            // SourceClass.$$MetroContributionScopeName.bindingFunction
            //                                          ^^^
            return buildString {
              append("Contributed by '${it.parentAsClass.parentAsClass.kotlinFqName}'")
              it.parentAsClass.parentAsClass.locationOrNull()?.let { location ->
                append(" at ")
                append(location.render())
              } ?: run { append(" at an unknown source location (likely a separate compilation).") }
            }
          }
        }
      }
      return super<Binding>.renderLocationDiagnostic()
    }

    override fun toString() = buildString {
      if (annotations.isBinds) {
        append("@Binds ")
      } else {
        append("@Provides ")
      }
      if (annotations.isIntoSet) {
        append("@IntoSet ")
      } else if (annotations.isElementsIntoSet) {
        append("@ElementsIntoSet ")
      } else if (annotations.isIntoMap || annotations.mapKeys.isNotEmpty()) {
        append("@IntoMap ")
        annotations.mapKeys.firstOrNull()?.let {
          append(it.toString())
          append(' ')
        }
      }
      append(aliasedType.render(short = true))
      append('.')
      append(nameHint)
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
    override val typeKey: IrTypeKey,
  ) : Binding, BindingWithAnnotations, InjectedClassBinding<Assisted> {
    // Dependencies are handled by the target class
    override val dependencies: List<IrContextualTypeKey> = listOf(target.contextualTypeKey)
    override val parametersByKey: Map<IrTypeKey, Parameter> = emptyMap()
    override val nameHint: String = type.name.asString()
    override val scope: IrAnnotation? = null
    override val contextualTypeKey: IrContextualTypeKey = IrContextualTypeKey(typeKey)
    override val reportableLocation: CompilerMessageSourceLocation?
      get() = type.locationOrNull()

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
    override val typeKey: IrTypeKey,
    override val nameHint: String,
    override val reportableLocation: CompilerMessageSourceLocation?,
  ) : Binding {
    constructor(
      parameter: Parameter
    ) : this(parameter.typeKey, "${parameter.name.asString()}Instance", parameter.location)

    override val dependencies: List<IrContextualTypeKey> = emptyList()
    override val scope: IrAnnotation? = null
    override val parametersByKey: Map<IrTypeKey, Parameter> = emptyMap()
    override val parameters: Parameters<out Parameter> = Parameters.empty()
    override val contextualTypeKey: IrContextualTypeKey = IrContextualTypeKey(typeKey)
  }

  data class Absent(override val typeKey: IrTypeKey) : Binding {
    override val dependencies: List<IrContextualTypeKey> = emptyList()
    override val scope: IrAnnotation? = null
    override val nameHint: String
      get() = error("Should never be called")

    override val parametersByKey: Map<IrTypeKey, Parameter> = emptyMap()
    override val parameters: Parameters<out Parameter> = Parameters.empty()
    override val contextualTypeKey: IrContextualTypeKey = IrContextualTypeKey(typeKey)

    override val reportableLocation: CompilerMessageSourceLocation? = null
    override val isTransient: Boolean = true
  }

  @Poko
  class GraphDependency(
    @Poko.Skip val graph: IrClass,
    @Poko.Skip val getter: IrSimpleFunction,
    val isProviderFieldAccessor: Boolean,
    override val typeKey: IrTypeKey,
    val callableId: CallableId = getter.callableId,
  ) : Binding {
    override val dependencies: List<IrContextualTypeKey> = emptyList()
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
    override val parametersByKey: Map<IrTypeKey, Parameter> = emptyMap()
    override val parameters: Parameters<out Parameter> = Parameters.empty()
    override val contextualTypeKey: IrContextualTypeKey = IrContextualTypeKey(typeKey)

    override val reportableLocation: CompilerMessageSourceLocation?
      get() = getter.propertyIfAccessor.locationOrNull()

    override fun toString(): String {
      return "${graph.kotlinFqName}#${(getter.propertyIfAccessor as IrDeclarationWithName).name}: ${getter.returnType.dumpKotlinLike()}"
    }
  }

  // TODO sets
  //  unscoped always initializes inline? Dagger sometimes generates private getters
  //  - @multibinds methods can never be scoped
  //  - their providers can't go into providerFields - would cause duplicates. Need to look up by
  //   nameHint
  @Poko
  class Multibinding(
    override val typeKey: IrTypeKey,
    @Poko.Skip val declaration: IrSimpleFunction?,
    val isSet: Boolean,
    val isMap: Boolean,
    var allowEmpty: Boolean,
    // Reconcile this with parametersByKey?
    // Sorted for consistency
    val sourceBindings: MutableSet<BindingWithAnnotations> =
      TreeSet(
        compareBy<Binding> { it.typeKey }
          .thenBy { it.nameHint }
          .thenBy { it.scope }
          .thenBy { it.parameters }
      ),
  ) : Binding {
    override val scope: IrAnnotation? = null
    override val dependencies: List<IrContextualTypeKey> = emptyList()
    override val aggregatedBindings: Set<Binding>
      get() = sourceBindings

    override val parametersByKey: Map<IrTypeKey, Parameter> = buildMap {
      for (binding in sourceBindings) {
        putAll(binding.parametersByKey)
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
      get() = "${typeKey.type.rawType().name}Multibinding"

    override val contextualTypeKey: IrContextualTypeKey = IrContextualTypeKey(typeKey)

    override val reportableLocation: CompilerMessageSourceLocation? = null

    companion object {
      fun create(
        metroContext: IrMetroContext,
        typeKey: IrTypeKey,
        declaration: IrSimpleFunction?,
        allowEmpty: Boolean = false,
      ): Multibinding {
        val rawType = typeKey.type.rawType()

        val isSet =
          rawType.implements(
            metroContext.pluginContext,
            metroContext.pluginContext.irBuiltIns.setClass.owner.classId!!,
          )
        val isMap = !isSet

        return Multibinding(
          typeKey,
          isSet = isSet,
          isMap = isMap,
          allowEmpty = allowEmpty,
          declaration = declaration,
        )
      }
    }
  }

  data class MembersInjected(
    override val contextualTypeKey: IrContextualTypeKey,
    override val parameters: Parameters<MembersInjectParameter>,
    override val reportableLocation: CompilerMessageSourceLocation?,
    val function: IrFunction,
    val isFromInjectorFunction: Boolean,
    val targetClassId: ClassId,
  ) : Binding {
    override val typeKey: IrTypeKey = contextualTypeKey.typeKey

    override val dependencies: List<IrContextualTypeKey> =
      parameters.nonInstanceParameters.map { it.contextualTypeKey }
    override val parametersByKey: Map<IrTypeKey, Parameter> =
      parameters.nonInstanceParameters.associateBy { it.typeKey }
    override val scope: IrAnnotation? = null

    override val nameHint: String = "${typeKey.type.rawType().name}MembersInjector"
  }
}

/** Creates an expected class binding for the given [contextKey] or returns null. */
internal fun IrMetroContext.injectedClassBindingOrNull(
  contextKey: IrContextualTypeKey,
  bindingStack: IrBindingStack,
  bindingGraph: IrBindingGraph,
): Binding? {
  val key = contextKey.typeKey
  val irClass = key.type.rawType()
  val classAnnotations = irClass.metroAnnotations(symbols.classIds)

  if (irClass.isObject) {
    // TODO make these opt-in?
    return ObjectClass(irClass, classAnnotations, key)
  }

  val injectableConstructor =
    irClass.findInjectableConstructor(onlyUsePrimaryConstructor = classAnnotations.isInject)
  return if (injectableConstructor != null) {
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
    val targetContextualTypeKey = IrContextualTypeKey.from(metroContext, function, classAnnotations)
    val bindingStackEntry = IrBindingStack.Entry.injectedAt(contextKey, function)
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
  } else if (contextKey.hasDefault) {
    Absent(key)
  } else {
    null
  }
}
