package dev.zacsweers.metro.compiler.sharding

import dev.zacsweers.metro.compiler.ir.IrBinding
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import org.jetbrains.kotlin.ir.declarations.IrField

/**
 * Registry that tracks the location and names of binding fields across shards.
 * 
 * This registry solves the coordination problem between shard generation and cross-shard access
 * by maintaining a mapping of which bindings are in which shards and their corresponding field names.
 */
internal class ShardFieldRegistry {
  
  /**
   * Information about a binding field in a shard.
   */
  data class FieldInfo(
    val shardIndex: Int,
    val field: IrField,
    val fieldName: String,
    val binding: IrBinding
  )
  
  /** Maps type keys to their field information */
  private val fieldsByTypeKey = mutableMapOf<IrTypeKey, FieldInfo>()
  
  /** Maps shard index to all fields in that shard */
  private val fieldsByShard = mutableMapOf<Int, MutableList<FieldInfo>>()
  
  /** Maps IR fields to their field information for efficient reverse lookups */
  private val fieldsByIrField = mutableMapOf<IrField, FieldInfo>()
  
  /**
   * Registers a binding field in a specific shard.
   * 
   * @param typeKey The type key of the binding
   * @param shardIndex The index of the shard containing this field
   * @param field The generated IR field
   * @param fieldName The name of the field
   * @param binding The binding associated with this field
   */
  fun registerField(
    typeKey: IrTypeKey,
    shardIndex: Int,
    field: IrField,
    fieldName: String,
    binding: IrBinding
  ) {
    val info = FieldInfo(shardIndex, field, fieldName, binding)
    fieldsByTypeKey[typeKey] = info
    fieldsByShard.getOrPut(shardIndex) { mutableListOf() }.add(info)
    fieldsByIrField[field] = info
  }
  
  /**
   * Finds the field information for a given type key.
   * 
   * @param typeKey The type key to look up
   * @return The field information, or null if not found
   */
  fun findField(typeKey: IrTypeKey): FieldInfo? {
    return fieldsByTypeKey[typeKey]
  }
  
  /**
   * Finds the field information for a given IR field.
   * 
   * DIAGNOSTIC USE ONLY: This method includes a fallback name-based lookup for diagnostic purposes.
   * Do not use this in initialization paths or performance-critical code.
   * For production code, use findField(typeKey) instead.
   * 
   * @param field The IR field to look up
   * @return The field information, or null if not found
   */
  fun findFieldByIrField(field: IrField): FieldInfo? {
    // First try the efficient map lookup
    fieldsByIrField[field]?.let { return it }
    
    // Fallback: search by field name (diagnostic only)
    val name = field.name.asString()
    return fieldsByTypeKey.values.firstOrNull { it.fieldName == name }
  }
  
  /**
   * Gets all fields in a specific shard.
   * 
   * @param shardIndex The shard index
   * @return List of field information for that shard
   */
  fun getFieldsInShard(shardIndex: Int): List<FieldInfo> {
    return fieldsByShard[shardIndex] ?: emptyList()
  }
  
  /**
   * Gets all fields in a specific shard (alias for getFieldsInShard for API consistency).
   * 
   * @param index The shard index
   * @return List of field information for that shard
   */
  fun fieldsInShard(index: Int): List<FieldInfo> {
    return getFieldsInShard(index)
  }
  
  /**
   * Checks if a binding is in a specific shard.
   * 
   * @param typeKey The type key of the binding
   * @param shardIndex The shard index to check
   * @return True if the binding is in the specified shard
   */
  fun isInShard(typeKey: IrTypeKey, shardIndex: Int): Boolean {
    return fieldsByTypeKey[typeKey]?.shardIndex == shardIndex
  }
  
  /**
   * Gets the shard index containing a specific binding.
   * 
   * @param typeKey The type key of the binding
   * @return The shard index, or null if not found
   */
  fun getShardIndex(typeKey: IrTypeKey): Int? {
    return fieldsByTypeKey[typeKey]?.shardIndex
  }
  
  /**
   * Returns all registered field information as a sequence for efficient iteration.
   */
  fun allFields(): Sequence<FieldInfo> {
    return fieldsByTypeKey.values.asSequence()
  }
  
  /**
   * Clears all registered fields.
   */
  fun clear() {
    fieldsByTypeKey.clear()
    fieldsByShard.clear()
    fieldsByIrField.clear()
  }
}