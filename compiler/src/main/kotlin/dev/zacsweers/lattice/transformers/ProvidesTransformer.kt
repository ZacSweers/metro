package dev.zacsweers.lattice.transformers

import dev.zacsweers.lattice.LatticeOrigin
import dev.zacsweers.lattice.LatticeSymbols
import dev.zacsweers.lattice.capitalizeUS
import dev.zacsweers.lattice.ir.addCompanionObject
import dev.zacsweers.lattice.ir.addOverride
import dev.zacsweers.lattice.ir.assignConstructorParamsToFields
import dev.zacsweers.lattice.ir.buildFactoryCreateFunction
import dev.zacsweers.lattice.ir.checkNotNullCall
import dev.zacsweers.lattice.ir.createIrBuilder
import dev.zacsweers.lattice.ir.irInvoke
import dev.zacsweers.lattice.ir.isAnnotatedWithAny
import dev.zacsweers.lattice.ir.isCompanionObject
import dev.zacsweers.lattice.ir.parametersAsProviderArguments
import dev.zacsweers.lattice.joinSimpleNames
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.packageFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal val isWordPrefixRegex = "^is([^a-z].*)".toRegex()

internal class ProvidesTransformer(context: LatticeTransformerContext) :
  LatticeTransformerContext by context {

  private val references = mutableMapOf<FqName, CallableReference>()
  private val generatedFactories = mutableMapOf<FqName, IrClass>()

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  fun visitComponentClass(declaration: IrClass) {
    declaration.declarations.forEach { nestedDeclaration ->
      when (nestedDeclaration) {
        is IrProperty -> visitProperty(declaration, nestedDeclaration)
        is IrFunction -> visitFunction(declaration, nestedDeclaration)
        is IrClass -> {
          if (nestedDeclaration.isCompanionObject) {
            // Include companion object refs
            visitClass(nestedDeclaration)
          }
        }
      }
    }
  }

  fun visitProperty(componentClass: IrClass, declaration: IrProperty) {
    if (
      !declaration.isAnnotatedWithAny(symbols.providesAnnotations) &&
        declaration.getter?.isAnnotatedWithAny(symbols.providesAnnotations) != true
    ) {
      return
    }

    getOrGenerateFactoryClass(componentClass, CallableReference.from(declaration))
  }

  fun visitFunction(componentClass: IrClass, declaration: IrFunction) {
    if (!declaration.isAnnotatedWithAny(symbols.providesAnnotations)) return
    getOrGenerateFactoryClass(componentClass, CallableReference.from(declaration))
  }

  fun visitClass(declaration: IrClass) {
    if (!declaration.isAnnotatedWithAny(symbols.componentAnnotations)) return
    visitComponentClass(declaration)
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  fun getOrGenerateFactoryClass(componentClass: IrClass, reference: CallableReference): IrClass {
    // TODO if declaration is external to this compilation, look
    //  up its factory or warn if it doesn't exist
    generatedFactories[reference.fqName]?.let {
      return it
    }

    // TODO FIR check parent class (if any) is a component. What about (companion) objects?
    // TODO FIR check function is not abstract
    // TODO FIR check for duplicate functions (by name, params don't count). Does this matter in FIR
    //  tho

    val isCompanionObject = componentClass.isCompanionObject
    val isInObject = isCompanionObject || reference.isInObject

    val isProperty = reference.isProperty
    val declarationName = reference.name
    // omit the `get-` prefix for property names starting with the *word* `is`, like `isProperty`,
    // but not for names which just start with those letters, like `issues`.
    // TODO still necessary in IR?
    val useGetPrefix = isProperty && !isWordPrefixRegex.matches(declarationName.asString())

    val packageName = componentClass.packageFqName!!
    val className = buildString {
      append(
        componentClass.classIdOrFail
          .joinSimpleNames()
          .relativeClassName
          .pathSegments()
          .joinToString("_")
      )
      append('_')
      if (isCompanionObject) {
        append("Companion_")
      }
      if (useGetPrefix) {
        append("Get")
      }
      append(declarationName.asString().capitalizeUS())
      append("Factory")
    }

    val parameters = reference.constructorParameters

    val returnType = reference.type

    val generatedClassName = ClassId(packageName, Name.identifier(className))
    val byteCodeFunctionName =
      when {
        useGetPrefix -> "get" + declarationName.asString().capitalizeUS()
        else -> declarationName.asString()
      }

    val canGenerateAnObject = isInObject && parameters.isEmpty()
    val factoryCls =
      pluginContext.irFactory
        .buildClass {
          name = generatedClassName.relativeClassName.shortName()
          kind = if (canGenerateAnObject) ClassKind.OBJECT else ClassKind.CLASS
          visibility = DescriptorVisibilities.PUBLIC
        }
        .apply { origin = LatticeOrigin }

    factoryCls.createImplicitParameterDeclarationWithWrappedDescriptor()
    factoryCls.superTypes += symbols.latticeFactory.typeWith(returnType)

    val factoryClassParameterized = factoryCls.typeWith()

    // Implement constructor w/ params if necessary
    val ctor =
      factoryCls.addSimpleDelegatingConstructor(
        symbols.anyConstructor,
        pluginContext.irBuiltIns,
        isPrimary = true,
        origin = LatticeOrigin,
      )

    val allParameters = buildList {
      if (!isInObject) {
        add(
          ConstructorParameter(
            name = Name.identifier("component"),
            typeKey = TypeKey(returnType),
            originalName = Name.special("component"),
            typeName = returnType,
            // This type is always the instance type
            providerTypeName = returnType,
            lazyTypeName = returnType,
            isWrappedInProvider = false,
            isWrappedInLazy = false,
            isLazyWrappedInProvider = false,
            isAssisted = false,
            assistedIdentifier = Name.identifier(""),
            symbols = symbols,
          )
        )
      }
      addAll(parameters)
    }

    val parametersToFields = assignConstructorParamsToFields(ctor, factoryCls, allParameters)

    val bytecodeFunctionSymbol =
      generateCreators(
        factoryCls,
        ctor.symbol,
        reference,
        factoryClassParameterized,
        allParameters,
        byteCodeFunctionName,
      )

    // Implement invoke()
    // TODO DRY this up with the constructor injection override
    factoryCls
      .addOverride(
        baseFqName = symbols.providerInvoke.owner.kotlinFqName,
        name = symbols.providerInvoke.owner.name.asString(),
        returnType = returnType,
      )
      .apply {
        this.dispatchReceiverParameter = factoryCls.thisReceiver!!
        this.overriddenSymbols += symbols.providerInvoke
        body =
          pluginContext.createIrBuilder(symbol).run {
            irExprBody(
              irInvoke(
                callee = bytecodeFunctionSymbol,
                args =
                  parametersAsProviderArguments(
                    parameters = allParameters,
                    receiver = factoryCls.thisReceiver!!,
                    parametersToFields = parametersToFields,
                    symbols = symbols,
                  ),
              )
            )
          }
      }

    factoryCls.dumpToLatticeLog()

    componentClass.getPackageFragment().addChild(factoryCls)
    generatedFactories[reference.fqName] = factoryCls
    return factoryCls
  }

  private fun CallableReference.Companion.from(function: IrFunction): CallableReference {
    return references.getOrPut(function.kotlinFqName) {
      // TODO FIR error if it has a receiver param
      // TODO FIR error if it is top-level/not in component

      val type = function.returnType
      val parent = function.parentAsClass
      CallableReference(
        fqName = function.kotlinFqName,
        isInternal = function.visibility == Visibilities.Internal,
        isCompanionObject = parent.isCompanionObject,
        isInObject = parent.kind == ClassKind.OBJECT,
        name = function.name,
        isProperty = false,
        constructorParameters =
          function.valueParameters.mapToConstructorParameters(this@ProvidesTransformer),
        type = type,
        isNullable = type.isMarkedNullable(),
        isPublishedApi = function.hasAnnotation(LatticeSymbols.ClassIds.PublishedApi),
        reportableNode = function,
        parent = parent.symbol,
        callee = function.symbol,
      )
    }
  }

  private fun CallableReference.Companion.from(property: IrProperty): CallableReference {
    val fqName = property.fqNameWhenAvailable ?: error("No FqName for property ${property.name}")
    return references.getOrPut(fqName) {
      // TODO FIR error if it has a receiver param
      // TODO FIR check property is not var
      // TODO FIR check property is visible
      // TODO enforce get:? enforce no site target?
      // TODO FIR error if it is top-level/not in component

      val getter =
        property.getter
          ?: error(
            "No getter found for property $fqName. Note that field properties are not supported"
          )
      val type = getter.returnType

      val parent = property.parentAsClass
      return CallableReference(
        fqName = fqName,
        isInternal = property.visibility == Visibilities.Internal,
        isCompanionObject = parent.isCompanionObject,
        isInObject = parent.kind == ClassKind.OBJECT,
        name = property.name,
        isProperty = true,
        constructorParameters = emptyList(),
        type = type,
        isNullable = type.isMarkedNullable(),
        isPublishedApi = property.hasAnnotation(LatticeSymbols.ClassIds.PublishedApi),
        reportableNode = property,
        parent = parent.symbol,
        callee = property.getter!!.symbol,
      )
    }
  }

  private fun generateCreators(
    factoryCls: IrClass,
    factoryConstructor: IrConstructorSymbol,
    reference: CallableReference,
    factoryClassParameterized: IrType,
    allParameters: List<ConstructorParameter>,
    byteCodeFunctionName: String,
  ): IrSimpleFunctionSymbol {
    val targetTypeParameterized = reference.type
    val returnTypeIsNullable = reference.isNullable

    // If this is an object, we can generate directly into this object
    val isObject = factoryCls.kind == ClassKind.OBJECT
    val classToGenerateCreatorsIn =
      if (isObject) {
        factoryCls
      } else {
        pluginContext.irFactory.addCompanionObject(symbols, parent = factoryCls)
      }

    // Generate create()
    classToGenerateCreatorsIn.buildFactoryCreateFunction(
      this,
      factoryCls,
      factoryClassParameterized,
      factoryConstructor,
      allParameters,
    )

    // Generate the named function

    /*
     Implement a static newInstance() function

     // Simple
     @JvmStatic // JVM only
     fun newInstance(value: T): Example = Example(value)

     // Generic
     @JvmStatic // JVM only
     fun <T> newInstance(value: T): Example<T> = Example<T>(value)

     // Provider
     @JvmStatic // JVM only
     fun newInstance(value: Provider<String>): Example = Example(value)
    */
    val newInstanceFunction =
      classToGenerateCreatorsIn
        .addFunction(byteCodeFunctionName, targetTypeParameterized, isStatic = true)
        .apply {
          // No type params for this
          this.origin = LatticeOrigin
          this.visibility = DescriptorVisibilities.PUBLIC
          markJvmStatic()
          for (parameter in allParameters) {
            addValueParameter(parameter.name, parameter.originalTypeName, LatticeOrigin)
          }
          val argumentsWithoutComponent: IrBuilderWithScope.() -> List<IrExpression> = {
            valueParameters.drop(1).map { irGet(it) }
          }
          val arguments: IrBuilderWithScope.() -> List<IrExpression> = {
            valueParameters.map { irGet(it) }
          }
          body =
            pluginContext.createIrBuilder(symbol).run {
              val expression: IrExpression =
                when {
                  isObject && returnTypeIsNullable -> {
                    // Static component call, allows nullable returns
                    // ExampleComponent.$callableName$arguments
                    irInvoke(irGetObject(reference.parent), reference.callee, args = arguments())
                  }
                  isObject && !returnTypeIsNullable -> {
                    // Static component call that doesn't allow nullable
                    // checkNotNull(ExampleComponent.$callableName$arguments) {
                    //   "Cannot return null from a non-@Nullable @Provides method"
                    // }
                    checkNotNullCall(
                      this@ProvidesTransformer,
                      this@apply.parent, // TODO this is obvi wrong
                      irInvoke(irGetObject(reference.parent), reference.callee, args = arguments()),
                      "Cannot return null from a non-@Nullable @Provides method",
                    )
                  }
                  !isObject && returnTypeIsNullable -> {
                    // Instance component call, allows nullable returns
                    // exampleComponent.$callableName$arguments
                    irInvoke(
                      irGet(valueParameters[0]),
                      reference.callee,
                      args = argumentsWithoutComponent(),
                    )
                  }
                  // !isObject && !returnTypeIsNullable
                  else -> {
                    // Instance component call, does not allow nullable returns
                    // exampleComponent.$callableName$arguments
                    checkNotNullCall(
                      this@ProvidesTransformer,
                      this@apply.parent, // TODO this is obvi wrong
                      irInvoke(
                        irGet(valueParameters[0]),
                        reference.callee,
                        args = argumentsWithoutComponent(),
                      ),
                      "Cannot return null from a non-@Nullable @Provides method",
                    )
                  }
                }
              irExprBody(expression)
            }
        }

    return newInstanceFunction.symbol
  }

  internal class CallableReference(
    val fqName: FqName,
    val isInternal: Boolean,
    val isCompanionObject: Boolean,
    val isInObject: Boolean,
    val name: Name,
    val isProperty: Boolean,
    val constructorParameters: List<ConstructorParameter>,
    val type: IrType,
    val isNullable: Boolean,
    val isPublishedApi: Boolean,
    val reportableNode: Any,
    val parent: IrClassSymbol,
    val callee: IrFunctionSymbol,
  ) {
    // TODO remove this support
    val isMangled: Boolean
      get() = !isProperty && isInternal && !isPublishedApi

    companion object // For extension
  }
}
