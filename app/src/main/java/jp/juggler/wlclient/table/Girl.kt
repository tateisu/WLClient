package jp.juggler.wlclient.table

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Base64
import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import jp.juggler.util.*
import jp.juggler.wlclient.App1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.*

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

        private fun parse(generationId: Long, generationSub: Int, src: JsonObject): Girl {
            return Girl(
                seeds = (src["seeds"] as JsonArray<*>).toJsonString(),
                thumbnail = Base64.decode(src.string("image") ?: "", 0),
                createdAt = System.currentTimeMillis(),
                generationId = generationId
            ).apply {
                try {
                    id = App1.database.insert(table, null, ContentValues().apply {
                        put(COL_SEEDS, seeds)
                        put(COL_THUMBNAIL, thumbnail)
                        put(COL_CREATED_AT, createdAt)
                        put(COL_GENERATION_ID, generationId)
                        put(COL_GENERATION_SUB, generationSub)
                    })
                } catch (ex: Throwable) {
                    log.trace(ex)
                    log.e(ex, "save failed.")
                }
            }
        }

        fun load(seeds: String?): Girl? {
            seeds ?: return null
            App1.database.query(table, null, "$COL_SEEDS=?", arrayOf(seeds), null, null, null)?.use { cursor ->
                return if (!cursor.moveToFirst()) {
                    log.e("load failed. seeds=$seeds")
                    null
                } else Girl(
                    id = cursor.getLong(COL_ID),
                    seeds = cursor.getString(COL_SEEDS),
                    thumbnail = cursor.getByteArray(COL_THUMBNAIL),
                    createdAt = cursor.getLong(COL_CREATED_AT),
                    generationId = cursor.getLong(COL_GENERATION_ID),
                    largePath = cursor.getStringOrNull(COL_LARGE_PATH),
                    chooseAt = cursor.getLong(COL_CHOOSE_AT)
                )
            }
            return null
        }

        fun loadByHistoryId(gid: Long) = ArrayList<Girl>().apply {
            App1.database.query(
                table,
                null,
                "$COL_GENERATION_ID=?",
                arrayOf(gid.toString()),
                null,
                null,
                "$COL_GENERATION_SUB asc"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    add(
                        Girl(
                            id = cursor.getLong(COL_ID),
                            seeds = cursor.getString(COL_SEEDS),
                            thumbnail = cursor.getByteArray(COL_THUMBNAIL),
                            createdAt = cursor.getLong(COL_CREATED_AT),
                            generationId = cursor.getLong(COL_GENERATION_ID),
                            largePath = cursor.getStringOrNull(COL_LARGE_PATH),
                            chooseAt = cursor.getLong(COL_CHOOSE_AT)
                        )
                    )
                }
            }
        }

        suspend fun generate(dataString: String, history: History): List<Girl> {
            val request = Request.Builder()
                .url("https://api.waifulabs.com/generate")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/76.0.3809.100 Safari/537.36"
                )
                .header("Referer", "https://waifulabs.com/")
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
    }

    private fun saveLargeData(context: Context, dataString: String) {

        val data = Base64.decode(dataString, 0)
        if (data?.isEmpty() != false) return

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

        this.largePath = file.absolutePath
        val cv = ContentValues()
        cv.put(COL_LARGE_PATH, largePath)
        if (this.chooseAt == 0L) {
            this.chooseAt = System.currentTimeMillis()
            cv.put(COL_CHOOSE_AT, chooseAt)
        }
        App1.database.update(table, cv, "$COL_ID=?", arrayOf(id.toString()))

        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
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

    // download large image if not yet prepared.
    // return true if image was prepared.
    suspend fun prepareLargeImage(context: Context, step: Int): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val path = largePath
                if (path?.isNotEmpty() == true && File(path).exists()) return@withContext true

                delay(3000L)

                val dataString = JsonObject().apply {
                    put("step", step)
                    put("size", 512)
                    put("currentGirl", seeds.toJsonAny())
                }.toJsonString()

                val request = Request.Builder()
                    .url("https://api.waifulabs.com/generate_big")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/76.0.3809.100 Safari/537.36"
                    )
                    .header("Referer", "https://waifulabs.com/")
                    .post(dataString.toRequestBody(mediaType = MEDIA_TYPE_JSON))
                    .build()

                val res = App1.okHttpClient.newCall(request).await()

                if (!res.isSuccessful)
                    error("HTTP ${res.code} ${res.message}")

                val root = when (val content = res.body?.string()) {
                    null -> error("missing response body")
                    else -> content.toJsonObject()
                }

                saveLargeData(
                    context,
                    root.string("girl")?.notEmpty() ?: error("missing girl data.")
                )

                true
            }
        } catch (ex: Throwable) {
            log.trace(ex)
            showToast(context, ex, "prepareLargeImage failed.")
            false
        }
    }


}