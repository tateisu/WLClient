package jp.juggler.util

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.text.NumberFormat

@Suppress("DEPRECATION", "MemberVisibilityCanPrivate")
class ProgressRunner constructor(
    context : Context,
    private val progress_style : Int = PROGRESS_SPINNER,
    private val progressSetupCallback : (progress : ProgressDialogEx) -> Unit = { _ -> }
){
    companion object {

        const val PROGRESS_NONE = - 1
        const val PROGRESS_SPINNER = ProgressDialogEx.STYLE_SPINNER
        @Suppress("unused")
        const val PROGRESS_HORIZONTAL = ProgressDialogEx.STYLE_HORIZONTAL

        private val percent_format : NumberFormat by lazy {
            val v = NumberFormat.getPercentInstance()
            v.maximumFractionDigits = 0
            v
        }
    }

    private class ProgressInfo {

        // HORIZONTALスタイルの場合、初期メッセージがないと後からメッセージを指定しても表示されない
        internal var message = " "
        internal var isIndeterminate = true
        internal var value = 0
        internal var max = 1
    }

    private val handler = Handler()
    private val info = ProgressInfo()
    private var progress : ProgressDialogEx? = null
    private var progressPrefix : String? = null
    private var canceller: Job? = null

    private val refContext : WeakReference<Context> = WeakReference(context)

    private var lastMessageShown : Long = 0

    private val procProgressMessage = object : Runnable {
        override fun run() {
            synchronized(this) {
                if(progress?.isShowing == true) {
                    showProgressMessage()
                }
            }
        }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    var isActive : Boolean = true
        private set

    suspend fun <T:Any?> run(callback : suspend (runner:ProgressRunner)->T) :T{
        openProgress()
        return try{
            val job = Job()
            this.canceller = job
            withContext(job){
                callback(this@ProgressRunner)
            }
        }finally{
            this.canceller = null
            isActive = false
            dismissProgress()
        }
    }

    fun progressPrefix(s : String) : ProgressRunner {
        this.progressPrefix = s
        return this
    }

    //////////////////////////////////////////////////////
    // implements TootApiClient.Callback


    //////////////////////////////////////////////////////
    // ProgressDialog

    private fun openProgress() {
        // open progress
        if(progress_style != PROGRESS_NONE) {
            val context = refContext.get()
            if(context != null && context is Activity) {
                val progress = ProgressDialogEx(context)
                this.progress = progress
                progress.setCancelable(true)
                progress.setOnCancelListener { canceller?.cancel() }
                progress.setProgressStyle(progress_style)
                progressSetupCallback(progress)
                showProgressMessage()
                progress.show()
            }
        }
    }

    // ダイアログを閉じる
    private fun dismissProgress() {
        progress?.dismissSafe()
        progress = null
    }

    // ダイアログのメッセージを更新する
    // 初期化時とメッセージ更新時に呼ばれる
    private fun showProgressMessage() {
        val progress = this.progress ?: return

        val message = info.message.trim { it <= ' ' }
        val progressPrefix = this.progressPrefix
        progress.setMessage(
            if(progressPrefix == null || progressPrefix.isEmpty()) {
                message
            } else if(message.isEmpty()) {
                progressPrefix
            } else {
                "$progressPrefix\n$message"
            }
        )

        progress.isIndeterminate = info.isIndeterminate
        if(info.isIndeterminate) {
            progress.setProgressNumberFormat(null)
            progress.setProgressPercentFormat(null)
        } else {
            progress.progress = info.value
            progress.max = info.max
            progress.setProgressNumberFormat("%1$,d / %2$,d")
            progress.setProgressPercentFormat(percent_format)
        }

        lastMessageShown = SystemClock.elapsedRealtime()
    }

    // 少し後にダイアログのメッセージを更新する
    // あまり頻繁に更新せず、しかし繰り返し呼ばれ続けても時々は更新したい
    // どのスレッドから呼ばれるか分からない
    private fun delayProgressMessage() {
        var wait = 100L + lastMessageShown - SystemClock.elapsedRealtime()
        wait = if(wait < 0L) 0L else if(wait > 100L) 100L else wait

        synchronized(this) {
            handler.removeCallbacks(procProgressMessage)
            handler.postDelayed(procProgressMessage, wait)
        }
    }

    fun publishApiProgress(s : String) {
        synchronized(this) {
            info.message = s
            info.isIndeterminate = true
        }
        delayProgressMessage()
    }

    @Suppress("unused")
    fun publishApiProgressRatio(value : Int, max : Int) {
        synchronized(this) {
            info.isIndeterminate = false
            info.value = value
            info.max = max
        }
        delayProgressMessage()
    }
}
