package com.superalarm.app

import android.app.Application
import com.superalarm.app.alarm.AlarmRingingService
import com.superalarm.app.alarm.AlarmStorage
import com.superalarm.app.data.SuperAlarmStore

class MainApplication : Application() {
  val store: SuperAlarmStore by lazy { SuperAlarmStore(applicationContext) }

  override fun onCreate() {
    super.onCreate()
    AlarmStorage.migrateToDeviceProtectedStorage(this)
    AlarmRingingService.ensureNotificationChannel(this)
  }
}
