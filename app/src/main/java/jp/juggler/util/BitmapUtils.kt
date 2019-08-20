package jp.juggler.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream


// use bitmap and recycle after it.
inline fun <T : Any?> Bitmap.use(block: (bitmap: Bitmap) -> T): T =
    try {
        block(this)
    } finally {
        this.recycle()
    }

// PNG => Bitmap. don't forgot to call Bitmap.recycle() !!
fun ByteArray.toBitmap(): Bitmap? =
    BitmapFactory.decodeStream(ByteArrayInputStream(this))

// Bitmap => PNG.
fun Bitmap.toPng(): ByteArray =
    ByteArrayOutputStream()
        .also { this.compress(Bitmap.CompressFormat.PNG, 100, it) }
        .toByteArray()
