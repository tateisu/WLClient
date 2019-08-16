package jp.juggler.wlclient

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import jp.juggler.util.LogCategory
import jp.juggler.wlclient.table.Girl
import jp.juggler.wlclient.table.History
import kotlinx.coroutines.*
import java.io.File
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

        setContentView(R.layout.act_undo_history)
        this.adapter = MyAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> adapter.onItemClick(position) }

        this.glide = Glide.with(this@ActUndoHistory)

        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        runBlocking { job.cancelAndJoin() }
        adapter.cursor?.close()
    }

    @SuppressLint("Recycle")
    private fun load() = launch {
        try {
            val cursor = withContext(Dispatchers.IO) { History.cursorByCreatedAt() }
            adapter.cursor = cursor
            adapter.colIdx = History.ColIdx(cursor)
            adapter.notifyDataSetChanged()
        } catch (ex: Throwable) {
            log.trace(ex)
            log.e(ex, "load failed.")
        }
    }


    inner class ViewHolder(root: View) {
        private val ivThumbnail1: ImageView = root.findViewById(R.id.ivThumbnail1)
        private val ivThumbnail2: ImageView = root.findViewById(R.id.ivThumbnail2)
        private val tvDesc: TextView = root.findViewById(R.id.tvDesc)

        private fun loadThumbnail1(seeds: String?) {

            glide.clear(ivThumbnail1)
            ivThumbnail1.setImageDrawable(null)

            if (seeds?.isNotEmpty() == true) {
                Girl.load(seeds)?.let { girl ->
                    val file = girl.largePath?.let{ File(it) }
                    if (file?.exists() == true ) {
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

        fun bind(item: History?): ViewHolder {
            loadThumbnail1(item?.seeds)
            loadThumbnail2(item?.thumbnail)

            when(item?.event){
                History.EVENT_GENERATE ->{
                    ivThumbnail1.alpha = 0.5f
                    ivThumbnail2.alpha = 1f
                }
                History.EVENT_CHOOSE ->{
                    ivThumbnail1.alpha = 1f
                    ivThumbnail2.alpha = 0.5f
                }
                else ->{
                    ivThumbnail1.alpha = 1f
                    ivThumbnail2.alpha = 1f
                }
            }

            tvDesc.text = item?.timeString ?:""

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
    }
}