package jp.juggler.wlclient.table

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.*
import jp.juggler.util.*
import jp.juggler.wlclient.App1

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
    // last generation id for EVENT_CHOOSE, 0L for EVENT_GENERATE
    val generationId: Long = 0L
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
        private const val COL_GENNERATION_ID = "id_thumbnails" // historical reason, col name not match

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
,$COL_GENNERATION_ID integer not null
)"""
            )

            db.execSQL(
                "create unique index if not exists ${table}_create on $table($COL_CREATED_AT,$COL_EVENT)"
            )
            db.execSQL(
                "create index if not exists ${table}_seeds on $table($COL_SEEDS,$COL_CREATED_AT)"
            )
        }

        override fun onDBUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            log.d("onDBUpgrade!")
            if (oldVersion < 2 && newVersion >= 2) {
                try {
                    db.execSQL("alter table $table add column $COL_GENNERATION_ID integer default 0")
                } catch (ex: Throwable) {
                    log.trace(ex)
                }
            }
            if (oldVersion < 3 && newVersion >= 3) {
                db.execSQL(
                    "create index if not exists ${table}_seeds on $table($COL_SEEDS,$COL_CREATED_AT)"
                )
            }
        }

        fun create(event: Int, step: Int, seeds: String?, generationId: Long = 0L): History {
            return History(
                createdAt = System.currentTimeMillis(),
                seeds = seeds,
                step = step,
                event = event,
                generationId = generationId
            ).apply {
                try {
                    id = App1.database.insertOrThrow(table, null, ContentValues().apply {
                        put(COL_CREATED_AT, createdAt)
                        put(COL_SEEDS, seeds)
                        put(COL_STEP, step)
                        put(COL_EVENT, event)
                        put(COL_GENNERATION_ID, generationId)
                    })
                    log.d("create: id=$id,generationId=$generationId,seeds=$seeds")
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
                event = cursor.getInt(colIdx.idxEvent),
                generationId = cursor.getLong(colIdx.idxGenerationId)
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

        @Suppress("RedundantSuspendModifier")
        suspend fun loadById(
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

        fun loadBySeed(seeds: String): History? {
            App1.database.query(
                table,
                null,
                "$COL_SEEDS =?",
                arrayOf(seeds),
                null,
                null,
                "$COL_CREATED_AT desc",
                "1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return load(null, cursor)
                }
            }
            return null
        }

        private const val THUMBNAIL_SIZE = 200

    }

    class ColIdx(cursor: Cursor) {
        val idxId = cursor.getColumnIndex(COL_ID)
        val idxSeed = cursor.getColumnIndex(COL_SEEDS)
        val idxStep = cursor.getColumnIndex(COL_STEP)
        val idxCreatedAt = cursor.getColumnIndex(COL_CREATED_AT)
        val idxThumbnail = cursor.getColumnIndex(COL_THUMBNAIL)
        val idxEvent = cursor.getColumnIndex(COL_EVENT)
        val idxGenerationId = cursor.getColumnIndex(COL_GENNERATION_ID)
    }

    fun saveThumbnail(thumbnailData: List<ByteArray>?) {
        thumbnailData ?: return

        Bitmap.createBitmap(THUMBNAIL_SIZE, THUMBNAIL_SIZE, Bitmap.Config.ARGB_8888)?.use { dst ->

            val srcRect = Rect(0, 0, 0, 0)
            val dstRect = Rect(0, 0, THUMBNAIL_SIZE, THUMBNAIL_SIZE)

            val paint = Paint().apply {
                isFilterBitmap = true
                color = Color.WHITE
            }

            val canvas = Canvas(dst)

            // fill background
            canvas.drawRect(dstRect, paint)

            // copy thumbnails
            var idx = 0
            imageLoop@ for (y in 0 until 4) {
                dstRect.top = (THUMBNAIL_SIZE * y / 4)
                dstRect.bottom = (THUMBNAIL_SIZE * (y + 1) / 4)
                for (x in 0 until 4) {
                    dstRect.left = (THUMBNAIL_SIZE * x / 4)
                    dstRect.right = (THUMBNAIL_SIZE * (x + 1) / 4)

                    if (idx >= thumbnailData.size) break@imageLoop

                    thumbnailData[idx++].toBitmap()?.use { src ->
                        srcRect.right = src.width
                        srcRect.bottom = src.height
                        canvas.drawBitmap(src, srcRect, dstRect, paint)
                    }
                }
            }

            this.thumbnail = dst.toPng()

            App1.database.update(
                table,
                ContentValues().apply { put(COL_THUMBNAIL, thumbnail) },
                "$COL_ID=?",
                arrayOf(id.toString())
            )
        }
    }

    fun delete() {
        App1.database.delete(table,"$COL_ID=?",arrayOf(id.toString()))
    }
}