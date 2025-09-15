// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.sharding.ShardFieldRegistry
import dev.zacsweers.metro.compiler.sharding.ShardingPlan
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.primaryConstructor

// Use shared constant from ShardingConstants
private const val SHARD_STATEMENTS_PER_METHOD = ShardingConstants.STATEMENTS_PER_METHOD

/**
 * Generates IR for shard classes following Dagger's pattern.
 * 
 * Shards are static nested classes within the main component that contain
 * a subset of the bindings to avoid method size limits.
 */
internal class ShardGenerator(
  private val context: IrMetroContext,
  private val parentClass: IrClass,
  private val shard: ShardingPlan.Shard,
  private val bindingGraph: IrBindingGraph,
  private val fieldNameAllocator: NameAllocator,
  private val shardingPlan: ShardingPlan,
  private val fieldRegistry: ShardFieldRegistry
) : IrMetroContext by context {

  /**
   * Generates the nested shard class.
   * Following Dagger's pattern:
   * - static nested class (not inner)
   * - internal visibility for cross-shard access
   * - receives main graph instance and necessary modules in constructor
   *
   * @param initializeFieldsFunction Optional initialization function to call in constructor
   */
  fun generateShardClass(initializeFieldsFunction: IrFunction? = null): IrClass {
    val shardClassName = shard.shardClassName()

    // Create the shard class using the IR factory
    val shardClass = pluginContext.irFactory.buildClass {
      name = shardClassName.asName()
      kind = ClassKind.CLASS
      visibility = DescriptorVisibilities.INTERNAL
      modality = Modality.FINAL
      origin = Origins.Default
    }

    // Make it a nested class of the parent component
    parentClass.addChild(shardClass)

    // Ensure thisReceiver is created
    if (shardClass.thisReceiver == null) {
      shardClass.createThisReceiverParameter()
    }

    // Add backing field for graph parameter
    val graphField = shardClass.addField {
      name = "graph".asName()
      type = parentClass.defaultType
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
      origin = Origins.Default
    }

    // Generate initializeFields method FIRST (if we have the function)
    // This ensures it exists before we reference it in the constructor
    if (initializeFieldsFunction != null) {
      // The function is already created, just ensure it's added to the class
      shardClass.addChild(initializeFieldsFunction)
    }

    // Add primary constructor
    val constructor = shardClass.addConstructor {
      visibility = DescriptorVisibilities.PUBLIC
      isPrimary = true
      returnType = shardClass.defaultType
    }

    // Add parameters for main graph and modules
    val graphParameter = constructor.addValueParameter("graph", parentClass.defaultType)
    // Module parameters will be added based on shard requirements

    // Store the graph parameter symbol in metadata for robust access
    setShardMetadata(shardClass, ShardMetadata(graphParameterSymbol = graphParameter.symbol))

    // Add constructor body WITH proper field assignment
    constructor.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
      val builder = context.createIrBuilder(constructor.symbol)
      val thisRef = requireNotNull(shardClass.thisReceiver) {
        "Shard class ${shardClass.name} missing this receiver"
      }

      // 1. Call super constructor (Any.<init>())
      statements += builder.irDelegatingConstructorCall(
        context.irBuiltIns.anyClass.owner.primaryConstructor!!
      )

      // 2. Set the graph field: this.graph = graph
      statements += builder.irSetField(
        receiver = builder.irGet(thisRef),
        field = graphField,
        value = builder.irGet(graphParameter)
      )

      // 3. Call instance initializer
      statements += IrInstanceInitializerCallImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        shardClass.symbol,
        shardClass.defaultType
      )

      // 4. Call this.initializeFields() if provided
      if (initializeFieldsFunction != null) {
        statements += builder.irCall(initializeFieldsFunction.symbol).also { call ->
          call.dispatchReceiver = builder.irGet(thisRef)
        }
      }
    }

    return shardClass
  }

  /**
   * Generates a field in the parent class to hold this shard instance.
   * The field has internal visibility to allow cross-shard access.
   */
  fun generateShardField(shardClass: IrClass): IrField {
    val fieldName = "shard${shard.index}"
    
    return parentClass.addField {
      name = fieldName.asName()
      type = shardClass.defaultType
      visibility = DescriptorVisibilities.INTERNAL
      isFinal = true
      origin = Origins.Default
    }
  }

  /**
   * Generates the initialization expression for this shard.
   * Creates a call to the shard constructor: ShardN(this, module1, module2, ...)
   */
  fun generateShardInitialization(
    shardClass: IrClass,
    thisReceiver: IrValueParameter,
    moduleParameters: List<IrValueParameter>
  ): IrExpression {
    return with(context.createIrBuilder(parentClass.symbol)) {
      val constructor = shardClass.primaryConstructor
        ?: error("Shard class missing primary constructor")
      
      // Build arguments list: main graph + module parameters
      val args = buildList {
        add(irGet(thisReceiver)) // Pass 'this' (the main graph) as first parameter
        moduleParameters.forEach { param ->
          add(irGet(param)) // Pass module parameters
        }
      }
      
      // Use irCall with arguments array instead of deprecated putValueArgument
      irCall(constructor.symbol).apply {
        args.forEachIndexed { index, arg ->
          arguments[index] = arg
        }
      }
    }
  }

  /**
   * Determines if a binding should go into this shard.
   */
  fun containsBinding(typeKey: IrTypeKey): Boolean {
    return shard.bindings.contains(typeKey)
  }

  /**
   * Gets the shard field name for cross-shard references.
   */
  fun shardFieldName(): String {
    return if (shard.isComponentShard) {
      "this" // Component shard is just "this"
    } else {
      "shard${shard.index}"
    }
  }

  /**
   * Generates an initialization method inside the shard class.
   * Creates a private initializeFields() method that populates all fields in the shard.
   * If there are too many initializers, splits them into multiple initializePart{N}() methods.
   * 
   * @param shardInfo The shard information containing the shard class
   * @param initializers List of (field, typeKey, initializerFunction) triples for this shard
   * @return The generated initializeFields IrFunction
   */
  fun generateShardFieldInitialization(
    shardInfo: ShardInfo, 
    initializers: List<Triple<IrField, IrTypeKey, FieldInitializer>>
  ): IrFunction {
    val shardClass = shardInfo.shardClass
    
    // Check if we need to chunk the initializers
    val needsChunking = initializers.size > SHARD_STATEMENTS_PER_METHOD
    
    if (needsChunking) {
      // Split initializers into chunks
      val chunks = initializers.chunked(SHARD_STATEMENTS_PER_METHOD)
      
      // Generate initializePart{N} methods for each chunk
      val partFunctions = chunks.mapIndexed { index, chunk ->
        val partMethod = shardClass.addFunction {
          name = "initializePart${index + 1}".asName()
          visibility = DescriptorVisibilities.PRIVATE
          returnType = context.irBuiltIns.unitType
        }.apply {
          // Make it an instance method by adding dispatch receiver
          buildReceiverParameter { type = shardClass.defaultType }
        }

        // Create method body with field assignments for this chunk
        partMethod.body = context.irFactory.createBlockBody(
          startOffset = UNDEFINED_OFFSET,
          endOffset = UNDEFINED_OFFSET
        ).apply {
          val dr = requireNotNull(partMethod.dispatchReceiverParameter) {
            "Part method ${partMethod.name} missing dispatch receiver parameter"
          }
          for ((field, typeKey, fieldInitializer) in chunk) {
            statements += with(context.createIrBuilder(partMethod.symbol)) {
              irSetField(
                receiver = irGet(dr),
                field = field,
                value = fieldInitializer(dr, typeKey)
              )
            }
          }
        }

        partMethod
      }
      
      // Create the main initializeFields method that calls all parts
      val initMethod = shardClass.addFunction {
        name = "initializeFields".asName()
        visibility = DescriptorVisibilities.PRIVATE
        returnType = context.irBuiltIns.unitType
      }.apply {
        // Make it an instance method by adding dispatch receiver
        buildReceiverParameter { type = shardClass.defaultType }
      }

      // Create method body that calls all part methods
      initMethod.body = context.irFactory.createBlockBody(
        startOffset = UNDEFINED_OFFSET,
        endOffset = UNDEFINED_OFFSET
      ).apply {
        val dr = requireNotNull(initMethod.dispatchReceiverParameter) {
          "Initialize method missing dispatch receiver parameter"
        }
        for (partFunction in partFunctions) {
          statements += with(context.createIrBuilder(initMethod.symbol)) {
            irCall(partFunction.symbol).also { call ->
              call.dispatchReceiver = irGet(dr)
            }
          }
        }
      }
      
      return initMethod
    } else {
      // No chunking needed, generate single method as before
      val initMethod = shardClass.addFunction {
        name = "initializeFields".asName()
        visibility = DescriptorVisibilities.PRIVATE
        returnType = context.irBuiltIns.unitType
      }.apply {
        // Make it an instance method by adding dispatch receiver
        buildReceiverParameter { type = shardClass.defaultType }
      }

      // Create method body with field assignments
      initMethod.body = context.irFactory.createBlockBody(
        startOffset = UNDEFINED_OFFSET,
        endOffset = UNDEFINED_OFFSET
      ).apply {
        val dr = requireNotNull(initMethod.dispatchReceiverParameter) {
          "Initialize method missing dispatch receiver parameter"
        }
        // Iterate over each field and its initializer
        for ((field, typeKey, fieldInitializer) in initializers) {
          // Use the stored type key directly, no registry lookup needed
          statements += with(context.createIrBuilder(initMethod.symbol)) {
            irSetField(
              receiver = irGet(dr),
              field = field,
              value = fieldInitializer(dr, typeKey)
            )
          }
        }
      }

      return initMethod
    }
  }

  /**
   * Generates a complete shard class with field initialization.
   * This method coordinates the generation of both the shard class and its initialization method,
   * ensuring the constructor properly calls initializeFields().
   *
   * @param initializers List of (field, typeKey, initializerFunction) triples for this shard
   * @return The generated shard class with initialization
   */
  fun generateShardClassWithInitialization(
    initializers: List<Triple<IrField, IrTypeKey, FieldInitializer>>
  ): IrClass {
    // First, create a basic shard class structure
    val shardClassName = shard.shardClassName()

    // Create the shard class using the IR factory
    val shardClass = pluginContext.irFactory.buildClass {
      name = shardClassName.asName()
      kind = ClassKind.CLASS
      visibility = DescriptorVisibilities.INTERNAL
      modality = Modality.FINAL
      origin = Origins.Default
    }

    // Make it a nested class of the parent component
    parentClass.addChild(shardClass)

    // Ensure thisReceiver is created
    if (shardClass.thisReceiver == null) {
      shardClass.createThisReceiverParameter()
    }

    // Create ShardInfo to use with the field initialization method
    val shardInfo = ShardInfo(
      shard = shard,
      shardClass = shardClass,
      shardField = null, // Will be set later
      generator = this
    )

    // Generate the initializeFields method FIRST
    val initializeFieldsMethod = generateShardFieldInitialization(shardInfo, initializers)

    // Add backing field for graph parameter
    val graphField = shardClass.addField {
      name = "graph".asName()
      type = parentClass.defaultType
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
      origin = Origins.Default
    }

    // Add primary constructor
    val constructor = shardClass.addConstructor {
      visibility = DescriptorVisibilities.PUBLIC
      isPrimary = true
      returnType = shardClass.defaultType
    }

    // Add parameters for main graph and modules
    val graphParameter = constructor.addValueParameter("graph", parentClass.defaultType)
    // Module parameters will be added based on shard requirements

    // Store the graph parameter symbol in metadata for robust access
    setShardMetadata(shardClass, ShardMetadata(graphParameterSymbol = graphParameter.symbol))

    // Add constructor body WITH proper field assignment
    constructor.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
      val builder = context.createIrBuilder(constructor.symbol)
      val thisRef = requireNotNull(shardClass.thisReceiver) {
        "Shard class ${shardClass.name} missing this receiver"
      }

      // 1. Call super constructor (Any.<init>())
      statements += builder.irDelegatingConstructorCall(
        context.irBuiltIns.anyClass.owner.primaryConstructor!!
      )

      // 2. Set the graph field: this.graph = graph
      statements += builder.irSetField(
        receiver = builder.irGet(thisRef),
        field = graphField,
        value = builder.irGet(graphParameter)
      )

      // 3. Call instance initializer
      statements += IrInstanceInitializerCallImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        shardClass.symbol,
        shardClass.defaultType
      )

      // 4. Call this.initializeFields()
      statements += builder.irCall(initializeFieldsMethod.symbol).also { call ->
        call.dispatchReceiver = builder.irGet(thisRef)
      }
    }

    return shardClass
  }

  companion object {
    /**
     * Metadata storage for shard classes.
     * Maps shard classes to their metadata containing important symbols.
     */
    private val shardMetadataMap = mutableMapOf<IrClass, ShardMetadata>()
    
    /**
     * Stores metadata for a shard class.
     */
    fun setShardMetadata(shardClass: IrClass, metadata: ShardMetadata) {
      shardMetadataMap[shardClass] = metadata
    }
    
    /**
     * Retrieves metadata for a shard class.
     */
    fun getShardMetadata(shardClass: IrClass): ShardMetadata? {
      return shardMetadataMap[shardClass]
    }
    
    /**
     * Generates all shard classes for a sharding plan.
     * Returns a map of shard index to ShardInfo containing the generated class and field.
     */
    fun generateShards(
      metroContext: IrMetroContext,
      parentClass: IrClass,
      shardingPlan: ShardingPlan,
      bindingGraph: IrBindingGraph,
      fieldNameAllocator: NameAllocator,
      fieldRegistry: ShardFieldRegistry
    ): Map<Int, ShardInfo> {
      val result = mutableMapOf<Int, ShardInfo>()
      
      // Generate each additional shard (skip shard 0 which is the main component)
      for (shard in shardingPlan.additionalShards()) {
        val generator = ShardGenerator(
          context = metroContext,
          parentClass = parentClass,
          shard = shard,
          bindingGraph = bindingGraph,
          fieldNameAllocator = fieldNameAllocator,
          shardingPlan = shardingPlan,
          fieldRegistry = fieldRegistry
        )
        
        val shardClass = generator.generateShardClass()
        val shardField = generator.generateShardField(shardClass)
        
        result[shard.index] = ShardInfo(
          shard = shard,
          shardClass = shardClass,
          shardField = shardField,
          generator = generator
        )
      }
      
      return result
    }
  }

  /**
   * Information about a generated shard.
   */
  data class ShardInfo(
    val shard: ShardingPlan.Shard,
    val shardClass: IrClass,
    val shardField: IrField?,
    val generator: ShardGenerator
  )
  
  /**
   * Metadata for a shard class containing important symbols.
   * This allows robust access to shard constructor parameters without relying on names.
   */
  data class ShardMetadata(
    val graphParameterSymbol: IrValueParameterSymbol
  )
}