package com.superalarm.app.widget

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.appwidget.AppWidgetManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.GlanceComponents
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import com.superalarm.app.MainApplication
import com.superalarm.app.MainActivity
import com.superalarm.app.R
import com.superalarm.app.alarm.AlarmStorage
import com.superalarm.app.telemetry.ProductEvent
import com.superalarm.app.telemetry.SuperAlarmTelemetry
import com.superalarm.app.telemetry.TelemetryEventName
import com.superalarm.app.telemetry.TelemetryFailureOperation
import com.superalarm.app.telemetry.TelemetryOutcome
import com.superalarm.app.telemetry.TelemetrySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZoneId

class SuperAlarmWidget : GlanceAppWidget() {
  override val sizeMode: SizeMode = SizeMode.Responsive(
    setOf(
      DpSize(140.dp, 64.dp),
      DpSize(140.dp, 140.dp),
      DpSize(300.dp, 140.dp),
    ),
  )

  @Suppress("RestrictedApi")
  override fun getComponents(context: Context): GlanceComponents {
    val defaults = GlanceComponents.getDefault(context)
    return GlanceComponents(
      actionTrampolineActivity = defaults.actionTrampolineActivity,
      invisibleActionTrampolineActivity = ComponentName(
        context,
        ResilientWidgetActionActivity::class.java,
      ),
      actionCallbackBroadcastReceiver = defaults.actionCallbackBroadcastReceiver,
      remoteViewsService = defaults.remoteViewsService,
    )
  }

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val config = AlarmStorage.readAlarmConfig(context)
    val state = SuperAlarmWidgetState(
      time = formatWidgetAlarmTime(config.timeMinutesLocal, DateFormat.is24HourFormat(context)),
      status = formatWidgetAlarmStatus(
        enabled = config.enabled,
        triggerAtMillis = AlarmStorage.getScheduledTriggerAt(context),
        nowMillis = System.currentTimeMillis(),
        zoneId = ZoneId.systemDefault(),
        hasRepeatDays = config.repeatDays.isNotEmpty(),
      ),
      repeat = formatWidgetRepeatDays(config.repeatDays),
      enabled = config.enabled,
    )

    provideContent {
      GlanceTheme {
        SuperAlarmWidgetContent(state)
      }
    }
  }
}

class SuperAlarmWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = SuperAlarmWidget()

  override fun onReceive(context: Context, intent: Intent) {
    super.onReceive(context, intent)
    if (intent.action in TimeChangeActions) {
      SuperAlarmWidgetUpdater.requestUpdate(context)
    }
  }
}

fun requestPinSuperAlarmWidget(context: Context): Boolean {
  val appWidgetManager = AppWidgetManager.getInstance(context)
  if (!appWidgetManager.isRequestPinAppWidgetSupported) return false
  return appWidgetManager.requestPinAppWidget(
    ComponentName(context, SuperAlarmWidgetReceiver::class.java),
    null,
    null,
  )
}

fun hasSuperAlarmWidgets(context: Context): Boolean =
  AppWidgetManager.getInstance(context)
    .getAppWidgetIds(ComponentName(context, SuperAlarmWidgetReceiver::class.java))
    .isNotEmpty()

fun needsHomeScreenPermissionHint(): Boolean =
  Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

fun openHomeScreenPermissionSettings(context: Context) {
  val miuiPermissionIntent = Intent("miui.intent.action.APP_PERM_EDITOR")
    .setClassName(
      "com.miui.securitycenter",
      "com.miui.permcenter.permissions.PermissionsEditorActivity",
    )
    .putExtra("extra_pkgname", context.packageName)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  val appDetailsIntent = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.parse("package:${context.packageName}"),
  ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  val intent = if (miuiPermissionIntent.resolveActivity(context.packageManager) != null) {
    miuiPermissionIntent
  } else {
    appDetailsIntent
  }
  context.startActivity(intent)
}

object SuperAlarmWidgetUpdater {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  fun requestUpdate(context: Context) {
    val applicationContext = context.applicationContext
    scope.launch {
      runCatching { SuperAlarmWidget().updateAll(applicationContext) }
        .onFailure { SuperAlarmTelemetry.captureFailure(TelemetryFailureOperation.WidgetUpdate, it) }
    }
  }
}

class SetSleepDurationAction : ActionCallback {
  override suspend fun onAction(
    context: Context,
    glanceId: GlanceId,
    parameters: ActionParameters,
  ) {
    val durationHours = parameters[DurationHoursKey]?.coerceIn(1, 24) ?: return
    SuperAlarmTelemetry.capture(ProductEvent(
      TelemetryEventName.WidgetActionSelected,
      source = TelemetrySource.Widget,
      count = durationHours,
    ))
    val store = (context.applicationContext as MainApplication).store
    val enabled = store.setSleepDuration(durationHours * 60)
    SuperAlarmWidget().updateAll(context)

    if (!enabled) {
      SuperAlarmTelemetry.capture(ProductEvent(
        TelemetryEventName.WidgetActionFailed,
        source = TelemetrySource.Widget,
        outcome = TelemetryOutcome.Blocked,
        failureCode = "missing_dismissal_target",
      ))
      context.startActivity(
        Intent(context, MainActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      )
    } else {
      SuperAlarmTelemetry.capture(ProductEvent(
        TelemetryEventName.WidgetActionCompleted,
        source = TelemetrySource.Widget,
        outcome = TelemetryOutcome.Success,
        count = durationHours,
      ))
    }
  }
}

private data class SuperAlarmWidgetState(
  val time: String,
  val status: String,
  val repeat: String,
  val enabled: Boolean,
)

private val DurationHoursKey = ActionParameters.Key<Int>("duration-hours")
private val TimeChangeActions = setOf(
  Intent.ACTION_LOCALE_CHANGED,
  Intent.ACTION_TIMEZONE_CHANGED,
  Intent.ACTION_TIME_CHANGED,
)

@Composable
private fun SuperAlarmWidgetContent(state: SuperAlarmWidgetState) {
  val size = LocalSize.current
  when {
    size.width >= 245.dp -> WideWidget(state)
    size.height >= 115.dp -> StandardWidget(state)
    else -> CompactWidget(state)
  }
}

@Composable
private fun CompactWidget(state: SuperAlarmWidgetState) {
  Row(
    modifier = widgetContainer().clickable(actionStartActivity<MainActivity>()),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Image(
      provider = ImageProvider(R.drawable.ic_widget_alarm),
      contentDescription = "Super Alarm",
      modifier = GlanceModifier.size(28.dp),
      colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
    )
    Spacer(GlanceModifier.width(10.dp))
    Column(modifier = GlanceModifier.defaultWeight()) {
      Text(
        text = "Super Alarm",
        style = TextStyle(
          color = GlanceTheme.colors.onSurfaceVariant,
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
      )
      Text(
        text = state.time,
        style = TextStyle(
          color = GlanceTheme.colors.onSurface,
          fontSize = 22.sp,
          fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
      )
    }
    StatusDot(state.enabled)
  }
}

@Composable
private fun StandardWidget(state: SuperAlarmWidgetState) {
  Column(
    modifier = widgetContainer().clickable(actionStartActivity<MainActivity>()),
    verticalAlignment = Alignment.Vertical.CenterVertically,
  ) {
    Header()
    Spacer(GlanceModifier.height(8.dp))
    Text(
      text = state.time,
      style = TextStyle(
        color = GlanceTheme.colors.onSurface,
        fontSize = 30.sp,
        fontWeight = FontWeight.Medium,
      ),
      maxLines = 1,
    )
    Spacer(GlanceModifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      StatusDot(state.enabled)
      Spacer(GlanceModifier.width(6.dp))
      Text(
        text = state.status,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
        maxLines = 1,
      )
    }
    Spacer(GlanceModifier.height(6.dp))
    Text(
      text = state.repeat,
      style = TextStyle(
        color = GlanceTheme.colors.onSurface,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
      ),
      maxLines = 1,
    )
  }
}

@Composable
private fun WideWidget(state: SuperAlarmWidgetState) {
  Row(
    modifier = widgetContainer(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(
      modifier = GlanceModifier.defaultWeight().fillMaxHeight()
        .clickable(actionStartActivity<MainActivity>()),
      verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
          provider = ImageProvider(R.drawable.ic_widget_alarm),
          contentDescription = "Super Alarm",
          modifier = GlanceModifier.size(20.dp),
          colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
        )
        Spacer(GlanceModifier.width(6.dp))
        Text(
          text = "Super Alarm",
          style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
          ),
          maxLines = 1,
        )
      }
      Spacer(GlanceModifier.height(2.dp))
      Text(
        text = state.time,
        style = TextStyle(
          color = GlanceTheme.colors.onSurface,
          fontSize = 28.sp,
          fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
      )
      Spacer(GlanceModifier.height(2.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(state.enabled)
        Spacer(GlanceModifier.width(6.dp))
        Text(
          text = "${state.status} · ${state.repeat}",
          style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
          maxLines = 1,
        )
      }
    }
    Spacer(GlanceModifier.width(12.dp))
    Column(horizontalAlignment = Alignment.Horizontal.End) {
      Text(
        text = "Sleep for",
        style = TextStyle(
          color = GlanceTheme.colors.onSurfaceVariant,
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium,
        ),
      )
      Spacer(GlanceModifier.height(6.dp))
      Row {
        SleepPreset(5)
        Spacer(GlanceModifier.width(6.dp))
        SleepPreset(6)
        Spacer(GlanceModifier.width(6.dp))
        SleepPreset(8)
      }
    }
  }
}

@Composable
private fun Header() {
  Row(
    modifier = GlanceModifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Image(
      provider = ImageProvider(R.drawable.ic_widget_alarm),
      contentDescription = "Super Alarm",
      modifier = GlanceModifier.size(24.dp),
      colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
    )
    Spacer(GlanceModifier.width(8.dp))
    Text(
      text = "Super Alarm",
      modifier = GlanceModifier.defaultWeight(),
      style = TextStyle(
        color = GlanceTheme.colors.onSurface,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
      ),
      maxLines = 1,
    )
  }
}

@Composable
private fun StatusDot(enabled: Boolean) {
  Box(
    modifier = GlanceModifier
      .size(10.dp)
      .cornerRadius(5.dp)
      .background(
        if (enabled) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
      ),
  ) {}
}

@Composable
private fun SleepPreset(hours: Int) {
  Box(
    modifier = GlanceModifier
      .width(48.dp)
      .height(48.dp)
      .cornerRadius(16.dp)
      .background(GlanceTheme.colors.secondaryContainer)
      .clickable(
        actionRunCallback<SetSleepDurationAction>(
          parameters = actionParametersOf(DurationHoursKey to hours),
        ),
      ),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = "${hours}h",
      style = TextStyle(
        color = GlanceTheme.colors.onSecondaryContainer,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
      ),
    )
  }
}

@Composable
private fun widgetContainer(): GlanceModifier =
  GlanceModifier
    .fillMaxSize()
    .cornerRadius(R.dimen.super_alarm_widget_corner_radius)
    .background(GlanceTheme.colors.widgetBackground)
    .appWidgetBackground()
    .padding(12.dp)
