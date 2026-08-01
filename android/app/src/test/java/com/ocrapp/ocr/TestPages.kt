package com.ocrapp.ocr

import android.graphics.Bitmap

/** Minimal page bitmap for tests; Robolectric provides the Bitmap implementation. */
fun testPage(index: Int = 0, width: Int = 8, height: Int = 8): PageImage =
    PageImage(
        index = index,
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
    )
