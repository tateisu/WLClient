package jp.juggler.util

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val MEDIA_TYPE_FORM_URL_ENCODED : MediaType =
	"application/x-www-form-urlencoded".toMediaType()

val MEDIA_TYPE_JSON : MediaType =
	"application/json;charset=UTF-8".toMediaType()

fun String.toRequestBody(mediaType : MediaType = MEDIA_TYPE_FORM_URL_ENCODED) : RequestBody =
	this.toRequestBody(mediaType)

fun RequestBody.toPost() : Request.Builder =
	Request.Builder().post(this)

fun RequestBody.toPut() :Request.Builder =
	Request.Builder().put(this)

// fun RequestBody.toDelete():Request.Builder  =
// Request.Builder().delete(this)

fun RequestBody.toPatch() :Request.Builder =
	Request.Builder().patch(this)

fun RequestBody.toRequest(methodArg : String) :Request.Builder =
	Request.Builder().method(methodArg, this)

////////////////////////////////////////////////////

const val OKHTTP_STACK_RECORDER_PROPERTY = "ru.gildor.coroutines.okhttp.stackrecorder"
const val OKHTTP_STACK_RECORDER_ON = "on"
const val OKHTTP_STACK_RECORDER_OFF = "off"

@JvmField
val isRecordStack = when (System.getProperty(OKHTTP_STACK_RECORDER_PROPERTY)) {
	OKHTTP_STACK_RECORDER_ON -> true
	OKHTTP_STACK_RECORDER_OFF, null, "" -> false
	else -> error("System property '$OKHTTP_STACK_RECORDER_PROPERTY' has unrecognized value '${System.getProperty(OKHTTP_STACK_RECORDER_PROPERTY)}'")
}

suspend fun Call.await(recordStack: Boolean = isRecordStack): Response {
	val callStack = if (recordStack) {
		IOException().apply {
			// Remove unnecessary lines from stacktrace
			// This doesn't remove await$default, but better than nothing
			stackTrace = stackTrace.copyOfRange(1, stackTrace.size)
		}
	} else {
		null
	}
	return suspendCancellableCoroutine { continuation ->
		enqueue(object : Callback {
			override fun onResponse(call: Call, response: Response) {
				continuation.resume(response)
			}

			override fun onFailure(call: Call, e: IOException) {
				// Don't bother with resuming the continuation if it is already cancelled.
				if (continuation.isCancelled) return
				callStack?.initCause(e)
				continuation.resumeWithException(callStack ?: e)
			}
		})

		continuation.invokeOnCancellation {
			try {
				cancel()
			} catch (ex: Throwable) {
				//Ignore cancel exception
			}
		}
	}
}
