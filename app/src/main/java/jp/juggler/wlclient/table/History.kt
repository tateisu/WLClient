package jp.juggler.wlclient.table

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.*
import jp.juggler.util.LogCategory
import jp.juggler.util.getByteArrayOrNull
import jp.juggler.util.getStringOrNull
import jp.juggler.wlclient.App1
import java.io.ByteArrayOutputStream

class History(
    // primary key
    var id: Long = -1L,
    // creation time
    val createdAt: Long,
    // trigger to save history, EVENT_GENERATE or EVENT_CHOOSE
    val event: Int,
    // seeds of current girl. may null if trigger is step0 generation.
    val seeds: String? = null,
    // last generate step
    val step: Int,
    // thumbnail of generation.
    var thumbnail: ByteArray? = null,
    // history id suppliment
    val idForThumbnails: Long = 0L
) {

    companion object : TableCompanion {
        private val log = LogCategory("History")

        const val table = "history"

        private const val COL_ID = "_id"
        private const val COL_SEEDS = "seed"
        private const val COL_STEP = "step"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_THUMBNAIL = "thumbnail"
        private const val COL_EVENT = "event"
        private const val COL_ID_THUMBNAILS = "id_thumbnails"

        const val EVENT_GENERATE = 1
        const val EVENT_CHOOSE = 2

        override fun onDBCreate(db: SQLiteDatabase) {
            log.d("onDBCreate!")

            db.execSQL(
                """create table if not exists $table
($COL_ID INTEGER PRIMARY KEY
,$COL_SEEDS text  
,$COL_CREATED_AT integer not null
,$COL_THUMBNAIL blob 
,$COL_STEP integer not null
,$COL_EVENT integer not null
,$COL_ID_THUMBNAILS integer not null
)"""
            )

            db.execSQL(
                "create unique index if not exists ${table}_create on $table($COL_CREATED_AT,$COL_EVENT)"
            )
        }

        override fun onDBUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            log.d("onDBUpgrade!")
            if (oldVersion < 2 && newVersion >= 2) {
                try {
                    db.execSQL("alter table $table add column $COL_ID_THUMBNAILS integer default 0")
                } catch (ex: Throwable) {
                    log.trace(ex)
                }
            }
        }

        fun create(event: Int, step: Int, seeds: String?, idForThumbnails: Long = 0L): History {
            return History(
                createdAt = System.currentTimeMillis(),
                seeds = seeds,
                step = step,
                event = event,
                idForThumbnails = idForThumbnails
            ).apply {
                try {
                    val cv = ContentValues()
                    cv.put(COL_CREATED_AT, this.createdAt)
                    cv.put(COL_SEEDS, this.seeds)
                    cv.put(COL_STEP, this.step)
                    cv.put(COL_EVENT, this.event)
                    cv.put(COL_ID_THUMBNAILS, this.idForThumbnails)
                    id = App1.database.insert(table, null, cv)
                    log.d("generation $id created.")
                } catch (ex: Throwable) {
                    log.e(ex, "save failed.")
                }
            }
        }

        fun load(colIdxArg: ColIdx?, cursor: Cursor): History? {
            val colIdx = colIdxArg ?: ColIdx(cursor)
            return History(
                id = cursor.getLong(colIdx.idxId),
                seeds = cursor.getStringOrNull(colIdx.idxSeed),
                step = cursor.getInt(colIdx.idxStep),
                createdAt = cursor.getLong(colIdx.idxCreatedAt),
                thumbnail = cursor.getByteArrayOrNull(colIdx.idxThumbnail),
                event = cursor.getInt(colIdx.idxEvent)
            )
        }


        fun cursorByCreatedAt(): Cursor =
            App1.database.query(
                table,
                null,
                null,
                null,
                null,
                null,
                "$COL_CREATED_AT desc"
            )

        fun loadById(
            gid: Long,
            condition: String = "=",
            order: String = "asc"
        ): History? {
            App1.database.query(
                table,
                null,
                "$COL_ID $condition ?",
                arrayOf(gid.toString()),
                null,
                null,
                "$COL_ID $order",
                "1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return load(null, cursor)
                }
            }
            return null
        }


    }

    class ColIdx(cursor: Cursor) {
        val idxId = cursor.getColumnIndex(COL_ID)
        val idxSeed = cursor.getColumnIndex(COL_SEEDS)
        val idxStep = cursor.getColumnIndex(COL_STEP)
        val idxCreatedAt = cursor.getColumnIndex(COL_CREATED_AT)
        val idxThumbnail = cursor.getColumnIndex(COL_THUMBNAIL)
        val idxEvent = cursor.getColumnIndex(COL_EVENT)
    }

    fun saveThumbnail(thumbnailBitmaps: Array<Bitmap?>) {
        val width = 200
        val bitmap = Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val srcRect = Rect(0, 0, 0, 0)
        val dstRect = Rect(0, 0, width, width)
        // fill background
        paint.color = Color.WHITE
        canvas.drawRect(dstRect, paint)
        // copy thumbnails
        paint.isFilterBitmap = true
        var idx = 0
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                thumbnailBitmaps[idx++]?.let {
                    srcRect.right = it.width
                    srcRect.bottom = it.height
                    dstRect.left = (width * x / 4)
                    dstRect.right = (width * (x + 1) / 4)
                    dstRect.top = (width * y / 4)
                    dstRect.bottom = (width * (y + 1) / 4)
                    canvas.drawBitmap(it, srcRect, dstRect, paint)
                }
            }
        }

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        this.thumbnail = stream.toByteArray()

        try {
            val cv = ContentValues()
            cv.put(COL_THUMBNAIL, thumbnail)
            App1.database.update(table, cv, "$COL_ID=?", arrayOf(id.toString()))
        } catch (ex: Throwable) {
            log.e(ex, "save failed.")
        }
    }

}