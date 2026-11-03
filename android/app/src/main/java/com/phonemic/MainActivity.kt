package com.phonemic

import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    val context = (application as MainApplication).reactHost.currentReactContext
    val module = context?.getNativeModule("PhoneMic") as? PhoneMicModule
    return if (module?.headsetKey(event) == true) true else super.dispatchKeyEvent(event)
  }

  override fun onResume() {
    super.onResume()
    ContextCompat.startForegroundService(this, Intent(this, ModelKeepAliveService::class.java))
  }

  override fun invokeDefaultOnBackPressed() {
    moveTaskToBack(true)
  }

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "PhoneMIC"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)
}
