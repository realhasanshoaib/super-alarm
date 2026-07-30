package com.superalarm.app

import android.app.Application
import com.superalarm.app.data.SuperAlarmStore

class MainApplication : Application() {
  val store: SuperAlarmStore by lazy { SuperAlarmStore(applicationContext) }
}
