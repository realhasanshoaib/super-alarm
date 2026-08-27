package com.superalarm.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.superalarm.app.alarm.AlarmConstants
import com.superalarm.app.alarm.AlarmRingingService
import com.superalarm.app.data.SuperAlarmStore
import com.superalarm.app.telemetry.ProductEvent
import com.superalarm.app.telemetry.SuperAlarmTelemetry
import com.superalarm.app.telemetry.TelemetryEventName
import com.superalarm.app.telemetry.TelemetrySource
import com.superalarm.app.ui.SuperAlarmApp

class MainActivity : ComponentActivity() {
  private lateinit var store: SuperAlarmStore

  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.AppTheme)
    super.onCreate(savedInstanceState)
    (application as MainApplication).initializeTelemetryIfUnlocked()
    SuperAlarmTelemetry.capture(ProductEvent(TelemetryEventName.AppOpened, source = TelemetrySource.App))
    enableEdgeToEdge()
    store = (application as MainApplication).store
    applyAlarmWindowFlags(intent)
    store.handleLaunchIntent(intent)
    setContent { SuperAlarmApp(store = store) }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    applyAlarmWindowFlags(intent)
    if (::store.isInitialized) {
      store.handleLaunchIntent(intent)
    }
  }

  @SuppressLint("RestrictedApi")
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (AlarmRingingService.isActive() &&
      (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
    ) {
      return true
    }
    return super.dispatchKeyEvent(event)
  }

  private fun applyAlarmWindowFlags(intent: Intent?) {
    if (intent?.getBooleanExtra(AlarmConstants.EXTRA_LAUNCH_ALARM, false) != true) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    }

    window.addFlags(
      WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
        WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
    )
  }
}
