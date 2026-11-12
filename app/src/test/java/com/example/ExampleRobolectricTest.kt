package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.FolderRepository
import com.example.model.RecorderMode
import com.example.ui.FolderCamViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("FolderCam", appName)
    }

    @Test
    fun `folder repository starts empty and saves and restores foldercam json`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("folder_cam_v2_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        FolderRepository(context).getFallbackConfigFile().delete()
        FolderRepository(context).getSdcardConfigFile().delete()

        val repo = FolderRepository(context)
        assertTrue("By default no folders should exist", repo.folders.value.isEmpty())
        assertNull(repo.getSelectedFolder())
        assertTrue(repo.isDarkTheme.value)

        // Add folder and toggle theme to Light mode
        val custom = repo.addFolder(
            name = "FieldRecordings",
            sdcardPath = "/sdcard/Music/FieldRecordings"
        )
        repo.setDarkTheme(false)

        assertEquals("FieldRecordings", custom.name)
        assertEquals("/sdcard/Music/FieldRecordings", custom.sdcardPath)
        assertEquals(custom.id, repo.selectedFolderId.value)
        assertFalse(repo.isDarkTheme.value)

        // Export JSON and verify it can be restored into a fresh state
        val exportedJson = repo.toJsonString()
        assertTrue(exportedJson.contains("/sdcard/.foldercam"))
        assertTrue(exportedJson.contains("FieldRecordings"))

        repo.deleteFolder(custom.id)
        repo.setDarkTheme(true)
        assertTrue(repo.folders.value.isEmpty())

        // Restore from JSON
        assertTrue(repo.restoreFromJsonString(exportedJson))
        assertEquals(1, repo.folders.value.size)
        assertEquals("FieldRecordings", repo.folders.value.first().name)
        assertEquals("/sdcard/Music/FieldRecordings", repo.folders.value.first().sdcardPath)
        assertFalse(repo.isDarkTheme.value)
    }

    @Test
    fun `viewmodel defaults to Audio mode first then Photo then Video`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FolderCamViewModel(app)

        assertEquals(RecorderMode.AUDIO, viewModel.recorderMode.value)
        assertEquals(0, RecorderMode.AUDIO.index)
        assertEquals(1, RecorderMode.PHOTO.index)
        assertEquals(2, RecorderMode.VIDEO.index)

        viewModel.setRecorderMode(RecorderMode.PHOTO)
        assertEquals(RecorderMode.PHOTO, viewModel.recorderMode.value)

        viewModel.setRecorderMode(RecorderMode.VIDEO)
        assertEquals(RecorderMode.VIDEO, viewModel.recorderMode.value)

        // Verify default 12MP photo and 4K 30fps video configuration
        val camState = viewModel.cameraManager.cameraState.value
        assertEquals("12MP", camState.photoResolution)
        assertEquals("4K 30fps", camState.videoResolution)
        assertEquals(4000, com.example.camera.CameraManager.DEFAULT_PHOTO_12MP_SIZE.width)
        assertEquals(3000, com.example.camera.CameraManager.DEFAULT_PHOTO_12MP_SIZE.height)
        assertEquals(30, com.example.camera.CameraManager.DEFAULT_VIDEO_30FPS_RANGE.lower)
        assertEquals(30, com.example.camera.CameraManager.DEFAULT_VIDEO_30FPS_RANGE.upper)
    }
}
