package com.example

import android.app.Application
import android.content.Context
import android.provider.Settings
import android.util.Size
import android.view.Surface
import androidx.camera.core.ImageCapture
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.camera.CameraManager
import com.example.camera.LensOption
import com.example.data.FolderRepository
import com.example.data.MediaStorageManager
import com.example.model.CaptureOrientation
import com.example.model.MediaFolder
import com.example.model.MediaType
import com.example.model.RecorderMode
import com.example.model.VideoLensPreference
import com.example.service.BurstBufferManager
import com.example.service.RecordingCoordinator
import com.example.service.RecordingForegroundService
import com.example.system.DndController
import com.example.ui.FolderCamViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Before
    fun setUp() {
        RecordingCoordinator.resetForTests()
    }

    @After
    fun tearDown() {
        RecordingCoordinator.resetForTests()
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("FolderCam", appName)
    }

    @Test
    fun `folder repository initializes mandatory inbox and saves and restores foldercam json`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("folder_cam_v2_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        FolderRepository(context).getFallbackConfigFile().delete()
        FolderRepository(context).getSdcardConfigFile().delete()

        val repo = FolderRepository(context)
        assertEquals(1, repo.folders.value.size)
        val inbox = repo.getInboxFolder()
        assertEquals(MediaFolder.INBOX_FOLDER_ID, inbox.id)
        assertEquals(MediaFolder.INBOX_DEFAULT_NAME, inbox.name)
        assertEquals(MediaFolder.INBOX_DEFAULT_PATH, inbox.sdcardPath)
        assertTrue(inbox.isInbox)
        assertTrue(inbox.enableAudio && inbox.enablePhoto && inbox.enableVideo)
        assertEquals(inbox.id, repo.getSelectedFolder()?.id)
        assertTrue(repo.isDarkTheme.value)

        // Add custom folder and toggle theme to Light mode
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
        assertTrue(exportedJson.contains(MediaFolder.INBOX_DEFAULT_PATH))

        // Custom folder can be deleted, but mandatory Inbox cannot
        assertTrue(repo.deleteFolder(custom.id))
        assertFalse(repo.deleteFolder(MediaFolder.INBOX_FOLDER_ID))
        repo.setDarkTheme(true)
        assertEquals(listOf(MediaFolder.INBOX_FOLDER_ID), repo.folders.value.map { it.id })

        // Restore from JSON
        assertTrue(repo.restoreFromJsonString(exportedJson))
        assertEquals(2, repo.folders.value.size)
        assertEquals("FieldRecordings", repo.folders.value.last().name)
        assertEquals("/sdcard/Music/FieldRecordings", repo.folders.value.last().sdcardPath)
        assertFalse(repo.isDarkTheme.value)

        repo.getFallbackConfigFile().delete()
        repo.getSdcardConfigFile().delete()
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

    @Test
    fun `moveFolder reorders folders preserves active selection and persists to foldercam json`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("folder_cam_v2_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        FolderRepository(context).getFallbackConfigFile().delete()
        FolderRepository(context).getSdcardConfigFile().delete()

        val repo = FolderRepository(context)
        val alpha = repo.addFolder("Alpha", "/sdcard/DCIM/Alpha")
        val beta = repo.addFolder("Beta", "/sdcard/DCIM/Beta")
        val gamma = repo.addFolder("Gamma", "/sdcard/DCIM/Gamma")
        repo.selectFolder(beta.id)

        assertEquals(listOf("Inbox", "Alpha", "Beta", "Gamma"), repo.folders.value.map { it.name })
        assertTrue(repo.moveFolder(3, 1))
        assertEquals(listOf("Inbox", "Gamma", "Alpha", "Beta"), repo.folders.value.map { it.name })
        assertEquals(beta.id, repo.selectedFolderId.value)

        // Verify persisted order survives restore/reload
        val exported = repo.toJsonString()
        val repoReloaded = FolderRepository(context)
        assertTrue(repoReloaded.restoreFromJsonString(exported))
        assertEquals(listOf("Inbox", "Gamma", "Alpha", "Beta"), repoReloaded.folders.value.map { it.name })
        assertEquals(beta.id, repoReloaded.selectedFolderId.value)
        assertFalse(repo.moveFolder(-1, 0))
        assertFalse(repo.moveFolder(1, 1))

        // Clean up
        repo.getFallbackConfigFile().delete()
        repo.getSdcardConfigFile().delete()
    }

    @Test
    fun `capture orientation maps storage keys and forced surface rotations`() {
        assertEquals(CaptureOrientation.AUTO, CaptureOrientation.fromStorageKey(null))
        assertEquals(CaptureOrientation.AUTO, CaptureOrientation.fromStorageKey("unknown"))
        assertEquals(CaptureOrientation.PORTRAIT, CaptureOrientation.fromStorageKey("portrait"))
        assertEquals(CaptureOrientation.LANDSCAPE_LEFT, CaptureOrientation.fromStorageKey("LEFT"))
        assertEquals(CaptureOrientation.PORTRAIT_UPSIDE_DOWN, CaptureOrientation.fromStorageKey("flip"))
        assertEquals(CaptureOrientation.LANDSCAPE_RIGHT, CaptureOrientation.fromStorageKey("Right"))

        assertNull(CaptureOrientation.AUTO.surfaceRotation)
        assertEquals(Surface.ROTATION_0, CaptureOrientation.PORTRAIT.surfaceRotation)
        assertEquals(Surface.ROTATION_90, CaptureOrientation.LANDSCAPE_LEFT.surfaceRotation)
        assertEquals(Surface.ROTATION_180, CaptureOrientation.PORTRAIT_UPSIDE_DOWN.surfaceRotation)
        assertEquals(Surface.ROTATION_270, CaptureOrientation.LANDSCAPE_RIGHT.surfaceRotation)
    }

    @Test
    fun `camera manager respects fixed capture orientation and returns to auto tracking`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cameraManager = CameraManager(context)

        assertEquals(CaptureOrientation.AUTO, cameraManager.cameraState.value.captureOrientation)
        assertEquals(Surface.ROTATION_0, cameraManager.resolveCurrentRotation(null))

        cameraManager.setCaptureOrientation(CaptureOrientation.LANDSCAPE_LEFT)
        assertEquals(CaptureOrientation.LANDSCAPE_LEFT, cameraManager.cameraState.value.captureOrientation)
        assertEquals(Surface.ROTATION_90, cameraManager.resolveCurrentRotation(null))

        cameraManager.setCaptureOrientation(CaptureOrientation.PORTRAIT_UPSIDE_DOWN)
        assertEquals(Surface.ROTATION_180, cameraManager.resolveCurrentRotation(null))

        cameraManager.setCaptureOrientation(CaptureOrientation.LANDSCAPE_RIGHT)
        assertEquals(Surface.ROTATION_270, cameraManager.resolveCurrentRotation(null))

        cameraManager.setCaptureOrientation(CaptureOrientation.PORTRAIT)
        assertEquals(Surface.ROTATION_0, cameraManager.resolveCurrentRotation(null))

        cameraManager.setCaptureOrientation(CaptureOrientation.AUTO)
        assertEquals(CaptureOrientation.AUTO, cameraManager.cameraState.value.captureOrientation)
        assertEquals(Surface.ROTATION_0, cameraManager.resolveCurrentRotation(null))

        cameraManager.release()
    }

    @Test
    fun `capture orientation persists in foldercam json and defaults cleanly for legacy json`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("folder_cam_v2_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        FolderRepository(context).getFallbackConfigFile().delete()
        FolderRepository(context).getSdcardConfigFile().delete()

        val repo = FolderRepository(context)
        assertEquals(CaptureOrientation.AUTO, repo.captureOrientation.value)

        repo.setCaptureOrientation(CaptureOrientation.LANDSCAPE_RIGHT)
        assertEquals(CaptureOrientation.LANDSCAPE_RIGHT, repo.captureOrientation.value)

        val jsonWithOrientation = repo.toJsonString()
        assertTrue(jsonWithOrientation.contains("\"captureOrientation\""))
        assertTrue(jsonWithOrientation.contains("\"RIGHT\""))

        // Reload into a fresh repository instance from exported JSON
        repo.setCaptureOrientation(CaptureOrientation.AUTO)
        assertEquals(CaptureOrientation.AUTO, repo.captureOrientation.value)
        assertTrue(repo.restoreFromJsonString(jsonWithOrientation))
        assertEquals(CaptureOrientation.LANDSCAPE_RIGHT, repo.captureOrientation.value)

        // Legacy JSON missing captureOrientation defaults back to AUTO
        val legacyJson = """{"folders":[],"selectedFolderId":null,"isDarkTheme":true}"""
        assertTrue(repo.restoreFromJsonString(legacyJson))
        assertEquals(CaptureOrientation.AUTO, repo.captureOrientation.value)

        repo.getFallbackConfigFile().delete()
        repo.getSdcardConfigFile().delete()
    }

    @Test
    fun `photo capture uses minimize latency mode instead of zsl so zoom applies accurately`() {
        assertEquals(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY, CameraManager.DEFAULT_PHOTO_CAPTURE_MODE)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val cameraManager = CameraManager(context)
        assertFalse(cameraManager.cameraState.value.isZslActive)

        cameraManager.setZoomRatio(1.0f)
        assertEquals(1.0f, cameraManager.cameraState.value.currentZoomRatio, 0.001f)
        cameraManager.release()
    }

    @Test
    fun `folder repository saves restores and toggles audio photo and video flags`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("folder_cam_v2_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        FolderRepository(context).getFallbackConfigFile().delete()
        FolderRepository(context).getSdcardConfigFile().delete()

        val repo = FolderRepository(context)
        val voiceOnly = repo.addFolder(
            name = "VoiceNotes",
            sdcardPath = "/sdcard/Recordings/VoiceNotes",
            enableAudio = true,
            enablePhoto = false,
            enableVideo = false
        )

        assertEquals("/sdcard/Recordings/VoiceNotes", voiceOnly.sdcardPath)
        assertTrue(voiceOnly.enableAudio)
        assertFalse(voiceOnly.enablePhoto)
        assertFalse(voiceOnly.enableVideo)
        assertTrue(voiceOnly.isEnabledFor(RecorderMode.AUDIO))
        assertFalse(voiceOnly.isEnabledFor(RecorderMode.PHOTO))
        assertFalse(voiceOnly.isEnabledFor(RecorderMode.VIDEO))

        // Export & restore preserves toggle states
        val exportedJson = repo.toJsonString()
        assertTrue(exportedJson.contains("\"enableAudio\": true"))
        assertTrue(exportedJson.contains("\"enablePhoto\": false"))
        assertTrue(exportedJson.contains("\"enableVideo\": false"))

        assertTrue(repo.deleteFolder(voiceOnly.id))
        assertTrue(repo.restoreFromJsonString(exportedJson))
        val restored = repo.folders.value.first { it.id == voiceOnly.id }
        assertTrue(restored.enableAudio)
        assertFalse(restored.enablePhoto)
        assertFalse(restored.enableVideo)

        // Toggle Video ON via setFolderModeEnabled
        repo.setFolderModeEnabled(restored.id, RecorderMode.VIDEO, true)
        val toggled = repo.folders.value.first { it.id == voiceOnly.id }
        assertTrue(toggled.enableAudio)
        assertFalse(toggled.enablePhoto)
        assertTrue(toggled.enableVideo)

        // Legacy JSON missing toggle keys defaults all 3 modes to true and ensures Inbox is restored
        val legacyJson = """{"folders":[{"id":"legacy1","name":"Legacy","sdcardPath":"/sdcard/DCIM/Legacy"}],"selectedFolderId":"legacy1"}"""
        assertTrue(repo.restoreFromJsonString(legacyJson))
        assertTrue(repo.folders.value.any { it.isInbox })
        val legacy = repo.folders.value.first { it.id == "legacy1" }
        assertTrue(legacy.enableAudio)
        assertTrue(legacy.enablePhoto)
        assertTrue(legacy.enableVideo)

        repo.getFallbackConfigFile().delete()
        repo.getSdcardConfigFile().delete()
    }

    @Test
    fun `viewmodel filters bottom bar folders by active mode and always keeps inbox active`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("folder_cam_v2_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        FolderRepository(app).getFallbackConfigFile().delete()
        FolderRepository(app).getSdcardConfigFile().delete()

        val viewModel = FolderCamViewModel(app)
        val inbox = viewModel.folderRepository.getInboxFolder()
        val audioFolder = viewModel.addFolder(
            name = "Voice",
            sdcardPath = "/sdcard/Recordings/Voice",
            enableAudio = true,
            enablePhoto = false,
            enableVideo = false
        )
        val cameraFolder = viewModel.addFolder(
            name = "TripPhotos",
            sdcardPath = "/sdcard/DCIM/TripPhotos",
            enableAudio = false,
            enablePhoto = true,
            enableVideo = false
        )

        // Default mode is AUDIO -> Inbox + Voice appear (Voice auto-selected upon creation)
        assertEquals(RecorderMode.AUDIO, viewModel.recorderMode.value)
        assertEquals(listOf(inbox.id, audioFolder.id), viewModel.foldersForCurrentMode.value.map { it.id })
        assertEquals(audioFolder.id, viewModel.selectedFolder.value?.id)

        // Switch to PHOTO -> Inbox + TripPhotos appear
        viewModel.setRecorderMode(RecorderMode.PHOTO)
        assertEquals(listOf(inbox.id, cameraFolder.id), viewModel.foldersForCurrentMode.value.map { it.id })
        assertEquals(cameraFolder.id, viewModel.selectedFolder.value?.id)

        // Switch to VIDEO -> mandatory Inbox remains enabled for Video
        viewModel.setRecorderMode(RecorderMode.VIDEO)
        assertEquals(listOf(inbox.id), viewModel.foldersForCurrentMode.value.map { it.id })
        assertEquals(inbox.id, viewModel.selectedFolder.value?.id)
        // Gallery still lists Inbox + both custom folders
        assertEquals(3, viewModel.folders.value.size)

        // Enable Video on TripPhotos -> immediately appears in Video mode
        viewModel.setFolderModeEnabled(cameraFolder.id, RecorderMode.VIDEO, true)
        assertEquals(listOf(inbox.id, cameraFolder.id), viewModel.foldersForCurrentMode.value.map { it.id })

        // Clean up
        viewModel.folderRepository.getFallbackConfigFile().delete()
        viewModel.folderRepository.getSdcardConfigFile().delete()
    }

    @Test
    fun `mandatory inbox cannot disable modes or be deleted but allows editable path`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("folder_cam_v2_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        FolderRepository(context).getFallbackConfigFile().delete()
        FolderRepository(context).getSdcardConfigFile().delete()

        val repo = FolderRepository(context)
        val inbox = repo.getInboxFolder()
        assertFalse(repo.deleteFolder(inbox.id))

        // Attempt disabling Audio/Photo/Video on Inbox -> stays locked ON
        repo.setFolderModeEnabled(inbox.id, RecorderMode.AUDIO, false)
        repo.setFolderModeEnabled(inbox.id, RecorderMode.PHOTO, false)
        repo.setFolderModeEnabled(inbox.id, RecorderMode.VIDEO, false)
        val afterToggleAttempt = repo.getInboxFolder()
        assertTrue(afterToggleAttempt.enableAudio)
        assertTrue(afterToggleAttempt.enablePhoto)
        assertTrue(afterToggleAttempt.enableVideo)

        // Updating Inbox path works while keeping 3 modes locked ON
        repo.updateFolder(
            folderId = inbox.id,
            name = "Custom Inbox",
            sdcardPath = "/sdcard/Documents/CustomInbox",
            colorHex = "#22C55E",
            enableAudio = false,
            enablePhoto = false,
            enableVideo = false
        )
        val updatedInbox = repo.getInboxFolder()
        assertEquals("Custom Inbox", updatedInbox.name)
        assertEquals("/sdcard/Documents/CustomInbox", updatedInbox.sdcardPath)
        assertTrue(updatedInbox.isInbox)
        assertTrue(updatedInbox.enableAudio && updatedInbox.enablePhoto && updatedInbox.enableVideo)

        repo.getFallbackConfigFile().delete()
        repo.getSdcardConfigFile().delete()
    }

    @Test
    fun `missing folder directories are auto-created on disk per folder config`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MediaStorageManager(context)
        val uniqueSuffix = System.currentTimeMillis()
        val missingFolder = MediaFolder(
            id = "auto_create_$uniqueSuffix",
            name = "AutoCreated",
            sdcardPath = "/sdcard/FolderCamAutoTest_$uniqueSuffix"
        )

        val expectedSdcardDir = storage.toRealFile(missingFolder.sdcardPath)
        expectedSdcardDir.deleteRecursively()
        assertFalse(expectedSdcardDir.exists())

        storage.ensureFolderDirectoryExists(missingFolder)
        assertTrue(expectedSdcardDir.exists() && expectedSdcardDir.isDirectory)

        expectedSdcardDir.deleteRecursively()
    }

    @Test
    fun `video lens preference defaults to wide 1x and persists ultrawide in foldercam json`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("folder_cam_v2_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        FolderRepository(context).getFallbackConfigFile().delete()
        FolderRepository(context).getSdcardConfigFile().delete()

        val repo = FolderRepository(context)
        assertEquals(VideoLensPreference.WIDE, repo.videoLensPreference.value)

        repo.setVideoLensPreference(VideoLensPreference.ULTRAWIDE)
        val json = repo.toJsonString()
        assertTrue(json.contains("\"videoLensPreference\""))
        assertTrue(json.contains("\"ULTRAWIDE\""))

        repo.setVideoLensPreference(VideoLensPreference.WIDE)
        assertTrue(repo.restoreFromJsonString(json))
        assertEquals(VideoLensPreference.ULTRAWIDE, repo.videoLensPreference.value)

        repo.getFallbackConfigFile().delete()
        repo.getSdcardConfigFile().delete()
    }

    @Test
    fun `camera manager resolves ultrawide with fallback and configures 100-shot 88 percent burst`() {
        assertEquals(88, CameraManager.BURST_JPEG_QUALITY)
        assertEquals(100, CameraManager.MAX_BURST_PHOTOS)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val cameraManager = CameraManager(context)

        val lensesWithUw = listOf(
            LensOption(id = "uw", label = "0.5x", name = "Ultra-Wide", targetZoomRatio = 0.5f),
            LensOption(id = "1x", label = "1x", name = "Wide", targetZoomRatio = 1.0f),
            LensOption(id = "2x", label = "2x", name = "2x", targetZoomRatio = 2.0f)
        )
        assertEquals("uw", cameraManager.resolveLensForPreference(lensesWithUw, VideoLensPreference.ULTRAWIDE).id)
        assertEquals("1x", cameraManager.resolveLensForPreference(lensesWithUw, VideoLensPreference.WIDE).id)

        // Device without ultra-wide lens falls back gracefully to widest available lens ("1x")
        val lensesWithoutUw = listOf(
            LensOption(id = "1x", label = "1x", name = "Wide", targetZoomRatio = 1.0f),
            LensOption(id = "2x", label = "2x", name = "2x", targetZoomRatio = 2.0f)
        )
        assertEquals("1x", cameraManager.resolveLensForPreference(lensesWithoutUw, VideoLensPreference.ULTRAWIDE).id)

        cameraManager.release()
    }

    @Test
    fun `dnd controller fails safely without policy access and builds policy settings intent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dndController = DndController(context)

        assertFalse(dndController.hasPolicyAccess())
        assertFalse(dndController.enableDndForRecording())
        assertFalse(dndController.isDndActiveByApp())
        assertFalse(dndController.restoreDndAfterRecording())

        val intent = DndController.createPolicyAccessSettingsIntent()
        assertEquals(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, intent.action)
    }

    @Test
    fun `widget foreground service intents carry distinct video and audio actions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val videoIntent = RecordingForegroundService.createWidgetVideoIntent(context)
        val audioIntent = RecordingForegroundService.createWidgetAudioIntent(context)

        assertEquals(RecordingForegroundService.ACTION_WIDGET_TOGGLE_VIDEO, videoIntent.action)
        assertEquals(RecordingForegroundService.ACTION_WIDGET_TOGGLE_AUDIO, audioIntent.action)
    }

    @Test
    fun `burst buffer manager enqueues jpeg frames in RAM and drains to destination folder`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MediaStorageManager(context)
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val drainedResults = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()

        val bufferManager = BurstBufferManager(
            mediaStorageManager = storage,
            scope = testScope,
            onFrameDrained = { _, result -> drainedResults.add(result.destinationPath) },
            onFrameFailed = { err -> errors.add(err) }
        )

        assertTrue(bufferManager.getRamBudgetBytes() >= 32L * 1024L * 1024L)

        val uniqueSuffix = System.currentTimeMillis()
        val targetFolder = MediaFolder(
            id = "ram_burst_$uniqueSuffix",
            name = "RamBurst",
            sdcardPath = "/sdcard/DCIM/RamBurst_$uniqueSuffix"
        )
        storage.ensureFolderDirectoryExists(targetFolder)

        val sampleJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte())
        repeat(12) {
            assertTrue(bufferManager.enqueueCapturedBytes(targetFolder, sampleJpeg))
        }

        val deadline = System.currentTimeMillis() + 4000L
        while (System.currentTimeMillis() < deadline && drainedResults.size < 12) {
            Thread.sleep(20)
        }

        assertEquals(12, drainedResults.size)
        assertTrue(errors.isEmpty())
        assertEquals(0, bufferManager.getPendingCount())
        assertEquals(0L, bufferManager.getRamBufferedBytes())
        val realDestDir = storage.toRealFile(targetFolder.sdcardPath)
        assertNotNull(realDestDir.listFiles()?.takeIf { it.size == 12 })

        realDestDir.deleteRecursively()
        testScope.cancel()
    }
}
