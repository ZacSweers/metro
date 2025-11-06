/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package dev.zacsweers.metro.compiler.ir.cache

internal object IrThreadUnsafeCachesFactory : IrCachesFactory() {
  override fun <K : Any, V, CONTEXT> createCache(
    createValue: (K, CONTEXT) -> V
  ): IrCache<K, V, CONTEXT> = IrThreadUnsafeCache(createValue = createValue)

  override fun <K : Any, V, CONTEXT> createCache(
    initialCapacity: Int,
    loadFactor: Float,
    createValue: (K, CONTEXT) -> V,
  ): IrCache<K, V, CONTEXT> = IrThreadUnsafeCache(HashMap(initialCapacity, loadFactor), createValue)

  override fun <K : Any, V, CONTEXT, DATA> createCacheWithPostCompute(
    createValue: (K, CONTEXT) -> Pair<V, DATA>,
    postCompute: (K, V, DATA) -> Unit,
  ): IrCache<K, V, CONTEXT> = IrThreadUnsafeCacheWithPostCompute(createValue, postCompute)

  override fun <K : Any, V, CONTEXT> createCacheWithSuggestedLimits(
    maximumSize: Long?,
    keyStrength: KeyReferenceStrength,
    valueStrength: ValueReferenceStrength,
    createValue: (K, CONTEXT) -> V,
  ): IrCache<K, V, CONTEXT> = createCache(createValue)

  override fun <V> createLazyValue(createValue: () -> V): IrLazyValue<V> =
    FirThreadUnsafeValue(createValue)

  override fun <V> createPossiblySoftLazyValue(createValue: () -> V): IrLazyValue<V> =
    createLazyValue(createValue)
}

@Suppress("UNCHECKED_CAST")
private class IrThreadUnsafeCache<K : Any, V, CONTEXT>(
  private val map: MutableMap<K, V> = mutableMapOf(),
  private val createValue: (K, CONTEXT) -> V,
) : IrCache<K, V, CONTEXT>() {

  override fun getValue(key: K, context: CONTEXT): V =
    map.getOrElse(key) {
      createValue(key, context).also { createdValue -> map[key] = createdValue }
    }

  override fun getValueIfComputed(key: K): V? = map.getOrElse(key) { null as V }
}

private class IrThreadUnsafeCacheWithPostCompute<K : Any, V, CONTEXT, DATA>(
  private val createValue: (K, CONTEXT) -> Pair<V, DATA>,
  private val postCompute: (K, V, DATA) -> Unit,
) : IrCache<K, V, CONTEXT>() {
  private val map = mutableMapOf<K, V>()

  override fun getValue(key: K, context: CONTEXT): V =
    map.getOrElse(key) {
      val (createdValue, data) = createValue(key, context)
      map[key] = createdValue
      postCompute(key, createdValue, data)
      createdValue
    }

  @Suppress("UNCHECKED_CAST")
  override fun getValueIfComputed(key: K): V? = map.getOrElse(key) { null as V }
}

private class FirThreadUnsafeValue<V>(createValue: () -> V) : IrLazyValue<V>() {
  private val lazyValue by lazy(LazyThreadSafetyMode.NONE, createValue)

  override fun getValue(): V = lazyValue
}
