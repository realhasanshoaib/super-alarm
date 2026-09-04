package com.superalarm.app.widget

import android.os.Bundle
import androidx.glance.appwidget.action.InvisibleActionTrampolineActivity

private const val glanceActionIntentExtra = "ACTION_INTENT"

class ResilientWidgetActionActivity : InvisibleActionTrampolineActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    try {
      super.onCreate(savedInstanceState)
    } catch (failure: IllegalArgumentException) {
      if (intent.hasExtra(glanceActionIntentExtra)) {
        throw failure
      }
      SuperAlarmWidgetUpdater.requestUpdate(this)
      finish()
    }
  }
}
