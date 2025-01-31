/*
 * Copyright (C) 2016 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro

/**
 * The declaration's return type forms the type argument for the value of a `Map<K, Provider<V>>`,
 * and the combination of the annotated key and the returned value is contributed to the map as a
 * key/value pair. The `Map<K, Provider<V>>` produced from the accumulation of values will be
 * immutable.
 */
@MustBeDocumented
@Target(
  AnnotationTarget.FUNCTION,
  AnnotationTarget.FIELD,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.CLASS,
)
@Retention(AnnotationRetention.RUNTIME)
public annotation class IntoMap
