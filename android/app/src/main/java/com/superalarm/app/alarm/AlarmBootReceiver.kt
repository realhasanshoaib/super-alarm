package com.superalarm.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
      val config = AlarmStorage.readAlarmConfig(context)
      if (config.enabled) {
        AlarmScheduler.scheduleNext(context, config)
      }
    }
  }
}
