package com.narzoai.assistant

import android.content.Context
import android.content.pm.PackageManager
import com.google.mlkit.vision.objects.DetectedObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class CameraAITest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockPackageManager: PackageManager

    private lateinit var cameraAI: CameraAI

    @Before
    fun setUp() {
        `when`(mockContext.packageManager).thenReturn(mockPackageManager)
        cameraAI = CameraAI(mockContext)
    }

    @Test
    fun `hasCamera should check package manager`() {
        `when`(mockPackageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)).thenReturn(true)
        assertTrue(cameraAI.hasCamera())
    }

    @Test
    fun `hasCamera should return false when no camera`() {
        `when`(mockPackageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)).thenReturn(false)
        assertFalse(cameraAI.hasCamera())
    }

    @Test
    fun `getCurrentMode should default to OBJECT_DETECTION`() {
        assertEquals(CameraAI.DetectionMode.OBJECT_DETECTION, cameraAI.getCurrentMode())
    }

    @Test
    fun `setDetectionMode should update mode`() {
        cameraAI.setDetectionMode(CameraAI.DetectionMode.TEXT_RECOGNITION)
        assertEquals(CameraAI.DetectionMode.TEXT_RECOGNITION, cameraAI.getCurrentMode())
    }

    @Test
    fun `setDetectionMode to DISABLED should update mode`() {
        cameraAI.setDetectionMode(CameraAI.DetectionMode.DISABLED)
        assertEquals(CameraAI.DetectionMode.DISABLED, cameraAI.getCurrentMode())
    }

    @Test
    fun `describeDetectedObjects should return no objects for empty list`() {
        val result = cameraAI.describeDetectedObjects(emptyList())
        assertEquals("No objects detected", result)
    }

    @Test
    fun `release should not throw`() {
        cameraAI.release()
    }

    @Test
    fun `stopCamera should not throw`() {
        cameraAI.stopCamera()
    }

    @Test
    fun `setDetectionCallbacks should not throw`() {
        cameraAI.setDetectionCallbacks(
            onObject = { _, _ -> },
            onText = { _, _ -> },
            onError = { _ -> }
        )
    }
}
