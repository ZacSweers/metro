package dev.zacsweers.lattice.internal

/**
 * Returns a new list that is pre-sized to [size], or [emptyList] if empty. The list returned is
 * never intended to grow beyond [size], so adding to a list when the size is 0 is an error.
 */
public fun <T : Any> presizedList(size: Int): MutableList<T> =
  if (size == 0) {
    // Note: cannot use emptyList() here because Kotlin (helpfully) doesn't allow that cast at
    // runtime
    mutableListOf()
  } else {
    ArrayList<T>(size)
  }

/** Returns true if at least one pair of items in [this] are equal. */
public fun List<*>.hasDuplicates(): Boolean =
  if (size < 2) {
    false
  } else {
    size != toSet().size
  }
