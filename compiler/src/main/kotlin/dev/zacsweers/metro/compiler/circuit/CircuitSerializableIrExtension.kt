// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.api.fir.metroOriginData
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.ir.annotationsCompat
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.finderFor
import dev.zacsweers.metro.compiler.ir.generateDefaultConstructorBody
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.kClassReference
import dev.zacsweers.metro.compiler.ir.regularParameters
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addFakeOverrides
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.ClassId

/**
 * Generates Circuit serializer registration declarations before Metro's IR pipeline consumes them.
 */
public class CircuitSerializableIrDeclarationGenerationExtension
private constructor(private val compatContext: CompatContext) : IrGenerationExtension {
  public companion object {
    public fun create(
      compatContext: CompatContext
    ): CircuitSerializableIrDeclarationGenerationExtension {
      return CircuitSerializableIrDeclarationGenerationExtension(compatContext)
    }
  }

  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val symbols = CircuitSymbols.Ir(with(compatContext) { pluginContext.finderForBuiltinsCompat() })
    CircuitSerializableIrDeclarationGenerator(
        pluginContext = pluginContext,
        symbols = symbols,
        compatContext = compatContext,
      )
      .generateDeclarationShells(moduleFragment)
  }
}

/**
 * Completes Circuit serializer registrations generated in FIR or by
 * [CircuitSerializableIrDeclarationGenerationExtension].
 *
 * This extension must run before Metro's main IR pipeline so the generated contribution is visible
 * to the graph. It must also run before kotlinx-serialization so its IR extension can lower the
 * generated `Type.serializer()` call.
 */
public class CircuitSerializableIrExtension(
  private val generateClassesInIr: Boolean,
  private val compatContext: CompatContext,
) : IrGenerationExtension {
  public companion object {
    public fun create(
      generateClassesInIr: Boolean,
      compatContext: CompatContext,
    ): CircuitSerializableIrExtension {
      return CircuitSerializableIrExtension(
        generateClassesInIr = generateClassesInIr,
        compatContext = compatContext,
      )
    }
  }

  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val symbols = CircuitSymbols.Ir(with(compatContext) { pluginContext.finderForBuiltinsCompat() })
    moduleFragment.transformChildrenVoid(
      CircuitSerializableIrTransformer(
        pluginContext = pluginContext,
        symbols = symbols,
        generateClassesInIr = generateClassesInIr,
        compatContext = compatContext,
      )
    )
  }
}

private class CircuitSerializableIrDeclarationGenerator(
  private val pluginContext: IrPluginContext,
  private val symbols: CircuitSymbols.Ir,
  private val compatContext: CompatContext,
) : CompatContext by compatContext {
  private val generationSupport = CircuitIrGenerationSupport(pluginContext, compatContext)

  fun generateDeclarationShells(moduleFragment: IrModuleFragment) {
    for (file in moduleFragment.files) {
      for (declaration in file.declarations.toList()) {
        if (declaration is IrClass) {
          generateSerializerRegistrationShells(declaration)
        }
      }
    }
  }

  private fun generateSerializerRegistrationShells(serializedClass: IrClass) {
    generateSerializerRegistrationShell(serializedClass)

    for (nestedClass in serializedClass.declarations.filterIsInstance<IrClass>().toList()) {
      generateSerializerRegistrationShells(nestedClass)
    }
  }

  private fun generateSerializerRegistrationShell(serializedClass: IrClass) {
    // Platform compilations contain both sides of an expect/actual family. Circuit assigns
    // generation ownership to the expect declaration, so actual declarations must not generate a
    // second registration.
    if (serializedClass.isActualDeclaration) return

    val annotation =
      serializedClass.annotationsCompat().firstOrNull { annotation ->
        annotation.symbol.owner.parentAsClass.classId == CircuitClassIds.CircuitSerializable
      } ?: return
    val serializedType = serializedClass.classId ?: return
    val scopeClass = annotation.circuitSerializableScope() ?: return
    val registrationClassId = serializedType.circuitSerializerRegistrationClassId()
    val file = serializedClass.file
    if (file.hasTopLevelClass(registrationClassId)) return

    createSerializerRegistrationClass(
      parent = file,
      registrationClassId = registrationClassId,
      serializedClass = serializedClass,
      scopeClass = scopeClass,
    )
  }

  private fun IrConstructorCall.circuitSerializableScope(): IrClassSymbol? {
    val positionalScope = arguments.getOrNull(0) as? IrClassReference
    val namedScope =
      symbol.owner.parameters
        .firstOrNull { it.name == CircuitNames.scope }
        ?.let { parameter -> arguments.getOrNull(parameter.indexInParameters) } as? IrClassReference
    return (positionalScope ?: namedScope)?.symbol as? IrClassSymbol
  }

  private fun IrFile.hasTopLevelClass(classId: ClassId): Boolean {
    return declarations.filterIsInstance<IrClass>().any { it.classIdOrFail == classId }
  }

  private fun createSerializerRegistrationClass(
    parent: IrFile,
    registrationClassId: ClassId,
    serializedClass: IrClass,
    scopeClass: IrClassSymbol,
  ) {
    val registrationType = symbols.serializerRegistration ?: return
    val registrationClass =
      pluginContext.irFactory
        .buildClass {
          name = registrationClassId.shortClassName
          origin =
            IrDeclarationOrigin.GeneratedByPlugin(
              CircuitOrigins.SerializerRegistrationClass(serializedClass.classIdOrFail)
            )
          kind = ClassKind.CLASS
          visibility = DescriptorVisibilities.PUBLIC
          modality = Modality.FINAL
        }
        .apply {
          this.parent = parent
          createThisReceiverParameter()
        }
    parent.addChild(registrationClass)
    registrationClass.apply {
      superTypes += registrationType.defaultType
      generationSupport.addGeneratedClassAnnotations(
        generatedClass = this,
        scopeClass = scopeClass,
        originClass = serializedClass.symbol,
      )
      generationSupport.markAsDeprecatedHidden(this)
      addFakeOverrides(generationSupport.irTypeSystemContext)
    }

    // Kotlin 2.4 requires the class shell to be registered without a constructor. Register the
    // constructor separately so both declarations receive valid FIR metadata.
    generationSupport.metadataDeclarationRegistrarCompat.registerClassAsMetadataVisible(
      registrationClass
    )
    registrationClass
      .addConstructor {
        origin = CircuitOrigins.IrSerializerRegistrationConstructor
        isPrimary = true
        visibility = DescriptorVisibilities.PUBLIC
      }
      .apply {
        body = context(pluginContext) { generateDefaultConstructorBody() }
        generationSupport.metadataDeclarationRegistrarCompat.registerConstructorAsMetadataVisible(
          this
        )
      }
  }
}

private class CircuitSerializableIrTransformer(
  private val pluginContext: IrPluginContext,
  private val symbols: CircuitSymbols.Ir,
  private val generateClassesInIr: Boolean,
  private val compatContext: CompatContext,
) : IrElementTransformerVoid(), CompatContext by compatContext {
  private val generationSupport = CircuitIrGenerationSupport(pluginContext, compatContext)

  override fun visitClass(declaration: IrClass): IrStatement {
    val generatedOrigin =
      declaration.origin.expectAsOrNull<IrDeclarationOrigin.GeneratedByPlugin>()?.pluginKey
    if (generatedOrigin is CircuitOrigins.SerializerRegistrationClass) {
      finalizeSerializerRegistration(declaration, generatedOrigin)
    }
    return super.visitClass(declaration)
  }

  private fun finalizeSerializerRegistration(
    registrationClass: IrClass,
    origin: CircuitOrigins.SerializerRegistrationClass,
  ) {
    val registerFunction =
      symbols.serializerRegisterFunction(registrationClass)
        ?: error(
          "Generated Circuit serializer registration ${registrationClass.classId} is missing " +
            "CircuitSerializerRegistration.register()."
        )

    val serializedClass =
      with(compatContext) {
        pluginContext.finderFor(registrationClass).findClass(origin.serializedType)
      } ?: error("Could not find @CircuitSerializable type ${origin.serializedType}.")

    if (!generateClassesInIr || registrationClass.metroOriginData != null) {
      // FIR carries Metro's origin data separately. Materialize the annotation before Metro's IR
      // pipeline reads the generated contribution.
      generationSupport.addMetadataVisibleOrigin(registrationClass, serializedClass)
    }

    val serializerFunction =
      symbols.serializerFunction(serializedClass)
        ?: error(
          "Could not find serializer output for ${origin.serializedType.asSingleFqName()}. " +
            "Apply the kotlinx-serialization compiler plugin."
        )

    val subclassFunction =
      symbols.polymorphicSubclassFunction
        ?: error(
          "Could not find PolymorphicModuleBuilder.subclass(KClass, KSerializer). Ensure " +
            "Circuit's serialization runtime and kotlinx-serialization-core are on the classpath."
        )

    val builderParameter =
      registerFunction.regularParameters.firstOrNull { it.name == CircuitNames.builder }
        ?: registerFunction.regularParameters.singleOrNull()
        ?: error(
          "CircuitSerializerRegistration.register() on ${registrationClass.classId} is missing " +
            "its builder parameter."
        )

    if (registerFunction.isFakeOverride) {
      registerFunction.finalizeFakeOverride(registrationClass.thisReceiver!!)
    }
    registerFunction.modality = Modality.FINAL
    registerFunction.body =
      pluginContext.createIrBuilder(registerFunction.symbol).irBlockBody {
        val serializerCall = irInvoke(callee = serializerFunction)
        +irInvoke(
          dispatchReceiver = irGet(builderParameter),
          callee = subclassFunction,
          typeArgs = listOf(serializedClass.defaultType),
          args = listOf(kClassReference(serializedClass), serializerCall),
        )
      }
  }
}

private val IrClass.isActualDeclaration: Boolean
  get() = (metadata as? FirMetadataSource.Class)?.fir?.symbol?.rawStatus?.isActual == true
