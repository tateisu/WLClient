package jp.juggler.wlclient

import android.annotation.SuppressLint
import android.app.Dialog
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import com.bumptech.glide.Glide
import jp.juggler.util.LogCategory
import jp.juggler.util.ProgressRunner
import jp.juggler.util.showToast
import jp.juggler.wlclient.table.Girl
import kotlinx.coroutines.launch
import java.io.File

class DlgNaming(
    private val activity: ActMain,
    private val girl: Girl,
    private val step: Int
) {

    companion object {
        private val log = LogCategory("DlgNaming")

        fun open(activity: ActMain, girl: Girl, step: Int) =
            activity.launch { DlgNaming(activity, girl, step).show() }
    }

    private val density: Float = activity.resources.displayMetrics.density

    @SuppressLint("InflateParams")
    private val viewRoot = activity.layoutInflater.inflate(R.layout.dlg_naming, null, false)

    private val ivImage: ImageView = viewRoot.findViewById(R.id.ivImage)
    private val btnSend: Button = viewRoot.findViewById(R.id.btnSend)
    private val btnCancel: Button = viewRoot.findViewById(R.id.btnCancel)

    private val etName: EditText = viewRoot.findViewById(R.id.etName)
    private val etEMail: EditText = viewRoot.findViewById(R.id.etEMail)


    private val dialog: Dialog = Dialog(activity).apply {
        setContentView(viewRoot)
        setCancelable(true)
        setCanceledOnTouchOutside(true)
    }


    @SuppressLint("SetTextI18n")
    suspend fun show() = ProgressRunner(activity)
        .run { progress ->
            try {
                if (!girl.prepareLargeImage(activity, step, progress = progress)) {
                    showToast(activity, false, "can't prepare large image.")
                    return@run
                }

                val largePath = girl.largePath!!

                Glide.with(activity)
                    .load(File(largePath))
                    .into(ivImage)

                btnCancel.setOnClickListener {
                    dialog.cancel()
                }

                val sv = App1.pref.getString(Pref.EMail, null)
                if (sv?.isNotEmpty() == true) etEMail.setText(sv)

                btnSend.setOnClickListener {
                    val email = etEMail.text.toString().trim()
                    val name = etName.text.toString().trim()

                    if (email.isEmpty()) {
                        showToast(activity, false, R.string.e_mail_empty)
                        return@setOnClickListener
                    }

                    if (name.isEmpty()) {
                        showToast(activity, false, R.string.name_empty)
                        return@setOnClickListener
                    }

                    if (!email.matches("""^[a-zA-Z0-9.!#${'$'}%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)*${'$'}""".toRegex())) {
                        showToast(activity, false, R.string.e_mail_invalid)
                        return@setOnClickListener
                    }

                    activity.launchEx("naming") {
                        if (girl.saveLink(activity, email, name)) {
                            App1.pref.edit().putString(Pref.EMail, email).apply()
                        }
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
            } catch (ex: Throwable) {
                log.trace(ex)
            }
        }
}


