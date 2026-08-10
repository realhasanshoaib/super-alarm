package com.superalarm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSupportTest {
  @Test
  fun normalizesManufacturerAliases() {
    assertEquals("iqoo", normalizeManufacturer(" iQOO "))
    assertEquals("xiaomiredmi", normalizeManufacturer("Xiaomi / Redmi"))
    assertEquals("opporealme", normalizeManufacturer("OPPO-Realme"))
  }

  @Test
  fun mapsEverySupportedManufacturerFamily() {
    val manufacturers = listOf(
      "vivo",
      "iQOO",
      "Xiaomi",
      "Redmi",
      "POCO",
      "OPPO",
      "realme",
      "OnePlus",
      "Samsung",
      "Huawei",
      "Honor",
      "ASUS",
      "Meizu",
    )

    manufacturers.forEach { manufacturer ->
      val support = deviceSupportForManufacturer(manufacturer)
      assertNotNull(manufacturer, support)
      assertTrue(support!!.instruction.isNotBlank())
      assertTrue(support.settingsComponents.isNotEmpty())
    }
  }

  @Test
  fun preservesOrderedFallbackCandidates() {
    val support = requireNotNull(deviceSupportForManufacturer("xiaomi"))

    assertEquals("com.miui.securitycenter", support.settingsComponents.first().packageName)
    assertTrue(support.settingsComponents.first().className.contains("AutoStart"))
    assertTrue(support.settingsComponents.size > 1)
  }

  @Test
  fun unknownManufacturerHasNoOemCandidatesAndUsesAppFallback() {
    assertNull(deviceSupportForManufacturer("Google"))
    assertNull(deviceSupportForManufacturer(null))
  }
}
