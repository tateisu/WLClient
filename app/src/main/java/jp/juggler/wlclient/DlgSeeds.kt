package jp.juggler.wlclient

import android.annotation.SuppressLint
import android.app.Dialog
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import jp.juggler.util.*
import jp.juggler.wlclient.table.Girl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


// open context menu, view,share,share file path..
class DlgSeeds(
    private val activity: ActMain,
    private val girl: Girl?,
    private val callback: suspend (step: Int, seeds: String) -> Boolean // return true if dialog should close.
) {

    companion object {
        private val log = LogCategory("DlgSeeds")

        suspend fun open(activity: ActMain, girl: Girl?, callback: suspend (step: Int, seeds: String) -> Boolean) {
            DlgSeeds(activity, girl, callback).show()
        }
    }

    private val density: Float = activity.resources.displayMetrics.density

    @SuppressLint("InflateParams")
    private val viewRoot = activity.layoutInflater.inflate(R.layout.dlg_seeds, null, false)

    private val dialog: Dialog = Dialog(activity).apply {
        setContentView(viewRoot)
        setCancelable(true)
        setCanceledOnTouchOutside(true)
    }

    private suspend fun prepareRandomRange(): RangeItem {
        val dst = RangeItem()

        Girl.scanSeeds { seeds ->
            dst.checkRangeList(seeds.toJsonArray())
        }

        log.d("prepareRandomRange: min=${dst.dump(false)}")
        log.d("prepareRandomRange: max=${dst.dump(true)}")

        return dst
    }

    private var _range: RangeItem? = null
    private suspend fun getRange(): RangeItem {
        var x = _range
        if (x == null) {
            x = ProgressRunner(activity).run { progress ->
                progress.publishApiProgress("scanning value range from history…")
                withContext(Dispatchers.IO) {
                    prepareRandomRange()
                }
            }
            _range = x
        }
        return x
    }


    @SuppressLint("SetTextI18n")
    suspend fun show() {
        try {
            val etSeeds: EditText = viewRoot.findViewById(R.id.etSeeds)
            val btnRandomize: Button = viewRoot.findViewById(R.id.btnRandomize)
            val spStep: Spinner = viewRoot.findViewById(R.id.spStep)
            val btnGenerate: Button = viewRoot.findViewById(R.id.btnGenerate)
            val btnCancel: Button = viewRoot.findViewById(R.id.btnCancel)

            etSeeds.setText(girl?.seeds ?: getRange().getRandomSeeds())

            spStep.apply {
                val activity = this@DlgSeeds.activity
                adapter = ArrayAdapter(
                    activity,
                    android.R.layout.simple_spinner_item,
                    arrayOf(
                        activity.getString(R.string.color),
                        activity.getString(R.string.detail),
                        activity.getString(R.string.pose)
                    )
                ).apply {
                    setDropDownViewResource(R.layout.lv_spinner_dropdown)
                }
            }

            btnCancel.setOnClickListener {
                dialog.cancel()
            }

            btnRandomize.setOnClickListener {
                activity.launchEx("randomize") {
                    try {
                        etSeeds.setText(getRange().getRandomSeeds())
                    } catch (ex: Throwable) {
                        log.trace(ex)
                        showToast(activity, ex, "randomize failed.")
                    }
                }
            }

            btnGenerate.setOnClickListener {
                activity.launchEx("generate"){
                    if (callback(spStep.selectedItemPosition + 1, etSeeds.text.toString()))
                        showToast(activity, false, "generated!")
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

