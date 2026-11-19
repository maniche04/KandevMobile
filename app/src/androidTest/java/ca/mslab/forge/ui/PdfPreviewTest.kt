package ca.mslab.forge.ui

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.mslab.forge.data.WorkspaceFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfPreviewTest {
    @Test fun rendersBothPagesAtBoundedSizeAndRemovesTemporaryFiles() {
        val output = ByteArrayOutputStream()
        val pdf = PdfDocument()
        try {
            listOf(Color.RED, Color.BLUE).forEachIndexed { index, color ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 600, index + 1).create())
                page.canvas.drawColor(color)
                pdf.finishPage(page)
            }
            pdf.writeTo(output)
        } finally { pdf.close() }
        val file = WorkspaceFile("report.PDF", Base64.getEncoder().encodeToString(output.toByteArray()), binary = true)
        val cache = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pdf-test").apply { mkdirs() }
        try {
            listOf(Color.RED, Color.BLUE).forEachIndexed { index, color ->
                val (count, image) = renderPdfPage(file, cache, index)
                assertEquals(2, count)
                assertEquals(800, image.width)
                assertEquals(1600, image.height)
                assertEquals(color, image.asAndroidBitmap().getPixel(400, 800))
                image.asAndroidBitmap().recycle()
                assertTrue(cache.listFiles().orEmpty().isEmpty())
            }
            assertTrue(runCatching { renderPdfPage(file, cache, 2) }.isFailure)
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        } finally { cache.deleteRecursively() }
    }

    @Test fun corruptPdfFailsAndRemovesTemporaryFile() {
        val cache = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pdf-invalid-test").apply { mkdirs() }
        try {
            assertTrue(runCatching { renderPdfPage(WorkspaceFile("broken.pdf", "not a PDF"), cache, 0) }.isFailure)
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        } finally { cache.deleteRecursively() }
    }
}
