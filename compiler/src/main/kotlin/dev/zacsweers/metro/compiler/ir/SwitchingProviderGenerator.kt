// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("DEPRECATION")

package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.sharding.ShardFieldRegistry
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrStringConcatenationImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.Name

internal class SwitchingProviderGenerator(
  private val context: IrMetroContext,
  private val bindingFieldContext: BindingFieldContext? = null,
  private val shardFieldRegistry: ShardFieldRegistry? = null,
  private val expressionGenerator: IrGraphExpressionGenerator? = null,
) : IrMetroContext by context {

  // Helper extension functions for type checking
  private fun IrType.isProvider(): Boolean {
    val classifier = this.classifierOrNull
    return classifier is IrClassSymbol &&
           classifier.owner.fqNameWhenAvailable?.asString()?.let { fqName ->
             fqName == "javax.inject.Provider" ||
             fqName == "dev.zacsweers.metro.runtime.MetroProvider"
           } == true
  }

  private fun IrType.isLazy(): Boolean {
    val classifier = this.classifierOrNull
    return classifier is IrClassSymbol &&
           classifier.owner.fqNameWhenAvailable?.asString() == "kotlin.Lazy"
  }

  /**
   * Helper to resolve the owner of a field when it might be in a shard.
   * Returns the appropriate receiver expression (main graph or shard instance).
   *
   * IMPORTANT: This method creates FRESH expressions each time to avoid duplicate IR nodes.
   * This is critical for IR validation - we cannot reuse the same field access node.
   *
   * IMPORTANT: This method does NOT add any caching wrappers (DoubleCheck).
   * Caching is handled at the field initialization level in IrGraphGenerator,
   * not in the SwitchingProvider's invoke() dispatch logic.
   */
  context(scope: IrBuilderWithScope)
  private fun resolveOwnerForShard(
    switchingProviderClass: IrClass,
    graphClass: IrClass,
    shardIndex: Int?
  ): IrExpression = with(scope) {
    // Always create a fresh graph expression to avoid duplicate IR nodes
    val invokeFunction = switchingProviderClass.declarations
      .filterIsInstance<IrSimpleFunction>()
      .firstOrNull { it.name.asString() == "invoke" }
      ?: error("SwitchingProvider must have invoke() function")

    val spThis = invokeFunction.dispatchReceiverParameter
      ?: error("invoke() must have dispatch receiver")

    val graphField = switchingProviderClass.declarations.filterIsInstance<IrField>()
      .firstOrNull { it.name.asString() == "graph" }
      ?: error("SwitchingProvider must have field: graph")

    val freshGraphExpr = irGetField(irGet(spThis), graphField)

    if (shardIndex == null || shardIndex == 0) {
      // Field is in main graph
      if (debug && shardIndex == 0) {
        log("SwitchingProviderGenerator: Accessing field in main graph")
      }
      freshGraphExpr
    } else {
      // Field is in a shard, access via graph.shardN
      if (debug) {
        log("SwitchingProviderGenerator: Cross-shard access - main -> shard$shardIndex")
      }
      val shardFieldOnGraph = graphClass.declarations
        .filterIsInstance<IrField>()
        .firstOrNull { it.name.asString() == "shard$shardIndex" }
        ?: error("Missing shard field: shard$shardIndex")
      irGetField(freshGraphExpr, shardFieldOnGraph)
    }
  }

  companion object {
    // Maximum number of cases per method to avoid hitting JVM method size limits
    private const val MAX_CASES_PER_METHOD = 100
  }

  /**
   * Populates the invoke() body of SwitchingProvider with a when(id) expression.
   *
   * IMPORTANT: This method MUST NOT add any caching wrappers (DoubleCheck/SingleCheck).
   * Caching is already applied at the field initialization level when the provider
   * fields are created. The invoke() method should only dispatch to the appropriate
   * binding code without additional wrapping.
   *
   * For large numbers of bindings (> 100), this will generate helper methods to avoid
   * hitting JVM method size limits. The main invoke() method will dispatch to the
   * appropriate helper based on ID ranges.
   *
   * @param builder The IR builder for creating expressions
   * @param graphClass The main graph class containing the SwitchingProvider
   * @param switchingProviderClass The SwitchingProvider class itself
   * @param idToBinding Ordered list of bindings by their assigned IDs
   * @param graphExpr Expression to access the graph instance from SwitchingProvider.graph
   * @param idExpr Expression to access the id from SwitchingProvider.id
   * @param returnType The return type of invoke() (typically T from Provider<T>)
   */
  @Suppress("DEPRECATION")
  fun populateInvokeBody(
    builder: IrBuilderWithScope,
    graphClass: IrClass,
    switchingProviderClass: IrClass,
    idToBinding: List<IrBinding>, // same order as assigned IDs
    graphExpr: IrExpression,      // The graph instance from SwitchingProvider.graph field
    idExpr: IrExpression,          // The id from SwitchingProvider.id field
    returnType: IrType
  ): List<IrStatement> {
    // Defensive check: empty bindings list
    if (idToBinding.isEmpty()) {
      if (debug) {
        log("SwitchingProviderGenerator: No bindings registered for SwitchingProvider")
      }
      return builder.run {
        listOf(
          irReturn(
            irInvoke(
              callee = symbols.stdlibErrorFunction,
              args = listOf(irString("SwitchingProvider not implemented - no bindings registered"))
            )
          )
        )
      }
    }

    // Check if we need to split into helper methods
    if (idToBinding.size > MAX_CASES_PER_METHOD) {
      if (debug) {
        log("SwitchingProviderGenerator: Splitting ${idToBinding.size} bindings into helper methods")
      }
      // Generate helper methods and dispatch to them
      return generateSplitInvokeBody(
        builder, graphClass, switchingProviderClass,
        idToBinding, graphExpr, idExpr, returnType
      )
    }
    // Build branches for when(id) expression
    val branches = mutableListOf<IrBranchImpl>()

    // Add a branch for each binding ID
    idToBinding.forEachIndexed { id, binding ->
      val bindingExpr = builder.run {
        // CRITICAL: Inside SwitchingProvider.invoke(), we must NEVER invoke provider fields
        // because they might be wrapped in DoubleCheck(SwitchingProvider(...)), causing recursion.
        // Instead, we always generate the instance directly.

        // For all binding types, we generate the instance creation code directly.
        // This is what gets called when the provider is invoked.
        when (binding) {
            // BoundInstance must always be read from fields, never constructed inline
            is IrBinding.BoundInstance -> {
              // Try to resolve the field in order of preference:
              // 1. Instance field (most common for BoundInstance)
              // 2. Provider field (if caller expects a provider)
              // 3. Shard registry (if sharding is active)

              val instanceField = bindingFieldContext?.instanceField(binding.typeKey)
              val providerField = bindingFieldContext?.providerField(binding.typeKey)
              val shardInfo = shardFieldRegistry?.findField(binding.typeKey)

              when {
                instanceField != null -> {
                  // Resolve the owner (could be main graph or shard)
                  val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)
                  // Return the instance directly
                  irGetField(owner, instanceField)
                }

                providerField != null -> {
                  // If we have a provider field, use it and invoke
                  val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)
                  // Get the provider and invoke it
                  val providerExpr = irGetField(owner, providerField)
                  irCall(symbols.providerInvoke).apply {
                    dispatchReceiver = providerExpr
                  }
                }

                shardInfo != null -> {
                  // Only shard registry has the field
                  val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo.shardIndex)
                  irGetField(owner, shardInfo.field)
                }

                else -> {
                  error("BoundInstance must have a field (instance or provider): ${binding.typeKey}")
                }
              }
            }

            is IrBinding.GraphDependency -> {
              // GraphDependency should read from the appropriate field or call getter
              // NEVER call inline or constructor paths for GraphDependency
              when {
                binding.fieldAccess != null -> {
                  // Use safeGetField for proper cross-shard access
                  // First try to get the instance field
                  val instanceField = bindingFieldContext?.instanceField(binding.typeKey)
                  val providerField = bindingFieldContext?.providerField(binding.typeKey)

                  when {
                    instanceField != null -> {
                      // Check if field might be in a shard
                      val shardInfo = shardFieldRegistry?.findField(binding.typeKey)
                      val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)

                      // Use expressionGenerator's safeGetField if available for proper cross-shard handling
                      if (expressionGenerator != null) {
                        expressionGenerator.safeGetField(owner, instanceField, binding.typeKey)
                      } else {
                        // Fallback to direct field access
                        irGetField(owner, instanceField)
                      }
                    }

                    providerField != null -> {
                      // We have a provider field - get it and invoke if instance is needed
                      val shardInfo = shardFieldRegistry?.findField(binding.typeKey)
                      val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)

                      val providerExpr = if (expressionGenerator != null) {
                        expressionGenerator.safeGetField(owner, providerField, binding.typeKey)
                      } else {
                        irGetField(owner, providerField)
                      }

                      // Invoke the provider to get the instance
                      irCall(symbols.providerInvoke).apply {
                        dispatchReceiver = providerExpr
                      }
                    }

                    else -> {
                      error("GraphDependency with fieldAccess must have field: ${binding.typeKey}")
                    }
                  }
                }

                binding.getter != null -> {
                  // Build irInvoke(getter) using the resolved graph or included graph instance
                  // Create fresh graph expression to avoid duplicate IR nodes
                  val freshGraphExpr = resolveOwnerForShard(switchingProviderClass, graphClass, 0)
                  val getterResult = irCall(binding.getter).apply {
                    // Getters are always on the main graph or included graph
                    dispatchReceiver = freshGraphExpr
                  }

                  // Check if we need to unwrap provider/lazy
                  val returnType = binding.getter.returnType
                  when {
                    // If getter returns Provider<T>, invoke it to get T
                    returnType.isProvider() -> {
                      irCall(symbols.providerInvoke).apply {
                        dispatchReceiver = getterResult
                      }
                    }

                    // If getter returns Lazy<T>, get value to get T
                    returnType.isLazy() -> {
                      irCall(symbols.lazyGetValue).apply {
                        dispatchReceiver = getterResult
                      }
                    }

                    // Otherwise return the direct result
                    else -> getterResult
                  }
                }

                else -> {
                  error("GraphDependency must have either fieldAccess or getter")
                }
              }
            }
            
            else -> {
              // For all other binding types, generate the instance directly
              // NEVER invoke provider fields as they might recursively call back to SwitchingProvider

              // Use the expression generator to create the instance
              if (expressionGenerator != null) {
                // Generate the instance code directly, bypassing any provider wrappers
                expressionGenerator.generateBindingCode(
                  binding = binding,
                  contextualTypeKey = binding.contextualTypeKey,
                  accessType = IrGraphExpressionGenerator.AccessType.INSTANCE,
                  fieldInitKey = null,
                  bypassProviderFor = binding.typeKey  // Critical: prevent re-routing through SwitchingProvider
                )
              } else {
                // These binding types require field resolution
                // Try to find the field in bindingFieldContext or shardFieldRegistry
                val instanceField = bindingFieldContext?.instanceField(binding.typeKey)
                val shardInfo = shardFieldRegistry?.findField(binding.typeKey)

                when {
                  instanceField != null -> {
                    // Found instance field - use it with proper owner resolution
                    val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)
                    irGetField(owner, instanceField)
                  }

                  shardInfo != null -> {
                    // Field exists in shard registry
                    val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo.shardIndex)
                    irGetField(owner, shardInfo.field)
                  }

                  else -> {
                    // No field found - this is an error for unsupported inline types
                    error("Binding type ${binding::class.simpleName} requires a field but none found: ${binding.typeKey}")
                  }
                }
              }
            }
        }
      }

      // Create a fresh ID expression for each branch to avoid IR validation errors
      // The IR validator doesn't allow reusing the same field access node in multiple places
      val freshIdExpr = builder.run {
        // We need to get the id field from the SwitchingProvider instance
        // Use the same pattern as the original idExpr but create it fresh
        // The idExpr parameter can serve as a template, but we need to recreate it

        // Find the invoke function to get its dispatch receiver
        val invokeFunction = switchingProviderClass.declarations
          .filterIsInstance<IrSimpleFunction>()
          .firstOrNull { it.name.asString() == "invoke" }
          ?: error("SwitchingProvider must have invoke() function")

        val spThis = invokeFunction.dispatchReceiverParameter
          ?: error("invoke() must have dispatch receiver")

        val idField = switchingProviderClass.declarations.filterIsInstance<IrField>()
          .firstOrNull { it.name.asString() == "id" }
          ?: error("SwitchingProvider must have field: id")

        irGetField(irGet(spThis), idField)
      }

      branches += IrBranchImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        condition = builder.irEquals(freshIdExpr, builder.irInt(id)),
        result = bindingExpr
      )
    }

    // Default branch: throw error with the unknown id
    val defaultBranch = builder.run {
      val errorMessage = IrStringConcatenationImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        symbols.irBuiltIns.stringType
      ).apply {
        arguments.add(irString("Unknown SwitchingProvider id: "))
        arguments.add(idExpr)
      }

      IrBranchImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        condition = irTrue(),
        result = irInvoke(
          callee = symbols.stdlibErrorFunction,
          args = listOf(errorMessage)
        )
      )
    }
    branches += defaultBranch

    // Create and return the when expression
    val whenExpr = builder.irWhen(
      type = returnType,
      branches = branches
    )
    
    return listOf(builder.irReturn(whenExpr))
  }

  /**
   * Generates a split invoke body that delegates to helper methods for large switch statements.
   * Each helper method handles up to MAX_CASES_PER_METHOD cases.
   */
  private fun generateSplitInvokeBody(
    builder: IrBuilderWithScope,
    graphClass: IrClass,
    switchingProviderClass: IrClass,
    idToBinding: List<IrBinding>,
    graphExpr: IrExpression,
    idExpr: IrExpression,
    returnType: IrType
  ): List<IrStatement> {
    // Calculate how many helper methods we need
    val numChunks = (idToBinding.size + MAX_CASES_PER_METHOD - 1) / MAX_CASES_PER_METHOD

    // Generate helper methods
    for (chunkIndex in 0 until numChunks) {
      val startId = chunkIndex * MAX_CASES_PER_METHOD
      val endId = minOf(startId + MAX_CASES_PER_METHOD - 1, idToBinding.size - 1)
      val chunkBindings = idToBinding.subList(startId, endId + 1)

      generateHelperMethod(
        switchingProviderClass,
        chunkIndex,
        startId,
        endId,
        chunkBindings,
        returnType,
        graphClass
      )
    }

    // Generate main invoke body that dispatches to helpers
    return builder.run {
      val branches = mutableListOf<IrBranchImpl>()

      // Add a branch for each chunk
      for (chunkIndex in 0 until numChunks) {
        val startId = chunkIndex * MAX_CASES_PER_METHOD
        val endId = minOf(startId + MAX_CASES_PER_METHOD - 1, idToBinding.size - 1)

        // Find the helper method
        val helperMethod = switchingProviderClass.declarations
          .filterIsInstance<IrSimpleFunction>()
          .firstOrNull { it.name.asString() == "invoke\$chunk$chunkIndex" }
          ?: error("Helper method invoke\$chunk$chunkIndex not found")

        // Create condition: id in startId..endId
        val condition = if (startId == endId) {
          // Single case
          irEquals(idExpr, irInt(startId))
        } else {
          // Range check: id >= startId && id <= endId
          // Create comparison operations - the map expects a classifier symbol, not a type
          val intClassifier = symbols.irBuiltIns.intClass
          val geOp = irCall(symbols.irBuiltIns.greaterOrEqualFunByOperandType[intClassifier]!!).apply {
            arguments[0] = idExpr
            arguments[1] = irInt(startId)
          }
          val leOp = irCall(symbols.irBuiltIns.lessOrEqualFunByOperandType[intClassifier]!!).apply {
            arguments[0] = idExpr
            arguments[1] = irInt(startId)
          }
          // Combine with AND
          irCall(symbols.irBuiltIns.andandSymbol).apply {
            arguments[0] = geOp
            arguments[1] = leOp
          }
        }

        // Call the helper method
        val helperCall = irCall(helperMethod.symbol).apply {
          dispatchReceiver = irGet(switchingProviderClass.thisReceiver!!)
          arguments[0] = idExpr
          arguments[1] = graphExpr
        }

        branches += IrBranchImpl(
          UNDEFINED_OFFSET,
          UNDEFINED_OFFSET,
          condition = condition,
          result = helperCall
        )
      }

      // Default branch: throw error with the unknown id
      val errorMessage = IrStringConcatenationImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        symbols.irBuiltIns.stringType
      ).apply {
        arguments.add(irString("Unknown SwitchingProvider id: "))
        arguments.add(idExpr)
      }

      branches += IrBranchImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        condition = irTrue(),
        result = irInvoke(
          callee = symbols.stdlibErrorFunction,
          args = listOf(errorMessage)
        )
      )

      val whenExpr = irWhen(
        type = returnType,
        branches = branches
      )

      listOf(irReturn(whenExpr))
    }
  }

  /**
   * Generates a helper method that handles a chunk of switch cases.
   */
  private fun generateHelperMethod(
    switchingProviderClass: IrClass,
    chunkIndex: Int,
    startId: Int,
    endId: Int,
    chunkBindings: List<IrBinding>,
    returnType: IrType,
    graphClass: IrClass
  ) {
    // Create the helper method using the correct pattern
    val helperMethod = switchingProviderClass.addFunction {
      name = Name.identifier("invoke\$chunk$chunkIndex")
      visibility = DescriptorVisibilities.PRIVATE
      modality = Modality.FINAL
      this.returnType = returnType
    }

    // Add parameters: graph and id
    val graphParam = helperMethod.addValueParameter {
      name = Name.identifier("graph")
      type = graphClass.defaultType
    }

    val idParam = helperMethod.addValueParameter {
      name = Name.identifier("id")
      type = symbols.irBuiltIns.intType
    }

    // Add dispatch receiver
    helperMethod.setExtensionReceiver(switchingProviderClass.thisReceiver)

    // Build the method body with the subset of cases
    helperMethod.body = irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
      val builder = createIrBuilder(helperMethod.symbol)
      val branches = mutableListOf<IrBranchImpl>()

      // Generate branches for this chunk's bindings
      chunkBindings.forEachIndexed { index, binding ->
        val actualId = startId + index
        val bindingExpr = generateBindingExpression(
          builder,
          binding,
          builder.irGet(graphParam),
          graphClass,
          switchingProviderClass
        )

        branches += IrBranchImpl(
          UNDEFINED_OFFSET,
          UNDEFINED_OFFSET,
          condition = builder.irEquals(builder.irGet(idParam), builder.irInt(actualId)),
          result = bindingExpr
        )
      }

      // Default case (should not happen if main dispatch is correct)
      val errorMessage = builder.run {
        IrStringConcatenationImpl(
          UNDEFINED_OFFSET,
          UNDEFINED_OFFSET,
          symbols.irBuiltIns.stringType
        ).apply {
          arguments.add(irString("Unexpected id in chunk $chunkIndex: "))
          arguments.add(irGet(idParam))
        }
      }

      branches += IrBranchImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        condition = builder.irTrue(),
        result = builder.irInvoke(
          callee = symbols.stdlibErrorFunction,
          args = listOf(errorMessage)
        )
      )

      val whenExpr = builder.irWhen(
        type = returnType,
        branches = branches
      )

      statements += builder.irReturn(whenExpr)
    }
  }

  /**
   * Generates the expression for a single binding.
   * This is extracted from the original populateInvokeBody logic.
   */
  private fun generateBindingExpression(
    builder: IrBuilderWithScope,
    binding: IrBinding,
    graphExpr: IrExpression,
    graphClass: IrClass,
    switchingProviderClass: IrClass
  ): IrExpression = builder.run {
    // Strategy: Prefer existing provider fields over inline generation to avoid recursion

    // First, check bindingFieldContext for a provider field
    val providerField = bindingFieldContext?.providerField(binding.typeKey)

    if (providerField != null) {
      // Found a provider field in bindingFieldContext - use it with proper owner resolution
      // Check if this field might be in a shard
      val shardInfo = shardFieldRegistry?.findField(binding.typeKey)
      val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)

      // Get the provider field and invoke it to get the instance
      val providerExpr = irGetField(owner, providerField)

      // Invoke the provider to get the instance
      irCall(symbols.providerInvoke).apply {
        dispatchReceiver = providerExpr
      }
    } else {
      // No provider field found - need to handle special cases or generate inline

      when (binding) {
        // BoundInstance must always be read from fields, never constructed inline
        is IrBinding.BoundInstance -> {
          // Try to resolve the field in order of preference:
          // 1. Instance field (most common for BoundInstance)
          // 2. Provider field (if caller expects a provider)
          // 3. Shard registry (if sharding is active)

          val instanceField = bindingFieldContext?.instanceField(binding.typeKey)
          val providerField = bindingFieldContext?.providerField(binding.typeKey)
          val shardInfo = shardFieldRegistry?.findField(binding.typeKey)

          when {
            instanceField != null -> {
              // Resolve the owner (could be main graph or shard)
              val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)
              // Return the instance directly
              irGetField(owner, instanceField)
            }

            providerField != null -> {
              // If we have a provider field, use it and invoke
              val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)
              // Get the provider and invoke it
              val providerExpr = irGetField(owner, providerField)
              irCall(symbols.providerInvoke).apply {
                dispatchReceiver = providerExpr
              }
            }

            shardInfo != null -> {
              // Only shard registry has the field
              val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo.shardIndex)
              irGetField(owner, shardInfo.field)
            }

            else -> {
              error("BoundInstance must have a field (instance or provider): ${binding.typeKey}")
            }
          }
        }

        is IrBinding.GraphDependency -> {
          // GraphDependency should read from the appropriate field or call getter
          // NEVER call inline or constructor paths for GraphDependency
          when {
            binding.fieldAccess != null -> {
              // Use safeGetField for proper cross-shard access
              // First try to get the instance field
              val instanceField = bindingFieldContext?.instanceField(binding.typeKey)
              val providerField = bindingFieldContext?.providerField(binding.typeKey)

              when {
                instanceField != null -> {
                  // Check if field might be in a shard
                  val shardInfo = shardFieldRegistry?.findField(binding.typeKey)
                  val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)

                  // Use expressionGenerator's safeGetField if available for proper cross-shard handling
                  if (expressionGenerator != null) {
                    expressionGenerator.safeGetField(owner, instanceField, binding.typeKey)
                  } else {
                    // Fallback to direct field access
                    irGetField(owner, instanceField)
                  }
                }

                providerField != null -> {
                  // We have a provider field - get it and invoke if instance is needed
                  val shardInfo = shardFieldRegistry?.findField(binding.typeKey)
                  val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)

                  val providerExpr = if (expressionGenerator != null) {
                    expressionGenerator.safeGetField(owner, providerField, binding.typeKey)
                  } else {
                    irGetField(owner, providerField)
                  }

                  // Invoke the provider to get the instance
                  irCall(symbols.providerInvoke).apply {
                    dispatchReceiver = providerExpr
                  }
                }

                else -> {
                  error("GraphDependency with fieldAccess must have field: ${binding.typeKey}")
                }
              }
            }

            binding.getter != null -> {
              // Build irInvoke(getter) using the resolved graph or included graph instance
              // For getters, we just use the passed graphExpr since it's fresh in helper methods
              // and we don't need shard resolution for getters (always on main graph)
              val getterResult = irCall(binding.getter).apply {
                // Getters are always on the main graph or included graph
                dispatchReceiver = graphExpr
              }

              // Check if we need to unwrap provider/lazy
              val returnType = binding.getter.returnType
              when {
                // If getter returns Provider<T>, invoke it to get T
                returnType.isProvider() -> {
                  irCall(symbols.providerInvoke).apply {
                    dispatchReceiver = getterResult
                  }
                }

                // If getter returns Lazy<T>, get value to get T
                returnType.isLazy() -> {
                  irCall(symbols.lazyGetValue).apply {
                    dispatchReceiver = getterResult
                  }
                }

                // Otherwise return the direct result
                else -> getterResult
              }
            }

            else -> {
              error("GraphDependency must have either fieldAccess or getter")
            }
          }
        }

        else -> {
          // Only allow inline generation for safe binding types
          // All other types must be resolved via fields to avoid unsupported binding errors
          val canGenerateInline = when (binding) {
            is IrBinding.ConstructorInjected -> !binding.isAssisted // Non-assisted only
            is IrBinding.Provided -> true
            is IrBinding.ObjectClass -> true
            else -> false // Alias, Assisted, MembersInjected, Multibinding, etc. require fields
          }

          if (canGenerateInline) {
            // Safe to generate inline with bypassProviderFor to prevent recursion
            expressionGenerator?.generateBindingCode(
              binding = binding,
              contextualTypeKey = binding.contextualTypeKey,
              accessType = IrGraphExpressionGenerator.AccessType.INSTANCE,
              fieldInitKey = null,
              bypassProviderFor = binding.typeKey  // Prevent re-routing through SwitchingProvider
            ) ?: error("ExpressionGenerator is required for inline generation")
          } else {
            // These binding types require field resolution
            // Try to find the field in bindingFieldContext or shardFieldRegistry
            val instanceField = bindingFieldContext?.instanceField(binding.typeKey)
            val shardInfo = shardFieldRegistry?.findField(binding.typeKey)

            when {
              instanceField != null -> {
                // Found instance field - use it with proper owner resolution
                val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo?.shardIndex)
                irGetField(owner, instanceField)
              }

              shardInfo != null -> {
                // Field exists in shard registry
                val owner = resolveOwnerForShard(switchingProviderClass, graphClass, shardInfo.shardIndex)
                irGetField(owner, shardInfo.field)
              }

              else -> {
                // No field found - this is an error for unsupported inline types
                error("Binding type ${binding::class.simpleName} requires a field but none found: ${binding.typeKey}")
              }
            }
          }
        }
      }
    }
  }
}