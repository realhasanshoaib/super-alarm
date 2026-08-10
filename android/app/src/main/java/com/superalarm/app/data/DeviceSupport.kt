package com.superalarm.app.data

import java.util.Locale

data class DeviceSettingsComponent(
  val packageName: String,
  val className: String,
)

data class DeviceSupport(
  val label: String,
  val instruction: String,
  val settingsComponents: List<DeviceSettingsComponent>,
)

fun normalizeManufacturer(manufacturer: String?): String =
  manufacturer
    .orEmpty()
    .trim()
    .lowercase(Locale.ROOT)
    .filter(Char::isLetterOrDigit)

fun deviceSupportForManufacturer(manufacturer: String?): DeviceSupport? =
  when (normalizeManufacturer(manufacturer)) {
    "vivo", "iqoo", "vivoiqoo" -> VIVO_IQOO_SUPPORT
    "xiaomi", "redmi", "poco", "xiaomiredmi", "xiaomipoco" -> XIAOMI_SUPPORT
    "oppo", "realme", "opporealme" -> OPPO_REALME_SUPPORT
    "oneplus" -> ONEPLUS_SUPPORT
    "samsung" -> SAMSUNG_SUPPORT
    "huawei" -> HUAWEI_SUPPORT
    "honor" -> HONOR_SUPPORT
    "asus" -> ASUS_SUPPORT
    "meizu" -> MEIZU_SUPPORT
    else -> null
  }

private val VIVO_IQOO_SUPPORT = DeviceSupport(
  label = "Vivo / iQOO",
  instruction = "Allow Autostart, high background power use, lock-screen display, and background pop-ups.",
  settingsComponents = listOf(
    DeviceSettingsComponent(
      packageName = "com.vivo.permissionmanager",
      className = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
    ),
    DeviceSettingsComponent(
      packageName = "com.iqoo.secure",
      className = "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
    ),
    DeviceSettingsComponent(
      packageName = "com.vivo.permissionmanager",
      className = "com.vivo.permissionmanager.activity.PurviewTabActivity",
    ),
  ),
)

private val XIAOMI_SUPPORT = DeviceSupport(
  label = "Xiaomi / Redmi / POCO",
  instruction = "Turn on Background autostart and set battery use to No restrictions.",
  settingsComponents = listOf(
    DeviceSettingsComponent(
      packageName = "com.miui.securitycenter",
      className = "com.miui.permcenter.autostart.AutoStartManagementActivity",
    ),
    DeviceSettingsComponent(
      packageName = "com.miui.powerkeeper",
      className = "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
    ),
  ),
)

private val OPPO_REALME_SUPPORT = DeviceSupport(
  label = "OPPO / Realme",
  instruction = "Allow Auto-launch and background activity, then disable sleep standby for Super Alarm.",
  settingsComponents = listOf(
    DeviceSettingsComponent(
      packageName = "com.oplus.safecenter",
      className = "com.oplus.safecenter.startupapp.StartupAppListActivity",
    ),
    DeviceSettingsComponent(
      packageName = "com.coloros.safecenter",
      className = "com.coloros.safecenter.permission.startup.StartupAppListActivity",
    ),
  ),
)

private val ONEPLUS_SUPPORT = DeviceSupport(
  label = "OnePlus",
  instruction = "Allow Auto-launch and foreground activity, then disable sleep standby for Super Alarm.",
  settingsComponents = listOf(
    DeviceSettingsComponent(
      packageName = "com.oplus.safecenter",
      className = "com.oplus.safecenter.startupapp.StartupAppListActivity",
    ),
    DeviceSettingsComponent(
      packageName = "com.oneplus.security",
      className = "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
    ),
    DeviceSettingsComponent(
      packageName = "com.android.settings",
      className = "com.android.settings.Settings\$HighPowerApplicationsActivity",
    ),
  ),
)

private val SAMSUNG_SUPPORT = DeviceSupport(
  label = "Samsung",
  instruction = "Remove Super Alarm from Sleeping apps and add it to Never sleeping apps.",
  settingsComponents = listOf(
    DeviceSettingsComponent(
      packageName = "com.samsung.android.sm",
      className = "com.samsung.android.sm.ui.battery.BatteryActivity",
    ),
    DeviceSettingsComponent(
      packageName = "com.samsung.android.lool",
      className = "com.samsung.android.sm.ui.battery.BatteryActivity",
    ),
  ),
)

private val HUAWEI_SUPPORT = DeviceSupport(
  label = "Huawei",
  instruction = "Set Super Alarm to manage manually and allow auto-launch and background activity.",
  settingsComponents = listOf(
    DeviceSettingsComponent(
      packageName = "com.huawei.systemmanager",
      className = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    ),
    DeviceSettingsComponent(
      packageName = "com.huawei.systemmanager",
      className = "com.huawei.systemmanager.optimize.process.ProtectActivity",
    ),
  ),
)

private val HONOR_SUPPORT = DeviceSupport(
  label = "Honor",
  instruction = "Set Super Alarm to manage manually and allow auto-launch and background activity.",
  settingsComponents = listOf(
    DeviceSettingsComponent(
      packageName = "com.hihonor.systemmanager",
      className = "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    ),
    DeviceSettingsComponent(
      packageName = "com.huawei.systemmanager",
      className = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    ),
  ),
)

private val ASUS_SUPPORT = DeviceSupport(
  label = "ASUS",
  instruction = "Allow Super Alarm to start automatically and stay active in the background.",
  settingsComponents = listOf(
    DeviceSettingsComponent(
      packageName = "com.asus.mobilemanager",
      className = "com.asus.mobilemanager.MainActivity",
    ),
    DeviceSettingsComponent(
      packageName = "com.asus.mobilemanager",
      className = "com.asus.mobilemanager.entry.FunctionActivity",
    ),
  ),
)

private val MEIZU_SUPPORT = DeviceSupport(
  label = "Meizu",
  instruction = "Allow Super Alarm to auto-start and run in the background.",
  settingsComponents = listOf(
    DeviceSettingsComponent(
      packageName = "com.meizu.safe",
      className = "com.meizu.safe.permission.PermissionMainActivity",
    ),
    DeviceSettingsComponent(
      packageName = "com.meizu.safe",
      className = "com.meizu.safe.permission.SmartBGActivity",
    ),
  ),
)
