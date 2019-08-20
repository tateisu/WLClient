package jp.juggler.wlclient

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import jp.juggler.util.ActionsDialog
import jp.juggler.util.LogCategory
import jp.juggler.util.showToast
import jp.juggler.wlclient.table.Girl
import jp.juggler.wlclient.table.History
import kotlinx.coroutines.*
import java.io.File
import java.util.*
import kotlin.coroutines.CoroutineContext

class ActUndoHistory : AppCompatActivity(), CoroutineScope {

    companion object {
        private val log = LogCategory("ActUndoHistory")
    }

    private lateinit var job: Job

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    private lateinit var glide: RequestManager

    private lateinit var adapter: MyAdapter

    private val listView by lazy { findViewById<ListView>(R.id.list) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        App1.prepare(this)

        job = Job()

        this.glide = Glide.with(this@ActUndoHistory)

        initUI()

        load()
    }

    override fun onDestroy() {
        try {
            adapter.cursor?.close()
            adapter.cursor = null
        } catch (ex: Throwable) {
            log.trace(ex)
        }

        runBlocking { job.cancelAndJoin() }

        super.onDestroy()
    }


    private fun initUI() {
        setContentView(R.layout.act_undo_history)

        val toolBar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolBar)

        this.adapter = MyAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> adapter.onItemClick(position) }
        listView.setOnItemLongClickListener { _, _, position, _ -> adapter.onItemLongClick(position);true }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // nothing to show
        // menuInflater.inflate(R.menu.act_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {

            else -> super.onOptionsItemSelected(item)
        }

    private fun load() = launchEx("load") {
        val cursor = withContext(Dispatchers.IO) { History.cursorByCreatedAt() }
        adapter.cursor = cursor
        adapter.colIdx = History.ColIdx(cursor)
        adapter.notifyDataSetChanged()
    }

    inner class ViewHolder(root: View) {
        private val ivThumbnail1: ImageView = root.findViewById(R.id.ivThumbnail1)
        private val ivThumbnail2: ImageView = root.findViewById(R.id.ivThumbnail2)
        private val tvDesc: TextView = root.findViewById(R.id.tvDesc)

        private fun loadThumbnail1(seeds: String?) {

            glide.clear(ivThumbnail1)
            ivThumbnail1.setImageDrawable(null)

            if (seeds?.isNotEmpty() == true) {
                val girl = Girl.load(seeds)
                if (girl == null) {
                    log.w("missing girl for seed $seeds")
                } else {
                    val file = girl.largePath?.let { File(it) }
                    if (file?.exists() == true) {
                        glide.load(file).into(ivThumbnail1)
                        return
                    }

                    val thumbnail = girl.thumbnail
                    if (thumbnail.isNotEmpty()) {
                        glide.load(thumbnail).into(ivThumbnail1)
                        return
                    }
                }
            }
        }

        private fun loadThumbnail2(thumbnail: ByteArray?) {

            glide.clear(ivThumbnail2)
            ivThumbnail2.setImageDrawable(null)

            if (thumbnail?.isNotEmpty() == true) {
                glide.load(thumbnail).into(ivThumbnail2)
            }

        }


        private val History.timeString: String
            get() {
                val c = GregorianCalendar.getInstance()
                c.timeInMillis = createdAt
                val y = c.get(Calendar.YEAR)
                val m = c.get(Calendar.MONTH) + 1
                val d = c.get(Calendar.DAY_OF_MONTH)
                val h = c.get(Calendar.HOUR_OF_DAY)
                val j = c.get(Calendar.MINUTE)
                val s = c.get(Calendar.SECOND)
                return String.format("%d/%02d/%02d\n%02d:%02d:%02d", y, m, d, h, j, s)
            }

        fun bind(item: History?): ViewHolder {
            loadThumbnail1(item?.seeds)
            loadThumbnail2(item?.thumbnail)

            when (item?.event) {
                History.EVENT_GENERATE -> {
                    ivThumbnail1.alpha = 0.5f
                    ivThumbnail2.alpha = 1f
                }
                History.EVENT_CHOOSE -> {
                    ivThumbnail1.alpha = 1f
                    ivThumbnail2.alpha = 0.5f
                }
                else -> {
                    ivThumbnail1.alpha = 1f
                    ivThumbnail2.alpha = 1f
                }
            }

            tvDesc.text = item?.timeString ?: ""

            return this
        }
    }

    inner class MyAdapter : BaseAdapter() {
        var cursor: Cursor? = null
        var colIdx: History.ColIdx? = null

        override fun getCount(): Int = cursor?.count ?: 0

        private fun gen(idx: Int): History? {
            val cursor = cursor ?: return null
            return if (idx in 0 until cursor.count) {
                cursor.moveToPosition(idx)
                History.load(colIdx, cursor)
            } else {
                null
            }
        }

        override fun getItemId(position: Int): Long = gen(position)?.id ?: -1L

        override fun getItem(position: Int): Any? = gen(position)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = gen(position)
            return convertView?.apply {
                (tag as ViewHolder).bind(item)
            } ?: layoutInflater.inflate(R.layout.lv_undo, parent, false).apply {
                tag = ViewHolder(this).bind(item)
            }
        }

        fun onItemClick(position: Int) {
            val item = gen(position) ?: return
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra("gid", item.id)
            })
            finish()
        }

        fun onItemLongClick(position: Int) {
            ActionsDialog()
                .addAction("delete") {
                    gen(position)?.let {
                        it.delete()
                        load()
                    }
                }
                .show(this@ActUndoHistory)
        }
    }

    private fun launchEx(caption: String, block: suspend () -> Unit) = launch {
        try {
            block()
        } catch (ex: Throwable) {
            log.trace(ex)
            showToast(this@ActUndoHistory, ex, "$caption failed.")
        }
    }
}