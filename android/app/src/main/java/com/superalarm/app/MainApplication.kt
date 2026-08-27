package com.superalarm.app

import android.app.Application
import android.os.UserManager
import com.superalarm.app.alarm.AlarmRingingService
import com.superalarm.app.alarm.AlarmStorage
import com.superalarm.app.data.SuperAlarmStore
import com.superalarm.app.telemetry.SuperAlarmTelemetry

class MainApplication : Application() {
  val store: SuperAlarmStore by lazy { SuperAlarmStore(applicationContext, telemetry = SuperAlarmTelemetry) }

  override fun onCreate() {
    super.onCreate()
    AlarmStorage.migrateToDeviceProtectedStorage(this)
    AlarmRingingService.ensureNotificationChannel(this)
    initializeTelemetryIfUnlocked()
  }

  fun initializeTelemetryIfUnlocked() {
    if (getSystemService(UserManager::class.java).isUserUnlocked) {
      SuperAlarmTelemetry.initialize(this)
    }
  }
}
