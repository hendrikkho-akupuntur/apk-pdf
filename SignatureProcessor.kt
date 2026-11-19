package id.hendrik.pdfttd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** Mengubah foto tanda tangan (kertas putih) menjadi PNG transparan yang sudah dipotong rapi. */
object SignatureProcessor {
    private const val FILE = "signature.png"

    fun load(ctx: Context): Bitmap? {
        val f = File(ctx.filesDir, FILE)
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    fun save(ctx: Context, b: Bitmap) {
        File(ctx.filesDir, FILE).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun process(ctx: Context, uri: Uri): Bitmap {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1800) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var src = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Gambar tidak bisa dibaca")

        val orient = try {
            cr.openInputStream(uri)!!.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val deg = when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            src = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(deg) }, true)
        }

        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val lum = FloatArray(w * h) {
            val c = px[it]
            0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        }

        // Perkirakan kecerahan kertas per area (supaya bayangan/pencahayaan tidak rata tetap bersih)
        val gx = 12
        val gy = 12
        val grid = Array(gy) { FloatArray(gx) }
        val hist = IntArray(256)
        for (ty in 0 until gy) for (tx in 0 until gx) {
            val x0 = tx * w / gx; val x1 = (tx + 1) * w / gx
            val y0 = ty * h / gy; val y1 = (ty + 1) * h / gy
            hist.fill(0)
            var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                hist[lum[y * w + x].toInt().coerceIn(0, 255)]++
                n++
            }
            var acc = 0
            var v = 255
            val target = (n * 0.8f).toInt()
            for (i in 0..255) {
                acc += hist[i]
                if (acc >= target) { v = i; break }
            }
            grid[ty][tx] = v.toFloat()
        }
        val sorted = grid.flatMap { it.toList() }.sorted()
        val floor = sorted[sorted.size / 2] * 0.7f
        for (ty in 0 until gy) for (tx in 0 until gx) grid[ty][tx] = max(grid[ty][tx], floor)

        fun bgAt(x: Int, y: Int): Float {
            val fx = ((x + 0.5f) * gx / w - 0.5f).coerceIn(0f, gx - 1f)
            val fy = ((y + 0.5f) * gy / h - 0.5f).coerceIn(0f, gy - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val x1 = min(x0 + 1, gx - 1); val y1 = min(y0 + 1, gy - 1)
            val ax = fx - x0; val ay = fy - y0
            val top = grid[y0][x0] * (1 - ax) + grid[y0][x1] * ax
            val bot = grid[y1][x0] * (1 - ax) + grid[y1][x1] * ax
            return top * (1 - ay) + bot * ay
        }

        val alpha = IntArray(w * h)
        var sr = 0L; var sg = 0L; var sb = 0L; var cnt = 0L
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val r = lum[i] / max(bgAt(x, y), 1f)
            val a = ((0.88f - r) / (0.88f - 0.55f)).coerceIn(0f, 1f)
            val ai = (a * 255).toInt()
            alpha[i] = ai
            if (r < 0.5f) {
                sr += Color.red(px[i]); sg += Color.green(px[i]); sb += Color.blue(px[i]); cnt++
            }
            if (ai > 40) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) throw IOException("Tanda tangan tidak terdeteksi. Pakai foto tinta gelap di kertas putih dengan cahaya cukup.")

        val ir = if (cnt > 0) (sr / cnt * 0.6f).toInt() else 0
        val ig = if (cnt > 0) (sg / cnt * 0.6f).toInt() else 0
        val ib = if (cnt > 0) (sb / cnt * 0.6f).toInt() else 0

        val pad = max(4, (0.03f * max(maxX - minX, maxY - minY)).toInt())
        val cx0 = max(0, minX - pad); val cy0 = max(0, minY - pad)
        val cx1 = min(w - 1, maxX + pad); val cy1 = min(h - 1, maxY + pad)
        val cw = cx1 - cx0 + 1
        val ch = cy1 - cy0 + 1
        val out = IntArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            out[y * cw + x] = Color.argb(alpha[(y + cy0) * w + (x + cx0)], ir, ig, ib)
        }
        var bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, cw, 0, 0, cw, ch)
        if (cw > 900) bmp = Bitmap.createScaledBitmap(bmp, 900, max(1, ch * 900 / cw), true)
        return bmp
    }
}
