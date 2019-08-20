package jp.juggler.wlclient.table

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import androidx.appcompat.app.AppCompatActivity
import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import jp.juggler.util.*
import jp.juggler.wlclient.App1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.*

enum class MarkType(val id: Int, val range: IntRange) {
    Pose(1, 0 until 4),
    Detail(2, 4 until 12),
    Color(3, 12 until 16),
    Noise(4, 16 until 18)
    ;

    fun splitPart(seeds: String) = seeds.toJsonArray().slice(range)

    fun savePart(activity: AppCompatActivity, seeds: String) {
        try {
            val strMarked = JsonArray(splitPart(seeds)).toJsonString()
            val key = "Mark_$name"
            App1.pref.edit().putString(key, strMarked).apply()
            showToast(activity, false, "$strMarked\nwas marked as $name")
        }catch(ex:Throwable) {
            showToast(activity, ex, "mark failed.")
        }
    }

    fun sameWith(seeds: String): Boolean {
        val strMarked = JsonArray(splitPart(seeds)).toJsonString()
        val key = "Mark_$name"
        return strMarked == App1.pref.getString(key,null)
    }
}

class Girl(
    var id: Long = -1L,
    val seeds: String,
    val thumbnail: ByteArray,
    var largePath: String? = null,
    val createdAt: Long,

    val generationId: Long,

    // sort order in a generation. just used for query.
    // val generationSub: Int,

    var chooseAt: Long = 0
) {

    companion object : TableCompanion {
        private val log = LogCategory("Girl")

        private var lastLargeImageEnd = 0L

        private const val table = "girl"

        private const val COL_ID = "_id"
        private const val COL_SEEDS = "seeds"
        private const val COL_THUMBNAIL = "thumbnail"
        private const val COL_LARGE_PATH = "large_path"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_GENERATION_ID = "g_id"
        private const val COL_GENERATION_SUB = "g_sub"
        private const val COL_CHOOSE_AT = "choose_at"

        override fun onDBCreate(db: SQLiteDatabase) {
            log.d("onDBCreate!")

            db.execSQL(
                """create table if not exists $table
($COL_ID INTEGER PRIMARY KEY
,$COL_SEEDS text not null 
,$COL_THUMBNAIL blob not null 
,$COL_LARGE_PATH text
,$COL_CREATED_AT integer not null
,$COL_GENERATION_ID integer not null
,$COL_GENERATION_SUB integer not null
,$COL_CHOOSE_AT  integer
)"""
            )

            db.execSQL(
                "create index if not exists ${table}_seeds on $table($COL_SEEDS)"
            )
            db.execSQL(
                "create unique index if not exists ${table}_generation on $table($COL_GENERATION_ID,$COL_GENERATION_SUB)"
            )
            db.execSQL(
                "create unique index if not exists ${table}_choose on $table($COL_CHOOSE_AT)"
            )
        }

        override fun onDBUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            log.d("onDBUpgrade!")
//            if( oldVersion < 1 && newVersion >= 1){
//                //
//            }
        }

        private fun fromCursor(cursor: Cursor, colIdx: ColIdx = ColIdx(cursor)) =
            Girl(
                id = cursor.getLong(colIdx.idxId),
                seeds = cursor.getString(colIdx.idxSeeds),
                thumbnail = cursor.getBlob(colIdx.idxThumbnail),
                createdAt = cursor.getLong(colIdx.idxCreatedAt),
                generationId = cursor.getLong(colIdx.idxGenerationId),
                largePath = cursor.getStringOrNull(colIdx.idxLargePath),
                chooseAt = cursor.getLong(colIdx.idxChooseAt)
            )


        private fun parse(generationId: Long, generationSub: Int, src: JsonObject): Girl {
            return Girl(
                seeds = (src["seeds"] as JsonArray<*>).toJsonString(),
                thumbnail = Base64.decode(src.string("image") ?: "", 0),
                createdAt = System.currentTimeMillis(),
                generationId = generationId
            ).apply {
                id = App1.database.insertOrThrow(table, null, ContentValues().apply {
                    put(COL_SEEDS, seeds)
                    put(COL_THUMBNAIL, thumbnail)
                    put(COL_CREATED_AT, createdAt)
                    put(COL_GENERATION_ID, generationId)
                    put(COL_GENERATION_SUB, generationSub)
                })
            }
        }

        fun load(seeds: String?): Girl? {
            seeds ?: return null
            try {
                App1.database.query(table, null, "$COL_SEEDS=?", arrayOf(seeds), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) return fromCursor(cursor)
                    return null // 0 rows
                }
            } catch (ex: Throwable) {
                log.trace(ex)
            }
            log.e("load failed. seeds=$seeds")
            return null
        }

        @Suppress("RedundantSuspendModifier")
        suspend fun scanSeeds(callback: (seeds: String) -> Unit) {
            App1.database.query(
                table,
                arrayOf(COL_SEEDS),
                null,
                null,
                null,
                null,
                null
            ).use { cursor ->
                val idxSeeds = cursor.getColumnIndex(COL_SEEDS)
                while (cursor.moveToNext()) {
                    callback(cursor.getString(idxSeeds))
                }
            }
        }


        @Suppress("RedundantSuspendModifier")
        suspend fun loadByHistoryId(gid: Long) = ArrayList<Girl>().apply {
            App1.database.query(
                table,
                null,
                "$COL_GENERATION_ID=?",
                arrayOf(gid.toString()),
                null,
                null,
                "$COL_GENERATION_SUB asc"
            )?.use { cursor ->
                val colIdx = ColIdx(cursor)
                while (cursor.moveToNext()) {
                    add(fromCursor(cursor, colIdx))
                }
            }
        }

        suspend fun generate(dataString: String, history: History): List<Girl> {
            val request = App1.requestBase
                .url("https://api.waifulabs.com/generate")
                .post(dataString.toRequestBody(mediaType = MEDIA_TYPE_JSON))
                .build()

            val res = App1.okHttpClient.newCall(request).await()

            val content = res.body?.string()

            if (!res.isSuccessful)
                error("HTTP ${res.code} ${res.message} $content")

            val root = content!!.toJsonObject()

            val list = root.array<JsonObject>("newGirls")
            if (list == null || list.isEmpty())
                error("missing girl list.")

            return list.mapIndexed { idx, jsonData -> parse(history.id, idx, jsonData) }
        }

        private fun saveImageFile(context: Context, data: ByteArray, createdAt: Long) :File{
            val c = GregorianCalendar.getInstance()
            c.timeInMillis = createdAt
            val y = c.get(Calendar.YEAR)
            val m = c.get(Calendar.MONTH) + 1
            val d = c.get(Calendar.DAY_OF_MONTH)
            val h = c.get(Calendar.HOUR_OF_DAY)
            val j = c.get(Calendar.MINUTE)
            val s = c.get(Calendar.SECOND)

            var i = 1
            var file: File
            do {
                val name = if (i == 1) {
                    String.format("WaifuLabs-%d%02d%02dT%02d%02d%02d.png", y, m, d, h, j, s)
                } else {
                    String.format("WaifuLabs-%d%02d%02dT%02d%02d%02d-%d.png", y, m, d, h, j, s, i)
                }
                file = File(context.getExternalFilesDir(null), name)
                ++i
            } while (file.exists())
            FileOutputStream(file).use { it.write(data) }
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
            return file
        }

        private suspend fun generateBig(seeds: String, step: Int, progress: ProgressRunner? = null): ByteArray? {
            val now = SystemClock.elapsedRealtime()
            val remain = lastLargeImageEnd + 3000L - now
            if (remain > 0L) {
                progress?.publishApiProgress(
                    String.format(
                        "wait %.2f second(s) to avoid rate limit…",
                        remain.toFloat() / 1000f
                    )
                )
                delay(remain)
            }

            lastLargeImageEnd = SystemClock.elapsedRealtime()

            try {
                progress?.publishApiProgress("acquire large image…")
                var dataString = JsonObject().apply {
                    put("step", step)
                    put("size", 512)
                    put("currentGirl", seeds.toJsonAny())
                }.toJsonString()

                val request = App1.requestBase
                    .url("https://api.waifulabs.com/generate_big")
                    .post(dataString.toRequestBody(mediaType = MEDIA_TYPE_JSON))
                    .build()

                val res = App1.okHttpClient.newCall(request).await()

                if (!res.isSuccessful) error("HTTP ${res.code} ${res.message}")

                val root = when (val content = res.body?.string()) {
                    null -> error("missing response body")
                    else -> content.toJsonObject()
                }

                dataString = root.string("girl")?.notEmpty() ?: error("missing girl data.")
                return Base64.decode(dataString, 0)
            }finally{
                lastLargeImageEnd =  SystemClock.elapsedRealtime()
            }

        }

        suspend fun generateFromSeeds(context:Context,history: History,progress: ProgressRunner? = null): Girl? {
            val createdAt = System.currentTimeMillis()
            val binary = generateBig(history.seeds !!, history.step, progress) ?: error("can't generate image")

            val thumbnail = binary.toBitmap()?.use { src ->
                val size = 200
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)?.use { dst ->

                    val srcRect = Rect(0, 0, 0, 0)
                    val dstRect = Rect(0, 0, size, size)

                    val paint = Paint().apply {
                        isFilterBitmap = true
                    }

                    val canvas = Canvas(dst)
                    srcRect.right = src.width
                    srcRect.bottom = src.height
                    canvas.drawBitmap(src, srcRect, dstRect, paint)

                    dst.toPng()
                }
            }?: error("can't generate thumbnail")

            return Girl(
                seeds = history.seeds,
                thumbnail = thumbnail,
                createdAt = System.currentTimeMillis(),
                generationId = history.id
            ).apply {
                this.largePath = saveImageFile(context,binary,createdAt).absolutePath
                this.chooseAt = System.currentTimeMillis()
                log.d("generateFromSeeds: gId=$generationId, gSub=0, seeds=$seeds")
                id = App1.database.insertOrThrow(table, null, ContentValues().apply {
                    put(COL_SEEDS, seeds)
                    put(COL_THUMBNAIL, thumbnail)
                    put(COL_CREATED_AT, createdAt)
                    put(COL_GENERATION_ID, generationId)
                    put(COL_GENERATION_SUB, 0)
                    put(COL_LARGE_PATH,largePath)
                    put(COL_CHOOSE_AT, chooseAt)
                })
            }
        }
    }

    class ColIdx(cursor: Cursor) {
        val idxId = cursor.getColumnIndex(COL_ID)
        val idxSeeds = cursor.getColumnIndex(COL_SEEDS)
        val idxThumbnail = cursor.getColumnIndex(COL_THUMBNAIL)
        val idxCreatedAt = cursor.getColumnIndex(COL_CREATED_AT)
        val idxGenerationId = cursor.getColumnIndex(COL_GENERATION_ID)
        val idxLargePath = cursor.getColumnIndex(COL_LARGE_PATH)
        val idxChooseAt = cursor.getColumnIndex(COL_CHOOSE_AT)
    }

    val timeString: String
        get() {
            val c = GregorianCalendar.getInstance()
            c.timeInMillis = createdAt
            val y = c.get(Calendar.YEAR)
            val m = c.get(Calendar.MONTH) + 1
            val d = c.get(Calendar.DAY_OF_MONTH)
            val h = c.get(Calendar.HOUR_OF_DAY)
            val j = c.get(Calendar.MINUTE)
            val s = c.get(Calendar.SECOND)
            return String.format("%d/%02d/%02d-%02d:%02d:%02d", y, m, d, h, j, s)
        }

    private fun saveFilePath(file:File?){
        file ?: return
        val cv = ContentValues().apply{
            val path = file.absolutePath
            largePath = path
            put(COL_LARGE_PATH, path)
            if (chooseAt == 0L) {
                val t = System.currentTimeMillis()
                chooseAt = t
                put(COL_CHOOSE_AT, t)
            }
        }
        App1.database.update(table, cv, "$COL_ID=?", arrayOf(id.toString()))
    }

    // download large image if not yet prepared.
    // return true if image was prepared.
    suspend fun prepareLargeImage(context: Context, step: Int, progress: ProgressRunner? = null): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val path = largePath
                if (path?.isNotEmpty() == true && File(path).exists()) return@withContext true

                generateBig(seeds, step, progress)?.let { binary ->
                    if (binary.isEmpty() ) return@let
                    progress?.publishApiProgress("save to file…")
                    saveFilePath( saveImageFile(context,binary,createdAt))
                }

                true
            }
        } catch (ex: Throwable) {
            log.trace(ex)
            showToast(context, ex, "prepareLargeImage failed.")
            false
        }

    }
}