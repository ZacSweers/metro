// Regression test for https://github.com/ZacSweers/metro/issues/1236
// ENABLE_DAGGER_INTEROP
// WITH_ANVIL
package com.mad.interop

import com.squareup.anvil.annotations.ContributesMultibinding
import com.squareup.anvil.annotations.MergeComponent
import com.squareup.anvil.annotations.optional.SingleIn
import dagger.multibindings.ClassKey
import javax.inject.Inject
import javax.inject.Provider
import kotlin.reflect.KClass

interface Multibinding

@ContributesMultibinding(AppScope::class)
@ClassKey(Multibinding1::class)
class Multibinding1 @Inject constructor() : Multibinding

@SingleIn(AppScope::class)
class MultibindingsReference
@Inject
constructor(val multibindings: Map<KClass<*>, Provider<Multibinding>>)

@SingleIn(AppScope::class)
@MergeComponent(AppScope::class)
interface AppGraph {
  val multibindingsReference: MultibindingsReference
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertTrue(graph.multibindingsReference.multibindings.size == 1)
  return "OK"
}
