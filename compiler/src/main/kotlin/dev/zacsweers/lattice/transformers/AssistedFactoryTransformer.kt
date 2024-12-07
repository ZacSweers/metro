package dev.zacsweers.lattice.transformers

import dev.zacsweers.lattice.LatticeOrigin
import dev.zacsweers.lattice.ir.addCompanionObject
import dev.zacsweers.lattice.ir.addOverride
import dev.zacsweers.lattice.ir.createIrBuilder
import dev.zacsweers.lattice.ir.irInvoke
import dev.zacsweers.lattice.ir.isAnnotatedWithAny
import dev.zacsweers.lattice.ir.rawType
import dev.zacsweers.lattice.ir.singleAbstractFunction
import dev.zacsweers.lattice.joinSimpleNames
import dev.zacsweers.lattice.transformers.AssistedFactoryTransformer.AssistedFactoryFunction.Companion.toAssistedFactoryFunction
import dev.zacsweers.lattice.transformers.Parameter.AssistedParameterKey.Companion.toAssistedParameterKey
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

private val DELEGATE_FACTORY_NAME = Name.identifier("delegateFactory")

internal class AssistedFactoryTransformer(
  context: LatticeTransformerContext,
  private val injectConstructorTransformer: InjectConstructorTransformer,
) : LatticeTransformerContext by context {

  private val generatedImpls = mutableMapOf<ClassId, IrClass>()

  fun visitClass(declaration: IrClass) {
    val isAssistedFactory = declaration.isAnnotatedWithAny(symbols.assistedFactoryAnnotations)
    if (isAssistedFactory) {
      getOrGenerateImplClass(declaration)
    }
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  internal fun getOrGenerateImplClass(declaration: IrClass): IrClass {
    // TODO if declaration is external to this compilation, look
    //  up its factory or warn if it doesn't exist
    val classId: ClassId = declaration.classIdOrFail
    generatedImpls[classId]?.let {
      return it
    }

    // TODO generics asMemberOf()?
    val function =
      declaration.singleAbstractFunction(this).let { function ->
        function.toAssistedFactoryFunction(this, function)
      }

    val returnType = function.returnType
    val targetType = returnType.rawType()
    val injectConstructor = targetType.findInjectableConstructor()!!

    // TODO FIR
    //  ensure target type has an assistedinject constructor
    //  ensure assisted params match
    //  check duplicate keys
    //  check non-matching keys
    //  check for scopes? Scopes not allowed
    //  no qualifiers on assisted params
    //  if multiple types with same typekey, all need custom keys. OR all but one?

    val generatedFactory =
      injectConstructorTransformer.getOrGenerateFactoryClass(targetType, injectConstructor)

    val constructorParams = injectConstructor.parameters(this)
    val assistedParameters =
      constructorParams.valueParameters.filter { parameter -> parameter.isAssisted }
    val assistedParameterKeys =
      assistedParameters.mapIndexed { index, parameter ->
        injectConstructor.valueParameters[index].toAssistedParameterKey(symbols, parameter.typeKey)
      }

    val implClassName = declaration.classIdOrFail.joinSimpleNames(suffix = "_Impl")

    val implClass =
      pluginContext.irFactory
        .buildClass {
          name = implClassName.shortClassName
          origin = LatticeOrigin
        }
        .apply {
          copyTypeParametersFrom(declaration)
          superTypes += declaration.symbol.typeWith()

          createImplicitParameterDeclarationWithWrappedDescriptor()
          val implClassInstance = thisReceiver!!
          val ctor =
            addSimpleDelegatingConstructor(
              if (!declaration.isInterface) {
                declaration.primaryConstructor!!
              } else {
                symbols.anyConstructor
              },
              pluginContext.irBuiltIns,
              isPrimary = true,
              origin = LatticeOrigin,
            )

          // Add delegateFactory param and field
          val param = ctor.addValueParameter(DELEGATE_FACTORY_NAME, generatedFactory.typeWith())
          val delegateFactoryField =
            addField(DELEGATE_FACTORY_NAME, generatedFactory.typeWith()).apply {
              initializer = pluginContext.createIrBuilder(symbol).run { irExprBody(irGet(param)) }
            }

          addOverride(function.originalFunction).apply {
            this.dispatchReceiverParameter = implClassInstance
            this.returnType = returnType
            val functionParams =
              valueParameters.associateBy { valueParam ->
                val key = TypeMetadata.from(this@AssistedFactoryTransformer, valueParam).typeKey
                valueParam.toAssistedParameterKey(symbols, key)
              }
            body =
              pluginContext.createIrBuilder(symbol).run {
                // We call the @AssistedInject constructor. Therefore, find for each assisted
                // parameter the function parameter where the keys match.
                val argumentList =
                  assistedParameterKeys.map { assistedParameterKey ->
                    irGet(functionParams.getValue(assistedParameterKey))
                  }

                irExprBody(
                  irInvoke(
                    dispatchReceiver =
                      irGetField(irGet(dispatchReceiverParameter!!), delegateFactoryField),
                    callee = generatedFactory.getSimpleFunction("get")!!,
                    args = argumentList,
                  )
                )
              }
          }

          val companion = pluginContext.irFactory.addCompanionObject(symbols, parent = this)
          companion.buildCreateFunction(declaration.typeWith(), this, ctor, generatedFactory)
        }

    implClass.parent = declaration.parent
    declaration.getPackageFragment().addChild(implClass)

    implClass.dumpToLatticeLog()

    return implClass
  }

  // TODO for dagger interop - need createFactoryProvider() generated too
  private fun IrClass.buildCreateFunction(
    originClassName: IrType,
    implClass: IrClass,
    implConstructor: IrConstructor,
    generatedFactoryType: IrClass,
  ) {
    addFunction("create", originClassName.wrapInProvider(symbols.latticeProvider)).apply {
      this.copyTypeParametersFrom(implClass)
      this.origin = LatticeOrigin
      this.visibility = DescriptorVisibilities.PUBLIC
      markJvmStatic()

      val factoryParam = addValueParameter(DELEGATE_FACTORY_NAME, generatedFactoryType.typeWith())
      // InstanceFactory.create(Impl(delegateFactory))
      body =
        pluginContext.createIrBuilder(symbol).run {
          irExprBody(
            irInvoke(
              dispatchReceiver = irGetObject(symbols.instanceFactoryCompanionObject),
              callee = symbols.instanceFactoryCreate,
              args =
                listOf(
                  irInvoke(callee = implConstructor.symbol, args = listOf(irGet(factoryParam)))
                ),
            )
          )
        }
    }
  }

  /** Represents a parsed function in an `@AssistedInject.Factory`-annotated interface. */
  private data class AssistedFactoryFunction(
    val simpleName: String,
    val qualifiedName: String,
    val returnType: IrType,
    val originalFunction: IrSimpleFunction,
    val parameterKeys: List<Parameter.AssistedParameterKey>,
  ) {

    companion object {
      fun IrSimpleFunction.toAssistedFactoryFunction(
        context: LatticeTransformerContext,
        originalDeclaration: IrSimpleFunction,
      ): AssistedFactoryFunction {
        val params = parameters(context)
        return AssistedFactoryFunction(
          simpleName = originalDeclaration.name.asString(),
          qualifiedName = originalDeclaration.kotlinFqName.asString(),
          // TODO FIR validate return type is a component
          returnType = returnType,
          originalFunction = originalDeclaration,
          parameterKeys =
            originalDeclaration.valueParameters.mapIndexed { index, param ->
              param.toAssistedParameterKey(context.symbols, params.valueParameters[index].typeKey)
            },
        )
      }
    }
  }
}
