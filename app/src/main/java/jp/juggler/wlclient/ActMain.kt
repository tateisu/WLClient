package jp.juggler.wlclient

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.beust.klaxon.JsonObject
import jp.juggler.util.*
import jp.juggler.wlclient.table.Girl
import jp.juggler.wlclient.table.History
import kotlinx.coroutines.*
import org.apache.commons.io.IOUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.CoroutineContext

class ActMain : AppCompatActivity(), CoroutineScope {

    companion object {

        private val log = LogCategory("ActMain")

        private val PERMISSIONS_STORAGE = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        private const val REQUEST_CODE_EXTERNAL_STORAGE = 1
        private const val REQUEST_CODE_UNDO_HISTORY = 2

        private const val PREF_LAST_STEP = "lastStep"
        private const val PREF_LAST_HISTORY = "lastHistory"

        private const val TAG_GIRL = R.id.tag_girl

        private fun decodePng(src: ByteArray?): Bitmap? {
            if (src == null || src.isEmpty()) return null
            return try {
                BitmapFactory.decodeStream(ByteArrayInputStream(src))
            } catch (ex: Throwable) {
                log.e("decodePng failed.")
                null
            }
        }
    }

    private lateinit var job: Job

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    private val ivCurrentGirl by lazy { findViewById<ImageView>(R.id.ivCurrentGirl) }
    private val btnNew by lazy { findViewById<ImageButton>(R.id.btnNew) }
    private val btnColor by lazy { findViewById<ImageButton>(R.id.btnColor) }
    private val btnDetail by lazy { findViewById<ImageButton>(R.id.btnDetail) }
    private val btnPose by lazy { findViewById<ImageButton>(R.id.btnPose) }
    private val btnSaveAll by lazy { findViewById<ImageButton>(R.id.btnSaveAll) }
    private val btnHistory by lazy { findViewById<ImageButton>(R.id.btnHistory) }
    private val btnHistoryBack by lazy { findViewById<ImageButton>(R.id.btnHistoryBack) }
    private val btnHistoryForward by lazy { findViewById<ImageButton>(R.id.btnHistoryForward) }

    private val svThumbnails by lazy { findViewById<HorizontalScrollView>(R.id.svThumbnails) }
    private val llThumbnails by lazy { findViewById<LinearLayout>(R.id.llThumbnails) }
    private val ivThumbnails = ArrayList<ImageView>()

    private var currentGirl: Girl? = null

    private var bitmapCurrentGirl: Bitmap? = null
    private val bitmapThumbnails = arrayOfNulls<Bitmap>(16)

    private var lastStep: Int = 0
    private var lastList: List<Girl>? = null
    private var lastHistoryId: Long = 0L

    private fun saveState() {
        App1.pref.edit()
            .putInt(PREF_LAST_STEP, lastStep)
            .putLong(PREF_LAST_HISTORY, lastHistoryId)
            .apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = Job()
        App1.prepare(this)

        lastStep = App1.pref.getInt(PREF_LAST_STEP, 0)
        lastHistoryId = App1.pref.getLong(PREF_LAST_HISTORY, 0L)

        initUI()

        checkStoragePermission()

        launch {
            if (!loadHistory(lastHistoryId)) showCurrentGirl()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runBlocking { job.cancelAndJoin() }
        bitmapThumbnails.forEach { it?.recycle() }
        bitmapCurrentGirl?.recycle()
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(outState)
        saveState()
    }

    override fun onStart() {
        super.onStart()
        if (!checkStoragePermission()) return
    }

    override fun onStop() {
        super.onStop()
        saveState()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_EXTERNAL_STORAGE ->
                grantResults
                    .find { it != PackageManager.PERMISSION_GRANTED }
                    ?.let {
                        showToast(this@ActMain, true, "permission not granted. exit app…")
                        finish()
                    }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                REQUEST_CODE_UNDO_HISTORY -> launch { loadHistory(data?.getLongExtra("gid", 0L)) }
            }
        }
    }


    private fun initUI() {
        setContentView(R.layout.act_main)

        val density = resources.displayMetrics.density

        val thumbHeight = (density * 200f + 0.5f).toInt()
        for (i in 0 until 16) {
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(thumbHeight, thumbHeight)
                scaleType = ImageView.ScaleType.FIT_CENTER
                llThumbnails.addView(this)
                ivThumbnails.add(this)

                setOnClickListener {
                    launch {
                        (it.getTag(TAG_GIRL) as? Girl)?.choose()
                    }
                }
                setOnLongClickListener {
                    launch {
                        (it.getTag(TAG_GIRL)  as? Girl)?.contextMenu(this@ActMain, lastStep)
                    }
                    true
                }
            }
        }

        ivCurrentGirl.setOnClickListener {
            launch {
                currentGirl?.contextMenu(this@ActMain, lastStep)
            }
        }

        btnNew.setOnClickListener { generate(0) }
        btnColor.setOnClickListener { generate(1) }
        btnDetail.setOnClickListener { generate(2) }
        btnPose.setOnClickListener { generate(3) }

        btnSaveAll.setOnClickListener { saveAll() }
        btnSaveAll.setButtonColor(this@ActMain, R.drawable.ic_save, false)

        btnHistory.setOnClickListener {
            startActivityForResult(Intent(this, ActUndoHistory::class.java), REQUEST_CODE_UNDO_HISTORY)
        }

        btnHistoryBack.setOnClickListener {
            launch {
                History.loadById(lastHistoryId, condition = "<", order = "desc")?.show()
            }
        }

        btnHistoryForward.setOnClickListener {
            launch {
                History.loadById(lastHistoryId, condition = ">", order = "asc")?.show()
            }
        }
    }


    // return true if permission granted
    private fun checkStoragePermission() =
        when (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            PackageManager.PERMISSION_GRANTED -> true
            else -> {
                ActivityCompat.requestPermissions(
                    this,
                    PERMISSIONS_STORAGE,
                    REQUEST_CODE_EXTERNAL_STORAGE
                )
                false
            }
        }

    private fun generate(step: Int) = launch {

        lastStep = step

        try {
            ProgressRunner(this@ActMain).progressPrefix("generate…").run {

                var history: History? = null

                val list = withContext(Dispatchers.IO) {

                    val base = currentGirl

                    val parentSeed: String?
                    val dataString = JsonObject().apply {
                        if (base == null || step == 0) {
                            put("step", 0)
                            parentSeed = null
                        } else {
                            put("step", step)
                            put("currentGirl", base.seeds.toJsonAny())
                            parentSeed = base.seeds
                        }
                    }.toJsonString()

                    val h = History.create(History.EVENT_GENERATE, step, parentSeed)
                    history = h

                    Girl.generate(dataString, h)
                }

                setThumbnails(list)

                history?.apply {
                    saveThumbnail(bitmapThumbnails)
                    lastHistoryId = id
                }

                if (step == 0) {
                    currentGirl = null
                    showCurrentGirl()
                }
            }
        } catch (ex: Throwable) {
            log.trace(ex)
            showToast(this@ActMain, ex, "generate failed")
        }
    }

    private suspend fun History.show() = ProgressRunner(this@ActMain)
        .progressPrefix("load from history…")
        .run {
            log.d("History.show() current=$seeds, id=$id, generationId=$generationId")

            lastStep = step
            lastHistoryId = id

            // restore current girl
            currentGirl = Girl.load(seeds)
            currentGirl?.prepareLargeImage(this@ActMain, step)
            showCurrentGirl()

            //restore choice
            setThumbnails(Girl.loadByHistoryId(generationId.notZero() ?: id))

        }

    private suspend fun loadHistory(historyId: Long?): Boolean {
        if (historyId == null) {
            showToast(this@ActMain, false, "missing id.")
            return false
        }

        if (historyId == 0L) return false

        val src = History.loadById(historyId)
        if (src == null) {
            showToast(this@ActMain, false, "missing data.")
            return false
        }

        src.show()
        return true

    }


    private suspend fun setThumbnails(list: List<Girl> = ArrayList()) {

        log.d("setThumbnails: list = ${list.size}")

        lastList = list

        btnSaveAll.setButtonColor(this@ActMain, R.drawable.ic_save, list.isNotEmpty())

        svThumbnails.scrollTo(0, 0)

        ivThumbnails.forEachIndexed { idx, iv ->
            try {
                // ビットマップへの参照を外す
                iv.setImageDrawable(null)
                iv.setTag(TAG_GIRL, null)

                // サムネイル画像を更新

                val item = if (idx >= list.size) {
                    null
                } else {
                    list[idx]
                }

                if (item != null) {
                    val bitmap = withContext(Dispatchers.IO) {
                        decodePng(item.thumbnail)
                    }
                    bitmapThumbnails[idx]?.recycle()
                    bitmapThumbnails[idx] = bitmap
                    iv.setImageBitmap(bitmap)
                    iv.setTag(TAG_GIRL, item)
                } else {
                    bitmapThumbnails[idx]?.recycle()
                    bitmapThumbnails[idx] = null
                }
            } catch (ex: Throwable) {
                log.e(ex, "showThumbnail failed.")
            }
        }
    }

    private suspend fun Girl.choose() {
        ProgressRunner(this@ActMain).progressPrefix("prepare large image…").run {
            prepareLargeImage(this@ActMain, lastStep)
            currentGirl = this
            showCurrentGirl()

            History.create(
                History.EVENT_CHOOSE,
                lastStep,
                seeds,
                generationId = lastList?.first()?.generationId ?: 0L
            ).apply {
                saveThumbnail(bitmapThumbnails)
                lastHistoryId = id
            }

            setThumbnails()
        }
    }

    private suspend fun showCurrentGirl() {

        val girl = currentGirl

        val hasGirl = girl != null
        btnColor.setButtonColor(this@ActMain, R.drawable.ic_color, hasGirl)
        btnDetail.setButtonColor(this@ActMain, R.drawable.ic_brush, hasGirl)
        btnPose.setButtonColor(this@ActMain, R.drawable.ic_run, hasGirl)

        ivCurrentGirl.setImageDrawable(null)

        girl?.largePath?.notEmpty()?.let { path ->
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    decodePng(FileInputStream(File(path)).use {
                        val bao = ByteArrayOutputStream()
                        IOUtils.copy(it, bao)
                        bao.toByteArray()
                    })
                }
                bitmapCurrentGirl?.recycle()
                bitmapCurrentGirl = bitmap
                ivCurrentGirl.setImageBitmap(bitmap)
            } catch (ex: Throwable) {
                log.trace(ex)
                showToast(this@ActMain, ex, "showCurrentGirl: bitmap load failed.")
            }
        }
    }

    private fun saveAll() = launch {
        ProgressRunner(this@ActMain).progressPrefix("Save images… ").run { runner ->
            val list = lastList
            if (list?.isEmpty() != false) {
                showToast(this@ActMain, true, "list is null or empty.")
            } else {
                list.forEachIndexed { index, girl ->
                    runner.publishApiProgress(String.format("%d/%d", index + 1, list.size))
                    girl.prepareLargeImage(this@ActMain, lastStep)
                }
                showToast(this@ActMain, true, "all image was saved to ${getExternalFilesDir(null)?.absolutePath}")
            }
        }
    }

}
