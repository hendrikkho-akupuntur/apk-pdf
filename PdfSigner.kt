package id.hendrik.pdfttd

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

/** Menempelkan tanda tangan ke PDF asli (teks dan kualitas PDF tetap utuh). */
object PdfSigner {
    fun sign(input: File, output: File, placements: List<Placement>, sig: Bitmap) {
        PDDocument.load(input).use { doc ->
            val img = LosslessFactory.createFromImage(doc, sig)
            val aspect = sig.height.toFloat() / sig.width
            placements.groupBy { it.page }.forEach { (idx, list) ->
                val page = doc.getPage(idx)
                val box = page.cropBox
                val rot = ((page.rotation % 360) + 360) % 360
                val wp = box.width
                val hp = box.height
                val swap = rot == 90 || rot == 270
                val dispW = if (swap) hp else wp
                val dispH = if (swap) wp else hp
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.transform(Matrix(1f, 0f, 0f, 1f, box.lowerLeftX, box.lowerLeftY))
                    val m = when (rot) {
                        90 -> Matrix(0f, 1f, -1f, 0f, wp, 0f)
                        180 -> Matrix(-1f, 0f, 0f, -1f, wp, hp)
                        270 -> Matrix(0f, -1f, 1f, 0f, 0f, hp)
                        else -> Matrix(1f, 0f, 0f, 1f, 0f, 0f)
                    }
                    cs.transform(m)
                    list.forEach { p ->
                        val w = p.w * dispW
                        val h = w * aspect
                        val u = p.x * dispW
                        val top = p.y * dispH
                        cs.drawImage(img, u, dispH - top - h, w, h)
                    }
                }
            }
            doc.save(output)
        }
    }
}
