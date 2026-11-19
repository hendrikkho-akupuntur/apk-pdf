package id.hendrik.pdfttd

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    companion object {
        private const val REQ_PDF = 1
        private const val REQ_SIG = 2
    }

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var zoomLayout: ZoomLayout

    private val lock = Any()
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var currentFile: File? = null
    private var pageSizes: List<Pair<Int, Int>> = emptyList()
    private val placements = mutableListOf<Placement>()
    private var sourceUri: Uri? = null
    private var sourceName = "dokumen"
    private var sigBitmap: Bitmap? = null
    private var hintShown = false
    /** Password yang berhasil membuka PDF (jika ada). */
    private var pdfPassword: String? = null

    private val renderExec = Executors.newSingleThreadExecutor()
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        sigBitmap = SignatureProcessor.load(this)
        buildUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) i.data?.let { openPdf(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Landscape membantu membaca; reset zoom agar halaman fit lebar baru
        zoomLayout.resetZoom()
        list.post { list.adapter?.notifyDataSetChanged() }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeRenderer()
        renderExec.shutdown()
        io.shutdown()
    }

    // ---------- UI ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF424242.toInt())
        }
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            setBackgroundColor(0xFF616161.toInt())
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(out: Rect, v: View, parent: RecyclerView, s: RecyclerView.State) {
                    out.bottom = dp(8)
                }
            })
        }
        empty = TextView(this).apply {
            text = "Belum ada PDF.\n\nTekan \"Buka\", atau tap file PDF\ndari Download / File Manager.\n\nCubit layar untuk zoom.\nPutar ke landscape untuk lebih lega."
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 16f
        }
        zoomLayout = ZoomLayout(this)
        zoomLayout.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val frame = FrameLayout(this)
        frame.addView(zoomLayout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        fun btn(label: String, action: () -> Unit) {
            bar.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        btn("Buka") { pick("application/pdf", REQ_PDF) }
        btn("TTD") { onSignature() }
        btn("Ganti TTD") { pick("image/*", REQ_SIG) }
        btn("Simpan") { onSave() }
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
    }

    private fun pick(mime: String, req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            if (req == REQ_PDF) addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        startActivityForResult(i, req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_PDF -> openPdf(uri)
            REQ_SIG -> importSignature(uri)
        }
    }

    // ---------- Buka PDF ----------

    private fun queryName(uri: Uri): String {
        var n: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) n = it.getString(0)
            }
        } catch (_: Exception) {}
        return (n ?: uri.lastPathSegment ?: "dokumen").substringAfterLast('/').removeSuffix(".pdf").removeSuffix(".PDF")
    }

    private fun openPdf(uri: Uri) {
        empty.text = "Membuka PDF…"
        empty.visibility = View.VISIBLE
        pdfPassword = null
        io.execute {
            try {
                val dest = File(cacheDir, "doc_${System.nanoTime()}.pdf")
                contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                openPdfFile(dest, uri, null)
            } catch (e: SecurityException) {
                ui.post { askPassword(uri) }
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                if (msg.contains("password") || msg.contains("encrypted") || msg.contains("security")) {
                    ui.post { askPassword(uri) }
                } else {
                    ui.post {
                        empty.text = "Gagal membuka PDF."
                        toast("Gagal membuka PDF: ${e.message}")
                    }
                }
            }
        }
    }

    private fun askPassword(uri: Uri) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password PDF"
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle("PDF terkunci")
            .setMessage("Masukkan password untuk membuka PDF ini.")
            .setView(input)
            .setPositiveButton("Buka") { _, _ ->
                val pw = input.text?.toString() ?: ""
                empty.text = "Membuka PDF dengan password…"
                empty.visibility = View.VISIBLE
                io.execute {
                    try {
                        val dest = File(cacheDir, "doc_${System.nanoTime()}.pdf")
                        contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                        val unlocked = File(cacheDir, "unlocked_${System.nanoTime()}.pdf")
                        try {
                            PDDocument.load(dest, pw).use { doc ->
                                doc.isAllSecurityToBeRemoved = true
                                doc.save(unlocked)
                            }
                        } catch (e: InvalidPasswordException) {
                            dest.delete()
                            ui.post {
                                empty.text = "Password salah."
                                toast("Password salah. Coba lagi.")
                                askPassword(uri)
                            }
                            return@execute
                        } catch (e: Exception) {
                            dest.delete()
                            ui.post {
                                empty.text = "Gagal membuka PDF."
                                toast("Gagal membuka: ${e.message}")
                            }
                            return@execute
                        }
                        dest.delete()
                        openPdfFile(unlocked, uri, pw)
                    } catch (e: Exception) {
                        ui.post {
                            empty.text = "Gagal membuka PDF."
                            toast("Gagal: ${e.message}")
                        }
                    }
                }
            }
            .setNegativeButton("Batal") { _, _ ->
                empty.text = "Belum ada PDF.\n\nTekan \"Buka\", atau tap file PDF\ndari Download / File Manager.\n\nCubit layar untuk zoom.\nPutar ke landscape untuk lebih lega."
            }
            .setCancelable(true)
            .show()
    }

    /** Buka file yang sudah siap (tidak terenkripsi) dengan PdfRenderer. */
    private fun openPdfFile(dest: File, uri: Uri, password: String?) {
        try {
            val fd = ParcelFileDescriptor.open(dest, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(fd)
            val sizes = (0 until r.pageCount).map { idx -> r.openPage(idx).use { it.width to it.height } }
            val name = queryName(uri)
            ui.post {
                closeRenderer()
                synchronized(lock) { renderer = r; pfd = fd }
                currentFile = dest
                pageSizes = sizes
                placements.clear()
                sourceUri = uri
                sourceName = name
                pdfPassword = password
                zoomLayout.resetZoom()
                list.adapter = PageAdapter()
                list.scrollToPosition(0)
                empty.visibility = View.GONE
                if (password != null) toast("PDF berhasil dibuka")
            }
        } catch (e: SecurityException) {
            dest.delete()
            ui.post {
                empty.text = "PDF ini terkunci password."
                toast("Masih terkunci. Coba password lain.")
                askPassword(uri)
            }
        } catch (e: Exception) {
            dest.delete()
            ui.post {
                empty.text = "Gagal membuka PDF."
                toast("Gagal membuka PDF: ${e.message}")
            }
        }
    }

    private fun closeRenderer() {
        synchronized(lock) {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            renderer = null
            pfd = null
        }
        currentFile?.delete()
        currentFile = null
    }

    private fun renderPage(i: Int, widthPx: Int): Bitmap? = synchronized(lock) {
        try {
            val r = renderer ?: return null
            r.openPage(i).use { page ->
                val h = (widthPx.toFloat() * page.height / page.width).toInt()
                val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Daftar halaman ----------

    private class PageHolder(val frame: FrameLayout, val img: ImageView) : RecyclerView.ViewHolder(frame)

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = pageSizes.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val frame = FrameLayout(parent.context).apply {
                setBackgroundColor(Color.WHITE)
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            val img = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_XY }
            frame.addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            return PageHolder(frame, img)
        }

        override fun onBindViewHolder(h: PageHolder, pos: Int) {
            val (pw, ph) = pageSizes[pos]
            val w = list.width.coerceAtLeast(1)
            val hgt = (w.toFloat() * ph / pw).toInt().coerceAtLeast(1)
            h.frame.layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, hgt)
            h.img.setImageDrawable(null)
            while (h.frame.childCount > 1) h.frame.removeViewAt(1)
            placements.filter { it.page == pos }.forEach { addSig(h.frame, it, w, hgt) }
            val px = min((w * 2.2f).toInt(), 3000)
            renderExec.execute {
                val bmp = renderPage(pos, px)
                ui.post {
                    if (bmp != null && h.bindingAdapterPosition == pos) h.img.setImageBitmap(bmp)
                }
            }
        }
    }

    // ---------- Tanda tangan ----------

    private fun addSig(frame: FrameLayout, p: Placement, pageW: Int, pageH: Int) {
        val bmp = sigBitmap ?: return
        frame.addView(SignatureView(this, bmp, p, pageW, pageH) { v ->
            AlertDialog.Builder(this)
                .setMessage("Hapus tanda tangan ini dari halaman?")
                .setPositiveButton("Hapus") { _, _ ->
                    placements.remove(p)
                    (v.parent as? ViewGroup)?.removeView(v)
                }
                .setNegativeButton("Batal", null)
                .show()
        })
    }

    private fun onSignature() {
        if (sigBitmap == null) {
            toast("Pilih foto tanda tangan dulu (JPG/PNG)")
            pick("image/*", REQ_SIG)
            return
        }
        if (renderer == null) {
            toast("Buka PDF dulu")
            return
        }
        placeSignature()
    }

    private fun placeSignature() {
        val bmp = sigBitmap ?: return
        val lm = list.layoutManager as LinearLayoutManager
        var pos = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val first = lm.findViewByPosition(pos)
        if (first != null && first.bottom < list.height * 0.4f) pos = min(pos + 1, pageSizes.size - 1)
        val v = lm.findViewByPosition(pos)
        val (pw, ph) = pageSizes[pos]
        val wFrac = 0.35f
        val hFrac = wFrac * (pw.toFloat() / ph) * (bmp.height.toFloat() / bmp.width)
        var yFrac = 0.4f
        if (v != null && v.height > 0) {
            val visTop = max(0, -v.top)
            val visBottom = min(v.height, list.height - v.top)
            val centerPx = (visTop + visBottom) / 2f
            yFrac = (centerPx / v.height - hFrac / 2).coerceIn(0f, max(0f, 1f - hFrac))
        }
        val p = Placement(pos, 0.3f, yFrac, wFrac)
        placements.add(p)
        val holder = list.findViewHolderForAdapterPosition(pos) as? PageHolder
        if (holder != null) addSig(holder.frame, p, list.width, holder.frame.layoutParams.height)
        else list.adapter?.notifyItemChanged(pos)
        if (!hintShown) {
            hintShown = true
            toast("Geser TTD untuk pindah, cubit TTD untuk ukuran, tekan lama hapus.\nCubit layar (bukan TTD) untuk zoom halaman.")
        }
    }

    private fun importSignature(uri: Uri) {
        io.execute {
            try {
                val b = SignatureProcessor.process(this, uri)
                SignatureProcessor.save(this, b)
                ui.post {
                    sigBitmap = b
                    val preview = ImageView(this).apply {
                        setImageBitmap(b)
                        setBackgroundColor(0xFFB3E5FC.toInt())
                        adjustViewBounds = true
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                    }
                    val dlg = AlertDialog.Builder(this)
                        .setTitle("Tanda tangan tersimpan")
                        .setView(preview)
                        .setNegativeButton("Tutup", null)
                    if (renderer != null) dlg.setPositiveButton("Tempel sekarang") { _, _ -> placeSignature() }
                    dlg.show()
                }
            } catch (e: Exception) {
                ui.post { toast(e.message ?: "Gagal memproses gambar") }
            }
        }
    }

    // ---------- Simpan ----------

    private fun onSave() {
        if (renderer == null) { toast("Buka PDF dulu"); return }
        if (placements.isEmpty()) { toast("Belum ada tanda tangan yang ditempel"); return }
        AlertDialog.Builder(this)
            .setTitle("Simpan PDF")
            .setItems(arrayOf("Simpan salinan (Dokumen/TTD PDF)", "Timpa file asli")) { _, which ->
                save(which == 1)
            }
            .show()
    }

    private fun save(overwrite: Boolean) {
        val src = currentFile ?: return
        val sig = sigBitmap ?: return
        val items = placements.toList()
        val name = "${sourceName}_ttd.pdf"
        val uri = sourceUri
        toast("Menyimpan…")
        io.execute {
            try {
                val out = File(cacheDir, "signed.pdf")
                PdfSigner.sign(src, out, items, sig)
                val msg: String
                if (overwrite) {
                    val u = uri ?: throw IllegalStateException("sumber file tidak diketahui")
                    contentResolver.openOutputStream(u, "wt")!!.use { o -> out.inputStream().use { it.copyTo(o) } }
                    msg = "File asli sudah ditimpa"
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/TTD PDF")
                    }
                    val dest = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                        ?: throw IllegalStateException("tidak bisa membuat file")
                    contentResolver.openOutputStream(dest)!!.use { o -> out.inputStream().use { it.copyTo(o) } }
                    msg = "Tersimpan di Dokumen/TTD PDF/$name"
                }
                out.delete()
                ui.post { toast(msg) }
            } catch (e: Exception) {
                ui.post {
                    toast(
                        if (overwrite) "Tidak bisa menimpa file asli (${e.message}). Pakai \"Simpan salinan\"."
                        else "Gagal menyimpan: ${e.message}"
                    )
                }
            }
        }
    }
}
