/*
 * Copyright (C) 2024 The Dagger Authors.
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
@file:Suppress("RUNTIME_ANNOTATION_NOT_SUPPORTED") // Only read at compile-time

package dev.zacsweers.lattice.annotations.multibindings

import kotlin.reflect.KClass

/**
 * A [MapKey] annotation for maps with `Class<?>` keys.
 *
 * The difference from [ClassKey] is that dagger generates a string representation for the class to
 * use under the hood, which prevents loading unused classes at runtime.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@MapKey
public annotation class LazyClassKey(val value: KClass<*>)
