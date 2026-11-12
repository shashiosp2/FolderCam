package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.FolderRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
  fun `folder repository initializes with predefined folders`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = FolderRepository(context)
    val folders = repo.folders.value

    assertTrue("Should have predefined folders", folders.isNotEmpty())
    assertNotNull(repo.getSelectedFolder())

    // Test adding folder
    val custom = repo.addFolder("Test Project", "#38BDF8", "Folder")
    assertEquals("Test Project", custom.name)
    assertEquals(custom.id, repo.selectedFolderId.value)
  }
}
