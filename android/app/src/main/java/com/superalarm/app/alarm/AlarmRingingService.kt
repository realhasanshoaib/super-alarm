package com.superalarm.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.superalarm.app.MainActivity
import com.superalarm.app.R

class AlarmRingingService : Service() {
  private var mediaPlayer: MediaPlayer? = null
  private var vibrator: Vibrator? = null
  private var audioManager: AudioManager? = null
  private val volumeHandler = Handler(Looper.getMainLooper())
  private var restoreAlarmVolume: Int? = null
  private val volumeEnforcer = object : Runnable {
    override fun run() {
      forceAlarmVolume()
      volumeHandler.postDelayed(this, 750L)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    isAlarmActive = true
    ensureNotificationChannel(this)

    val alarmId = intent?.getStringExtra(AlarmConstants.EXTRA_ALARM_ID) ?: "morning-alarm"
    val attemptId = intent?.getStringExtra(AlarmConstants.EXTRA_ATTEMPT_ID) ?: "attempt-${System.currentTimeMillis()}"
    val kind = intent?.getStringExtra(AlarmConstants.EXTRA_KIND) ?: "live"
    val triggeredAt = intent?.getStringExtra(AlarmConstants.EXTRA_TRIGGERED_AT) ?: java.time.Instant.now().toString()
    val backupStage = intent?.getIntExtra(AlarmConstants.EXTRA_BACKUP_STAGE, 0) ?: 0
    val soundId = intent?.getStringExtra(AlarmConstants.EXTRA_SOUND_ID) ?: "alarm"
    val vibrationEnabled = intent?.getBooleanExtra(AlarmConstants.EXTRA_VIBRATION_ENABLED, true) ?: true

    val fullScreenIntent = PendingIntent.getActivity(
      this,
      "$alarmId:$attemptId".hashCode(),
      Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putExtra(AlarmConstants.EXTRA_LAUNCH_ALARM, true)
        putExtra(AlarmConstants.EXTRA_ALARM_ID, alarmId)
        putExtra(AlarmConstants.EXTRA_ATTEMPT_ID, attemptId)
        putExtra(AlarmConstants.EXTRA_KIND, kind)
        putExtra(AlarmConstants.EXTRA_TRIGGERED_AT, triggeredAt)
        putExtra(AlarmConstants.EXTRA_BACKUP_STAGE, backupStage)
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    startForeground(AlarmConstants.NOTIFICATION_ID, buildNotification(fullScreenIntent, kind, backupStage))

    startAudio(soundId)
    if (vibrationEnabled) {
      startVibration()
    }
    startVolumeLock()

    return START_STICKY
  }

  override fun onDestroy() {
    isAlarmActive = false
    stopAudio()
    super.onDestroy()
  }

  private fun buildNotification(fullScreenIntent: PendingIntent, kind: String, backupStage: Int): Notification =
    NotificationCompat.Builder(this, AlarmConstants.NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(if (kind == "test") "Test alarm running" else "Super Alarm ringing")
      .setContentText(if (backupStage > 0) "Backup ring is active. Scan an allowed target to dismiss." else "Scan an allowed target to dismiss.")
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOngoing(true)
      .setAutoCancel(false)
      .setFullScreenIntent(fullScreenIntent, true)
      .setContentIntent(fullScreenIntent)
      .build()

  private fun startAudio(soundId: String) {
    stopAudio()

    val uri = when (soundId) {
      "notification" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
      "ringtone" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
      else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

    mediaPlayer = MediaPlayer().apply {
      setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
      setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ALARM)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build(),
      )
      isLooping = true
      setDataSource(applicationContext, uri)
      prepare()
      start()
    }
  }

  private fun startVibration() {
    vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    val pattern = longArrayOf(0, 700, 500)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
    } else {
      @Suppress("DEPRECATION")
      vibrator?.vibrate(pattern, 0)
    }
  }

  private fun stopAudio() {
    stopVolumeLock()
    mediaPlayer?.run {
      if (isPlaying) {
        stop()
      }
      release()
    }
    mediaPlayer = null
    vibrator?.cancel()
    vibrator = null
  }

  private fun startVolumeLock() {
    audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    if (restoreAlarmVolume == null) {
      restoreAlarmVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM)
    }

    forceAlarmVolume()
    volumeHandler.removeCallbacks(volumeEnforcer)
    volumeHandler.post(volumeEnforcer)
  }

  private fun stopVolumeLock() {
    volumeHandler.removeCallbacks(volumeEnforcer)

    val previousVolume = restoreAlarmVolume
    if (previousVolume != null) {
      audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
    }

    restoreAlarmVolume = null
    audioManager = null
  }

  private fun forceAlarmVolume() {
    val manager = audioManager ?: return
    val maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
    if (manager.getStreamVolume(AudioManager.STREAM_ALARM) < maxVolume) {
      manager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
    }
  }

  companion object {
    @Volatile
    private var isAlarmActive = false

    fun isActive(): Boolean = isAlarmActive

    fun ensureNotificationChannel(context: Context) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        return
      }

      val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val channel = NotificationChannel(
        AlarmConstants.NOTIFICATION_CHANNEL_ID,
        "Super Alarm",
        NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        description = "Super Alarm wake-up flow"
        setBypassDnd(true)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      }
      manager.createNotificationChannel(channel)
    }
  }
}
