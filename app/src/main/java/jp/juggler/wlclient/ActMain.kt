package jp.juggler.wlclient

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.beust.klaxon.JsonObject
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import jp.juggler.util.*
import jp.juggler.wlclient.table.Girl
import jp.juggler.wlclient.table.History
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


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

    }

    private val myActivity = this

    private lateinit var job: Job

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    internal lateinit var glide: RequestManager

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

    // girl currently selected.
    private var currentGirl: Girl? = null

    // list of girls can be choose.
    private var lastList: List<Girl>? = null

    /////////////////////////////////////////////////////
    // states.
    private var lastStep: Int = 0
    private var lastHistoryId: Long = 0L

    private fun saveState() {
        App1.pref.edit()
            .putInt(PREF_LAST_STEP, lastStep)
            .putLong(PREF_LAST_HISTORY, lastHistoryId)
            .apply()
    }

    //////////////////////////////////////////////////////////////
    // lifecycle events

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = Job()
        App1.prepare(this)

        glide = Glide.with(this)

        lastStep = App1.pref.getInt(PREF_LAST_STEP, 0)
        lastHistoryId = App1.pref.getLong(PREF_LAST_HISTORY, 0L)

        initUI()

        checkStoragePermission()

        launchEx("restoreLast") {
            if (!loadHistory(lastHistoryId)) showCurrentGirl()
        }
    }

    override fun onDestroy() {
        runBlocking {
            try {
                job.cancelAndJoin()
            } catch (ex: Throwable) {
                log.trace(ex)
            }
        }

        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (!checkStoragePermission()) return
    }

    override fun onStop() {
        super.onStop()
        saveState()
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(outState)
        saveState()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                REQUEST_CODE_UNDO_HISTORY -> launchEx("loadHistory") {
                    loadHistory(data?.getLongExtra("gid", 0L))
                }
            }
        }
    }

    //////////////////////////////////////////////////////

    private fun initUI() {
        setContentView(R.layout.act_main)

        val density = resources.displayMetrics.density

        val toolBar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolBar)


        val thumbHeight = (density * 200f + 0.5f).toInt()
        for (i in 0 until 16) {
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(thumbHeight, thumbHeight)
                scaleType = ImageView.ScaleType.FIT_CENTER
                llThumbnails.addView(this)
                ivThumbnails.add(this)

                setOnClickListener {
                    (it.getTag(TAG_GIRL) as? Girl)?.choose()
                }

                setOnLongClickListener {
                    (it.getTag(TAG_GIRL)  as? Girl)?.contextMenu()
                    true
                }
            }
        }

        ivCurrentGirl.setOnClickListener {
            currentGirl?.contextMenu()
        }

        btnNew.setOnClickListener { generate(0) }
        btnColor.setOnClickListener { generate(1) }
        btnDetail.setOnClickListener { generate(2) }
        btnPose.setOnClickListener { generate(3) }

        btnSaveAll.setOnClickListener { saveAll() }

        btnHistory.setOnClickListener {
            startActivityForResult(Intent(this, ActUndoHistory::class.java), REQUEST_CODE_UNDO_HISTORY)
        }

        btnHistoryBack.setOnClickListener {
            launchEx("backHistory") {
                History.loadById(lastHistoryId, condition = "<", order = "desc")?.show()
            }
        }

        btnHistoryForward.setOnClickListener {
            launchEx("forwardHistory") {
                History.loadById(lastHistoryId, condition = ">", order = "asc")?.show()
            }
        }

        btnSaveAll.setButtonColor(myActivity, R.drawable.ic_save, false)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.act_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {

            R.id.menuSeeds -> {
                currentGirl?.seedsDialog()
                true
            }
            R.id.menuGenerate ->{
                generateDialog()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }

    ////////////////////////////////////////////////////////////////////////
    // runtime permission

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


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_EXTERNAL_STORAGE ->
                grantResults
                    .find { it != PackageManager.PERMISSION_GRANTED }
                    ?.let {
                        showToast(myActivity, true, "permission not granted. exit app…")
                        finish()
                    }
        }
    }


    ////////////////////////////////////////////////////////////////////////
    // UI actions that launch coroutines.

    private fun generate(
        step: Int,
        seedsArg: String? = null,
        callback: suspend (isOk: Boolean) -> Unit = {}
    ) = launchEx("generate()") {

        callback(try {

            var history: History? = null
            ProgressRunner(myActivity).run { progress ->

                val seeds = if (step == 0) {
                    null
                } else {
                    seedsArg ?: currentGirl?.seeds
                }

                val list = withContext(Dispatchers.IO) {

                    progress.publishApiProgress("create history…")

                    val parentSeed: String?
                    val dataString = JsonObject().apply {
                        if (seeds == null || step == 0) {
                            put("step", 0)
                            parentSeed = null
                        } else {
                            put("step", step)
                            put("currentGirl", seeds.toJsonAny())
                            parentSeed = seeds
                        }
                    }.toJsonString()

                    val h = History.create(History.EVENT_GENERATE, step, parentSeed)
                    history = h

                    progress.publishApiProgress("acquire results…")

                    Girl.generate(dataString, h)
                }

                setThumbnails(list, progress)

                progress.publishApiProgress("save history…")
                history?.apply { saveThumbnail(list.map { it.thumbnail }) }

                if (step == 0 || seedsArg != null) {
                    currentGirl = null
                    showCurrentGirl()
                }

                lastStep = step
                lastHistoryId = history!!.id
                true
            }
        } catch (ex: Throwable) {
            log.trace(ex)
            showToast(myActivity, ex, "generate failed")
            false
        })
    }

    private fun Girl.choose() = launchEx("choose") {

        ProgressRunner(myActivity)
            .run { progress ->
                prepareLargeImage(myActivity, lastStep, progress)
                currentGirl = this
                showCurrentGirl()

                History.create(
                    History.EVENT_CHOOSE,
                    lastStep,
                    seeds,
                    generationId = lastList?.first()?.generationId ?: 0L
                ).apply {
                    progress.publishApiProgress("save history…")
                    saveThumbnail(lastList?.map { it.thumbnail })
                    lastHistoryId = id
                }

                // setThumbnails(lastList,progress = progress)
            }
    }

    suspend fun chooseBySeeds(seeds: String): Girl? = ProgressRunner(myActivity).run { progress ->
        val oldHistory = History.loadBySeed(seeds)
        if( oldHistory != null){
            oldHistory.show()
            return@run currentGirl
        }

        val history =  History.create(History.EVENT_CHOOSE,4,seeds)

        Girl.generateFromSeeds(myActivity, history, progress)?.apply {
            lastStep = history.step
            lastList = null
            currentGirl = this
            showCurrentGirl()

            val g2 = Girl.load(this.seeds)
            if(g2 == null){
                log.e("girl not saved!")
            }

            history.apply {
                progress.publishApiProgress("save history…")
                saveThumbnail(null)
                lastHistoryId = id
            }
            setThumbnails(null, progress = progress)
        }
    }

    private fun saveAll() = launchEx("saveAll") {
        ProgressRunner(myActivity, progress_style = ProgressRunner.PROGRESS_HORIZONTAL)
            .run { progress ->
                val list = lastList
                when {
                    list?.isEmpty() != false ->
                        showToast(myActivity, true, "list is null or empty.")
                    else -> {
                        list.forEachIndexed { index, girl ->
                            progress.publishApiProgressRatio(index + 1, list.size)
                            girl.prepareLargeImage(myActivity, lastStep, progress)
                        }
                        val path = getExternalFilesDir(null)?.absolutePath
                        showToast(myActivity, true, "all image was saved to $path")
                    }
                }
            }
    }


    private fun Girl.contextMenu() = launchEx("contextMenu") {
        DlgContextMenu(myActivity, this, lastStep).show()
    }

    private fun Girl?.seedsDialog() = launchEx("seedsDialog") {
        DlgSeeds.open(myActivity, this) { step, seeds ->
            suspendCoroutine { cont ->
                generate(step, seeds) {
                    cont.resume(it)
                }
            }
        }
    }
    private fun generateDialog() = launchEx("generateDialog") {
        DlgGenerate.open(myActivity)
    }
    ////////////////////////////////////////////////////////////////////////
    // internal functions

    fun launchEx(caption: String, block: suspend () -> Unit) = launch {
        try {
            block()
        } catch (ex: Throwable) {
            log.trace(ex)
            showToast(myActivity, ex, "$caption failed.")
        }
    }

    private fun showCurrentGirl() {
        val girl = currentGirl

        val hasGirl = girl != null
        btnColor.setButtonColor(myActivity, R.drawable.ic_color, hasGirl)
        btnDetail.setButtonColor(myActivity, R.drawable.ic_brush, hasGirl)
        btnPose.setButtonColor(myActivity, R.drawable.ic_run, hasGirl)

        ivCurrentGirl.setImageDrawable(null)
        glide.clear(ivCurrentGirl)

        val path = girl?.largePath
        val file = when {
            path?.isNotEmpty() == true -> File(path)
            else -> null
        }
        val thumbnail = girl?.thumbnail

        when {
            file?.exists() == true -> glide.load(file).into(ivCurrentGirl)
            thumbnail?.isNotEmpty() == true -> glide.load(thumbnail).into(ivCurrentGirl)
        }
    }

    private fun setThumbnails(
        listArg: List<Girl>? = null,
        progress: ProgressRunner? = null
    ) {
        val list = listArg ?: ArrayList()

        progress?.publishApiProgress("set thumbnails…")

        log.d("setThumbnails: list = ${list.size}")

        lastList = list

        btnSaveAll.setButtonColor(myActivity, R.drawable.ic_save, list.isNotEmpty())

        svThumbnails.scrollTo(0, 0)

        ivThumbnails.forEachIndexed { idx, iv ->

            progress?.publishApiProgressRatio(idx + 1, list.size)

            try {
                // ビットマップへの参照を外す
                iv.setImageDrawable(null)
                iv.setTag(TAG_GIRL, null)
                glide.clear(iv)

                // サムネイル画像を更新
                if (idx < list.size) {
                    val item = list[idx]
                    iv.setTag(TAG_GIRL, item)
                    glide.load(item.thumbnail).into(iv)

                    log.d("setThumbnails: ${item.seeds}")
                }

            } catch (ex: Throwable) {
                log.trace(ex)
                log.e(ex, "showThumbnail failed.")
            }
        }
    }


    // returns false if loading failed and still current girl is not shown.
    private suspend fun loadHistory(historyId: Long?) = when (historyId) {

        null -> {
            showToast(myActivity, false, "missing id.")
            false
        }

        0L -> false // toast will not be shown in this case.

        else -> when (val src = History.loadById(historyId)) {

            null -> {
                showToast(myActivity, false, "missing data.")
                false
            }

            else -> {
                src.show()
                true
            }
        }
    }

    private suspend fun History.show() {
        ProgressRunner(myActivity)
            .run { progress ->
                log.d("History.show() current=$seeds, id=$id, generationId=$generationId")

                lastStep = step
                lastHistoryId = id

                // restore current girl
                currentGirl = Girl.load(seeds)
                currentGirl?.prepareLargeImage(myActivity, step, progress = progress)

                showCurrentGirl()

                //restore choice
                setThumbnails(Girl.loadByHistoryId(generationId.notZero() ?: id), progress)
            }
    }
}
