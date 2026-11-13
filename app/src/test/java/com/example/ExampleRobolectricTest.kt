package com.example

import android.app.Application
import android.content.Context
import android.util.Size
import android.view.Surface
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.camera.CameraManager
import com.example.data.FolderRepository
import com.example.data.MediaStorageManager
import com.example.model.MediaFolder
import com.example.model.MediaType
import com.example.model.RecorderMode
import com.example.ui.FolderCamViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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
        assertEquals(4000, CameraManager.DEFAULT_PHOTO_12MP_SIZE.width)
        assertEquals(3000, CameraManager.DEFAULT_PHOTO_12MP_SIZE.height)
        assertEquals(30, CameraManager.DEFAULT_VIDEO_30FPS_RANGE.lower)
        assertEquals(30, CameraManager.DEFAULT_VIDEO_30FPS_RANGE.upper)
    }

    @Test
    fun `cross-media paths Audio in DCIM Camera and Photo in Recordings save to exact folder`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val storage = MediaStorageManager(context)

            // MediaStore compatibility check must NOT claim cross-media directories are MediaStore-compatible
            assertFalse(storage.isMediaStoreCompatible(MediaType.AUDIO, "DCIM/Camera"))
            assertFalse(storage.isMediaStoreCompatible(MediaType.PHOTO, "Recordings/Trip"))
            assertTrue(storage.isMediaStoreCompatible(MediaType.PHOTO, "DCIM/Camera"))
            assertTrue(storage.isMediaStoreCompatible(MediaType.AUDIO, "Recordings/Voice"))

            // Audio in /sdcard/DCIM/Camera
            val dcimCameraFolder = MediaFolder(
                id = "dcim_cam",
                name = "CameraAudio",
                sdcardPath = "/sdcard/DCIM/Camera"
            )
            val audioFile = storage.prepareOutputFile(dcimCameraFolder, MediaType.AUDIO)
            audioFile.parentFile?.mkdirs()
            audioFile.writeBytes(byteArrayOf(1, 2, 3, 4))
            val audioResult = storage.finalizeCapturedFile(dcimCameraFolder, audioFile, MediaType.AUDIO)
            assertTrue(audioResult.savedToPublicSdcard)
            assertEquals("/sdcard/DCIM/Camera/${audioFile.name}", audioResult.destinationPath)
            assertTrue(audioResult.finalFile.exists())
            audioResult.finalFile.delete()

            // Photo in /sdcard/Recordings/Notes
            val recordingsFolder = MediaFolder(
                id = "rec_photos",
                name = "RecordingsPhotos",
                sdcardPath = "/sdcard/Recordings/Notes"
            )
            val photoFile = storage.prepareOutputFile(recordingsFolder, MediaType.PHOTO)
            photoFile.parentFile?.mkdirs()
            photoFile.writeBytes(byteArrayOf(5, 6, 7, 8))
            val photoResult = storage.finalizeCapturedFile(recordingsFolder, photoFile, MediaType.PHOTO)
            assertTrue(photoResult.savedToPublicSdcard)
            assertEquals("/sdcard/Recordings/Notes/${photoFile.name}", photoResult.destinationPath)
            assertTrue(photoResult.finalFile.exists())
            photoResult.finalFile.delete()
        }
    }

    @Test
    fun `camera manager handles host stop resume and audio switching without lifecycle crash`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cameraManager = CameraManager(context)

        assertEquals(Lifecycle.State.CREATED, cameraManager.backgroundLifecycleOwner.lifecycle.currentState)

        // Simulate host stop/resume cycles without active PreviewView or Video recording
        cameraManager.onHostStop()
        assertEquals(Lifecycle.State.CREATED, cameraManager.backgroundLifecycleOwner.lifecycle.currentState)

        cameraManager.onHostResume(previewView = null, mode = RecorderMode.PHOTO)
        cameraManager.onHostResume(previewView = null, mode = RecorderMode.AUDIO)
        cameraManager.unbindCameraIfIdle()
        assertFalse(cameraManager.isRecordingVideo())

        // Release and verify subsequent stop/resume calls don't crash on DESTROYED LifecycleRegistry
        cameraManager.release()
        assertEquals(Lifecycle.State.DESTROYED, cameraManager.backgroundLifecycleOwner.lifecycle.currentState)
        cameraManager.onHostStop()
        cameraManager.unbindCameraIfIdle()
    }

    @Test
    fun `fast burst photo staging generates unique collision-free filenames`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MediaStorageManager(context)
        val folder = MediaFolder(
            id = "burst_test",
            name = "BurstFolder",
            sdcardPath = "/sdcard/DCIM/BurstTest"
        )

        val generatedNames = (1..25).map {
            storage.prepareFastPhotoFile(folder).name
        }.toSet()

        assertEquals(25, generatedNames.size)
        assertTrue(generatedNames.all { it.startsWith("IMG_") && it.endsWith(".jpg") })
    }

    @Test
    fun `photo feedback tracks multi-shot async captures and settles cleanly`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FolderCamViewModel(app)
        val folder = viewModel.addFolder("BurstCam", "/sdcard/DCIM/BurstCam")

        val staged1 = viewModel.mediaStorageManager.prepareFastPhotoFile(folder)
        val staged2 = viewModel.mediaStorageManager.prepareFastPhotoFile(folder)
        staged1.parentFile?.mkdirs()
        staged1.writeBytes(byteArrayOf(10, 20, 30))
        staged2.writeBytes(byteArrayOf(40, 50, 60))

        viewModel.onPhotoCaptured(staged1)
        viewModel.onPhotoCaptured(staged2)

        // Wait up to 3s for IO coroutine to finalize both async captures
        val deadlineMs = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadlineMs) {
            val current = viewModel.photoFeedback.value
            if (current.savedCounter == 2 && current.inFlightCounter == 0 && current.summaryMessage != null) {
                break
            }
            Thread.sleep(25)
        }

        val feedback = viewModel.photoFeedback.value
        assertEquals(2, feedback.savedCounter)
        assertEquals(0, feedback.inFlightCounter)
        assertTrue(feedback.isVisible)
        assertEquals("Saved 2 photos to /sdcard/DCIM/BurstCam", feedback.summaryMessage)

        // Clean up created test files
        File("/sdcard/DCIM/BurstCam/${staged1.name}").delete()
        File("/sdcard/DCIM/BurstCam/${staged2.name}").delete()
        staged1.delete()
        staged2.delete()
    }

    @Test
    fun `12MP resolution ranking prefers 12MP over 3MP 1920x1440 and oversized 50MP`() {
        val deviceSizes = listOf(
            Size(1920, 1440),  // ~2.8MP (3MP)
            Size(8160, 6120),  // ~50MP
            Size(4032, 3024),  // ~12.2MP (4:3)
            Size(4000, 2250),  // ~9MP (16:9)
            Size(1280, 960)    // ~1.2MP
        )

        val ranked = CameraManager.rankPhotoSizesFor12Mp(deviceSizes)
        assertEquals(Size(4032, 3024), ranked.first())
    }

    @Test
    fun `autorotation maps physical sensor angles to Surface rotations`() {
        assertEquals(Surface.ROTATION_0, CameraManager.mapSensorOrientationToSurfaceRotation(0))
        assertEquals(Surface.ROTATION_0, CameraManager.mapSensorOrientationToSurfaceRotation(350))
        assertEquals(Surface.ROTATION_270, CameraManager.mapSensorOrientationToSurfaceRotation(90))
        assertEquals(Surface.ROTATION_180, CameraManager.mapSensorOrientationToSurfaceRotation(180))
        assertEquals(Surface.ROTATION_90, CameraManager.mapSensorOrientationToSurfaceRotation(270))
    }
}
