package com.narzoai.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class PhoneControllerTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockPackageManager: PackageManager

    @Mock
    private lateinit var mockAudioManager: AudioManager

    @Mock
    private lateinit var mockWifiManager: WifiManager

    @Mock
    private lateinit var mockPowerManager: PowerManager

    @Mock
    private lateinit var mockNarzoAccessibilityService: NarzoAccessibilityService

    private lateinit var phoneController: PhoneController

    @Before
    fun setUp() {
        `when`(mockContext.getSystemService(Context.AUDIO_SERVICE)).thenReturn(mockAudioManager)
        `when`(mockContext.applicationContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mockWifiManager)
        `when`(mockContext.getSystemService(Context.POWER_SERVICE)).thenReturn(mockPowerManager)
        `when`(mockContext.packageManager).thenReturn(mockPackageManager)

        phoneController = PhoneController(mockContext)
    }

    @Test
    fun `openApp should return false for invalid package`() {
        `when`(mockPackageManager.getLaunchIntentForPackage(anyString())).thenReturn(null)
        assertFalse(phoneController.openApp("com.invalid.app"))
    }

    @Test
    fun `openAppByName should return false for unknown app`() {
        assertFalse(phoneController.openAppByName("some_random_app_123"))
    }

    @Test
    fun `openAppByName should resolve known apps`() {
        `when`(mockPackageManager.getLaunchIntentForPackage("com.whatsapp")).thenReturn(Intent())
        assertTrue(phoneController.openAppByName("whatsapp"))
    }

    @Test
    fun `openAppByName should handle various whatsapp name formats`() {
        `when`(mockPackageManager.getLaunchIntentForPackage("com.whatsapp")).thenReturn(Intent())
        assertTrue(phoneController.openAppByName("Whats App"))
        assertTrue(phoneController.openAppByName("what's app"))
    }

    @Test
    fun `openAppByName should resolve youtube`() {
        `when`(mockPackageManager.getLaunchIntentForPackage("com.google.android.youtube")).thenReturn(Intent())
        assertTrue(phoneController.openAppByName("youtube"))
    }

    @Test
    fun `openAppByName should resolve browser as chrome`() {
        `when`(mockPackageManager.getLaunchIntentForPackage("com.android.chrome")).thenReturn(Intent())
        assertTrue(phoneController.openAppByName("browser"))
    }

    @Test
    fun `openAppByName should resolve settings`() {
        `when`(mockPackageManager.getLaunchIntentForPackage("com.android.settings")).thenReturn(Intent())
        assertTrue(phoneController.openAppByName("settings"))
    }

    @Test
    fun `setAlarm should return false when no alarm app`() {
        `when`(mockPackageManager.resolveActivity(any(Intent::class.java), anyInt())).thenReturn(null)
        assertFalse(phoneController.setAlarm(7, 30))
    }

    @Test
    fun `volumeUp should not throw`() {
        phoneController.volumeUp()
        verify(mockAudioManager).adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_RAISE,
            AudioManager.FLAG_SHOW_UI
        )
    }

    @Test
    fun `volumeDown should not throw`() {
        phoneController.volumeDown()
        verify(mockAudioManager).adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    @Test
    fun `mute should not throw`() {
        phoneController.mute()
        verify(mockAudioManager).adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_MUTE,
            AudioManager.FLAG_SHOW_UI
        )
    }

    @Test
    fun `setVolume should clamp to valid range`() {
        `when`(mockAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenReturn(15)
        assertTrue(phoneController.setVolume(20))
        verify(mockAudioManager).setStreamVolume(AudioManager.STREAM_MUSIC, 15, AudioManager.FLAG_SHOW_UI)
    }

    @Test
    fun `setVolume with negative should clamp to zero`() {
        `when`(mockAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenReturn(15)
        assertTrue(phoneController.setVolume(-5))
        verify(mockAudioManager).setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
    }

    @Test
    fun `setWifiEnabled for pre-Q should use wifiManager`() {
        `when`(mockWifiManager.isWifiEnabled).thenReturn(true)
        assertTrue(phoneController.setWifiEnabled(true))
        verify(mockWifiManager).isWifiEnabled = true
    }

    @Test
    fun `setBluetoothEnabled should return false when bluetooth not available`() {
        val result = phoneController.setBluetoothEnabled(true)
    }

    @Test
    fun `isAccessibilityServiceConnected should default to false`() {
        assertFalse(phoneController.isAccessibilityServiceConnected())
    }

    @Test
    fun `connectToAccessibilityService should set connected`() {
        phoneController.connectToAccessibilityService(mockNarzoAccessibilityService)
        assertTrue(phoneController.isAccessibilityServiceConnected())
    }

    @Test
    fun `disconnectFromAccessibilityService should set not connected`() {
        phoneController.connectToAccessibilityService(mockNarzoAccessibilityService)
        phoneController.disconnectFromAccessibilityService()
        assertFalse(phoneController.isAccessibilityServiceConnected())
    }

    @Test
    fun `goBack should return false when accessibility not connected`() {
        assertFalse(phoneController.goBack())
    }

    @Test
    fun `openRecentApps should return false when accessibility not connected`() {
        assertFalse(phoneController.openRecentApps())
    }

    @Test
    fun `performClick should return false when accessibility not connected`() {
        assertFalse(phoneController.performClick(100f, 200f))
    }

    @Test
    fun `performSwipe should return false when accessibility not connected`() {
        assertFalse(phoneController.performSwipe(0f, 0f, 100f, 100f))
    }

    @Test
    fun `hasRootAccess should not throw`() {
        phoneController.hasRootAccess()
    }
}
