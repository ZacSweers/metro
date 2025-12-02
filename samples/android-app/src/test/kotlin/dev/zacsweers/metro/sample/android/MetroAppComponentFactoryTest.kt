// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metrox.android.ActivityKey
import dev.zacsweers.metrox.android.BroadcastReceiverKey
import dev.zacsweers.metrox.android.ContentProviderKey
import dev.zacsweers.metrox.android.MetroAppComponentProviders
import dev.zacsweers.metrox.android.MetroApplication
import dev.zacsweers.metrox.android.ServiceKey
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

const val TEST_STRING = "Hello, Metro!"
const val TEST_ACTION = "dev.zacsweers.metro.sample.android.TEST_ACTION"
const val EXTRA_DATA = "extra_data"

class TestApp : Application(), MetroApplication {
  override val appComponentProviders: MetroAppComponentProviders by lazy {
    createGraph<TestAppGraph>()
  }
}

@DependencyGraph
interface TestAppGraph : MetroAppComponentProviders {
  @Provides fun provideString(): String = TEST_STRING
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
@Ignore("https://github.com/robolectric/robolectric/pull/10833")
class MetroAppComponentFactoryTest {

  @Test
  fun activity() {
    val activity = Robolectric.buildActivity(TestActivity::class.java).setup().get()
    assertThat(activity.value).isEqualTo(TEST_STRING)
  }

  @Test
  fun service() {
    val service = Robolectric.buildService(TestService::class.java).create().get()
    assertThat(service.value).isEqualTo(TEST_STRING)
  }

  @Test
  fun broadcastReceiver() {
    val context = RuntimeEnvironment.getApplication()
    val intent = Intent(TEST_ACTION).putExtra(EXTRA_DATA, "broadcast_data")
    context.sendBroadcast(intent)
    // Verify the receiver was injected with the test string and received the broadcast data
    assertThat(TestReceiver.lastInjectedValue).isEqualTo(TEST_STRING)
    assertThat(TestReceiver.lastReceivedData).isEqualTo("broadcast_data")
  }

  @Test
  fun contentProvider() {
    val provider = Robolectric.setupContentProvider(TestProvider::class.java)
    assertThat(provider.value).isEqualTo(TEST_STRING)
  }

  // Test component classes
  @Inject @ActivityKey(TestActivity::class) class TestActivity(val value: String) : Activity()

  @Inject
  @ServiceKey(TestService::class)
  class TestService(val value: String) : Service() {
    override fun onBind(intent: Intent?) = null
  }

  @Inject
  @BroadcastReceiverKey(TestReceiver::class)
  class TestReceiver(val value: String) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      lastInjectedValue = value
      lastReceivedData = intent?.getStringExtra(EXTRA_DATA)
    }

    companion object {
      var lastInjectedValue: String? = null
      var lastReceivedData: String? = null
    }
  }

  @Inject
  @ContentProviderKey(TestProvider::class)
  class TestProvider(val value: String) : ContentProvider() {
    override fun onCreate() = true

    override fun query(
      uri: android.net.Uri,
      projection: Array<out String>?,
      selection: String?,
      selectionArgs: Array<out String>?,
      sortOrder: String?,
    ) = null

    override fun getType(uri: android.net.Uri) = null

    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null

    override fun delete(
      uri: android.net.Uri,
      selection: String?,
      selectionArgs: Array<out String>?,
    ) = 0

    override fun update(
      uri: android.net.Uri,
      values: android.content.ContentValues?,
      selection: String?,
      selectionArgs: Array<out String>?,
    ) = 0
  }
}
