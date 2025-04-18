/*
 * Copyright 2022 Google LLC
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro.compiler

internal class MemoizedSequence<T>(sequence: Sequence<T>) : Sequence<T> {

  private val cache = ArrayList<T>()

  private var iter: Lazy<Iterator<T>>? = lazy { sequence.iterator() }

  internal val isCacheOnly: Boolean
    get() = iter == null

  private inner class CachedIterator() : Iterator<T> {
    var idx = 0

    override fun hasNext(): Boolean {
      if (idx < cache.size) {
        return true
      }

      val iterRef = iter
      if (iterRef == null) {
        return false
      }

      val iterValue = iterRef.value
      val hasNext = iterValue.hasNext()

      if (!hasNext) {
        // Relinquish the underlying sequence to GC after exhaustion
        iter = null
      }

      return hasNext
    }

    override fun next(): T {
      if (idx < cache.size) {
        return cache[idx++]
      }

      val iterRef = iter ?: throw NoSuchElementException()
      val value = iterRef.value.next()
      cache.add(value)
      idx++
      return value
    }
  }

  override fun iterator(): Iterator<T> {
    return CachedIterator()
  }
}

internal fun <T> Sequence<T>.memoized() = MemoizedSequence(this)
