package jp.juggler.wlclient

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import jp.juggler.util.LogCategory
import jp.juggler.util.ProgressRunner
import jp.juggler.util.dismissSafe
import jp.juggler.util.showToast
import jp.juggler.wlclient.table.Girl
import java.io.File


suspend fun Girl.contextMenu(activity: AppCompatActivity, step: Int) {
    DlgContextMenu(activity, this, step).show()
}

// open context menu, view,share,share file path..
class DlgContextMenu(
    private val activity: AppCompatActivity,
    private val girl: Girl,
    private val step: Int
) {
    companion object {
        private val log = LogCategory("DlgContextMenu")

    }

    private val density: Float = activity.resources.displayMetrics.density

    @SuppressLint("InflateParams")
    private val viewRoot = activity.layoutInflater.inflate(R.layout.dlg_context_menu, null, false)

    private val dialog: Dialog = Dialog(activity).apply {
        setContentView(viewRoot)
        setCancelable(true)
        setCanceledOnTouchOutside(true)
    }

    @SuppressLint("SetTextI18n")
    suspend fun show() = ProgressRunner(activity)
        .progressPrefix("prepare large image…")
        .run {

            if (!girl.prepareLargeImage(activity, step)) {
                showToast(activity, false, "can't prepare large image.")
                return@run
            }

            val largePath = girl.largePath!!

            Glide.with(activity)
                .load(File(largePath))
                .into(viewRoot.findViewById(R.id.ivImage))

            val tvText = viewRoot.findViewById<TextView>(R.id.tvText).apply {
                text = "${girl.timeString}\n\n$largePath\n\n${girl.seeds}"
            }

            viewRoot.findViewById<Button>(R.id.btnCancel).apply {
                isAllCaps = false
                setOnClickListener {
                    dialog.cancel()
                }
            }

            val llPanel: LinearLayout = viewRoot.findViewById(R.id.llPanel)

            fun addButton(caption: String, callback: () -> Unit) {
                llPanel.addView(Button(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (density * 3f + 0.5f).toInt()
                    }
                    isAllCaps = false
                    text = caption
                    setOnClickListener {
                        callback()
                        dialog.dismissSafe()
                    }
                })
            }

            addButton("View Image") {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        val uri = FileProvider.getUriForFile(
                            activity,
                            App1.FILE_PROVIDER_AUTHORITY,
                            File(largePath)
                        )
                        setDataAndType(uri, "image/png")
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (ex: Throwable) {
                    log.trace(ex)
                    showToast(activity, ex, "view failed.")
                }
            }

            addButton("Share Image") {
                try {
                    activity.startActivity(Intent(Intent.ACTION_SEND).apply {
                        val uri = FileProvider.getUriForFile(
                            activity,
                            App1.FILE_PROVIDER_AUTHORITY,
                            File(largePath)
                        )
                        type = "image/png"
                        putExtra(Intent.EXTRA_SUBJECT, "#WaifuLabs")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (ex: Throwable) {
                    log.trace(ex)
                    showToast(activity, ex, "share failed.")
                }
            }
            addButton("Share Text") {
                try {
                    activity.startActivity(Intent(Intent.ACTION_SEND).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, tvText.text.toString())
                    })
                } catch (ex: Throwable) {
                    showToast(activity, ex, "failed.")
                }
            }


            val window = dialog.window
            if (window != null) {
                val lp = window.attributes
                lp.width = (0.5f + 310f * density).toInt()
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                window.attributes = lp
            }
            dialog.show()
        }
}

