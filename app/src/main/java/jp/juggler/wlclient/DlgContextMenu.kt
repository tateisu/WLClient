package jp.juggler.wlclient

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import jp.juggler.util.LogCategory
import jp.juggler.util.ProgressRunner
import jp.juggler.util.setButtonColor
import jp.juggler.util.showToast
import jp.juggler.wlclient.table.Girl
import jp.juggler.wlclient.table.MarkType
import java.io.File

// open context menu, view,share,share file path..
class DlgContextMenu(
    private val activity: ActMain,
    private val girl: Girl,
    private val step: Int
) {

    companion object {
        private val log = LogCategory("DlgContextMenu")
    }

    private val density: Float = activity.resources.displayMetrics.density

    @SuppressLint("InflateParams")
    private val viewRoot = activity.layoutInflater.inflate(R.layout.dlg_context_menu, null, false)
    private val btnMarkNoise : ImageButton =  viewRoot.findViewById(R.id.btnMarkNoise)
    private val btnMarkColor : ImageButton =  viewRoot.findViewById(R.id.btnMarkColor)
    private val btnMarkDetail : ImageButton =  viewRoot.findViewById(R.id.btnMarkDetail)
    private val btnMarkPose : ImageButton =  viewRoot.findViewById(R.id.btnMarkPose)

    private val dialog: Dialog = Dialog(activity).apply {
        setContentView(viewRoot)
        setCancelable(true)
        setCanceledOnTouchOutside(true)
    }

    @SuppressLint("SetTextI18n")
    suspend fun show() = ProgressRunner(activity)
        .run { progress ->

            if (!girl.prepareLargeImage(activity, step, progress = progress)) {
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

            viewRoot.findViewById<Button>(R.id.btnCancel).setOnClickListener {
                dialog.cancel()
            }

            viewRoot.findViewById<Button>(R.id.btnImageView).setOnClickListener{
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

            viewRoot.findViewById<Button>(R.id.btnImageSend).setOnClickListener{
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

            viewRoot.findViewById<Button>(R.id.btnTextSend).setOnClickListener{
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


            viewRoot.findViewById<Button>(R.id.btnName).setOnClickListener{
                   DlgNaming.open(activity ,girl,step)
            }
            fun showMarkButton(){
                btnMarkColor.setButtonColor(activity,R.drawable.ic_color,! MarkType.Color.sameWith(girl.seeds))
                btnMarkDetail.setButtonColor(activity,R.drawable.ic_brush,! MarkType.Detail.sameWith(girl.seeds))
                btnMarkPose.setButtonColor(activity,R.drawable.ic_run,! MarkType.Pose.sameWith(girl.seeds))
                btnMarkNoise.setButtonColor(activity,R.drawable.ic_shuffle,! MarkType.Noise.sameWith(girl.seeds))
            }

            fun initMarkButton(btn:ImageButton,type:MarkType){
                btn.tag = type
                btn.setOnClickListener {
                    type.savePart(activity,girl.seeds)
                    showMarkButton()
                }
            }
            initMarkButton(btnMarkColor,MarkType.Color)
            initMarkButton(btnMarkDetail,MarkType.Detail)
            initMarkButton(btnMarkPose,MarkType.Pose)
            initMarkButton(btnMarkNoise,MarkType.Noise)
            showMarkButton()

//            val llPanel: LinearLayout = viewRoot.findViewById(R.id.llPanel)
//
//            fun addButton(caption: String, callback: () -> Boolean) {
//                llPanel.addView(Button(activity).apply {
//                    layoutParams = LinearLayout.LayoutParams(
//                        LinearLayout.LayoutParams.MATCH_PARENT,
//                        LinearLayout.LayoutParams.WRAP_CONTENT
//                    ).apply {
//                        topMargin = (density * 3f + 0.5f).toInt()
//                    }
//                    isAllCaps = false
//                    text = caption
//                    setOnClickListener {
//                        if( callback() ) dialog.dismissSafe()
//                    }
//                })
//            }

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

