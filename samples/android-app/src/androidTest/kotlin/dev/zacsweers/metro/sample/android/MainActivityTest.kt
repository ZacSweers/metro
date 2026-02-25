// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import androidx.test.core.app.launchActivity
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.createDynamicGraphFactory
import org.junit.Before
import org.junit.Test

class MainActivityTest {

  private val tracker = FakeTrackerBindings.tracker

  @Before
  fun inject() {
    val app =
      InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MetroApp
    val graph = createDynamicGraphFactory<AppGraph.Factory>(FakeTrackerBindings).create(app)
    app.setTestGraph(graph)
  }

  @Test
  fun incrementButtonClickEventIsTracked() {
    // given
    launchActivity<MainActivity>()

    // when
    Espresso.onView(withId(R.id.increment_button)).perform(click())

    // then
    assertThat(tracker.increments).isEqualTo(1)
  }
}
