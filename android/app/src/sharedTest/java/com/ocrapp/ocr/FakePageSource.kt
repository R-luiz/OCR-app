package com.ocrapp.ocr

import android.graphics.Bitmap

/**
 * A [PageSource] over bitmaps made on request, for tests with no file to open.
 *
 * [page] is called once per render, because the engines recycle what they are handed.
 */
class FakePageSource(
    override val pageCount: Int,
    private val page: (index: Int) -> Bitmap = { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) },
) : PageSource {

    /** The `maxEdge` of every render so far, in call order. */
    val requestedEdges = mutableListOf<Int>()

    override suspend fun render(index: Int, maxEdge: Int): Bitmap {
        requestedEdges += maxEdge
        return page(index)
    }

    override fun close() = Unit
}
