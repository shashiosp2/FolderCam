package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.FolderRepository
import com.example.model.RecorderMode
import com.example.ui.FolderCamViewModel
import org.junit.Assert.assertEquals
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
    fun `folder repository starts empty by default and adds sdcard folder`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("folder_cam_v2_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()

        val repo = FolderRepository(context)
        assertTrue("By default no folders should exist", repo.folders.value.isEmpty())
        assertNull(repo.getSelectedFolder())

        val custom = repo.addFolder(
            name = "FieldRecordings",
            sdcardPath = "/sdcard/Music/FieldRecordings"
        )
        assertEquals("FieldRecordings", custom.name)
        assertEquals("/sdcard/Music/FieldRecordings", custom.sdcardPath)
        assertEquals(custom.id, repo.selectedFolderId.value)
    }

    @Test
    fun `viewmodel defaults to Audio mode first then Photo then Video`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FolderCamViewModel(app)

        // Default mode on open is AUDIO
        assertEquals(RecorderMode.AUDIO, viewModel.recorderMode.value)
        assertEquals(0, RecorderMode.AUDIO.index)
        assertEquals(1, RecorderMode.PHOTO.index)
        assertEquals(2, RecorderMode.VIDEO.index)

        // Switching left to PHOTO and VIDEO
        viewModel.setRecorderMode(RecorderMode.PHOTO)
        assertEquals(RecorderMode.PHOTO, viewModel.recorderMode.value)

        viewModel.setRecorderMode(RecorderMode.VIDEO)
        assertEquals(RecorderMode.VIDEO, viewModel.recorderMode.value)
    }
}
