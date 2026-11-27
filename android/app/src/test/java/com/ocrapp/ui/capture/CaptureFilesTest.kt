package com.ocrapp.ui.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CaptureFilesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `sweep deletes the photos of an earlier process and keeps this one's`() {
        val startedAt = System.currentTimeMillis()
        val orphan = CaptureFiles.create(context).apply { setLastModified(startedAt - 60_000) }
        val pending = CaptureFiles.create(context).apply { setLastModified(startedAt + 1_000) }

        CaptureFiles.sweep(context, startedAt)

        assertFalse(orphan.exists())
        assertTrue(pending.exists())
    }
}
