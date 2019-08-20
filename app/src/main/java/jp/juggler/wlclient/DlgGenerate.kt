package jp.juggler.wlclient

import android.annotation.SuppressLint
import android.app.Dialog
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import com.beust.klaxon.JsonArray
import jp.juggler.util.LogCategory
import jp.juggler.util.ProgressRunner
import jp.juggler.util.toJsonArray
import jp.juggler.wlclient.table.Girl
import jp.juggler.wlclient.table.MarkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


// open context menu, view,share,share file path..
class DlgGenerate(private val activity: ActMain) {

    companion object {
        private val log = LogCategory("DlgSeeds")

        suspend fun open(activity: ActMain) {
            DlgGenerate(activity).show()
        }
    }

    private val density: Float = activity.resources.displayMetrics.density

    @SuppressLint("InflateParams")
    private val viewRoot = activity.layoutInflater.inflate(R.layout.dlg_seeds2, null, false)

    private val ivImage: ImageView = viewRoot.findViewById(R.id.ivImage)
    private val btnGenerate: Button = viewRoot.findViewById(R.id.btnGenerate)
    private val btnCancel: Button = viewRoot.findViewById(R.id.btnCancel)

    private val rgPose: RadioGroup = viewRoot.findViewById(R.id.rgPose)
    private val rbPoseMarked: RadioButton = viewRoot.findViewById(R.id.rbPoseMarked)
    private val rbPoseRandom: RadioButton = viewRoot.findViewById(R.id.rbPoseRandom)

    private val rgDetail: RadioGroup = viewRoot.findViewById(R.id.rgDetail)
    private val rbDetailMarked: RadioButton = viewRoot.findViewById(R.id.rbDetailMarked)
    private val rbDetailRandom: RadioButton = viewRoot.findViewById(R.id.rbDetailRandom)

    private val rgColor: RadioGroup = viewRoot.findViewById(R.id.rgColor)
    private val rbColorMarked: RadioButton = viewRoot.findViewById(R.id.rbColorMarked)
    private val rbColorRandom: RadioButton = viewRoot.findViewById(R.id.rbColorRandom)

    private val rgNoise: RadioGroup = viewRoot.findViewById(R.id.rgNoise)
    private val rbNoiseMarked: RadioButton = viewRoot.findViewById(R.id.rbNoiseMarked)
    private val rbNoiseRandom: RadioButton = viewRoot.findViewById(R.id.rbNoiseRandom)


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
            btnCancel.setOnClickListener {
                dialog.cancel()
            }

            fun initRadioGroup(rg: RadioGroup, rbMarked: RadioButton, rbRandom: RadioButton, type: MarkType) {
                val key = "Mark_${type.name}"
                val sv = App1.pref.getString(key, "") ?: ""
                rbMarked.text = "Marked : ${sv}"
                if (sv.isNotEmpty()) {
                    rbMarked.isEnabled = true
                    rbMarked.isChecked = true
                    rbRandom.isChecked = false
                } else {
                    rbMarked.isEnabled = false
                    rbMarked.isChecked = false
                    rbRandom.isChecked = true
                }
            }

            initRadioGroup(rgPose, rbPoseMarked, rbPoseRandom, MarkType.Pose)
            initRadioGroup(rgColor, rbColorMarked, rbColorRandom, MarkType.Color)
            initRadioGroup(rgDetail, rbDetailMarked, rbDetailRandom, MarkType.Detail)
            initRadioGroup(rgNoise, rbNoiseMarked, rbNoiseRandom, MarkType.Noise)

            suspend fun getPart(rg: RadioGroup, markedId: Int, type: MarkType): List<Any?> {
                return if (rg.checkedRadioButtonId == markedId) {
                    val key = "Mark_${type.name}"
                    App1.pref.getString(key, "")!!.toJsonArray()
                } else {
                    getRange().getRandomSeeds().toJsonArray().slice(type.range)
                }
            }

            btnGenerate.setOnClickListener {
                activity.launchEx("generate") {
                    val seeds = JsonArray(ArrayList<Any?>().apply {
                        addAll(getPart(rgPose, R.id.rbPoseMarked, MarkType.Pose))
                        addAll(getPart(rgDetail, R.id.rbDetailMarked, MarkType.Detail))
                        addAll(getPart(rgColor, R.id.rbColorMarked, MarkType.Color))
                        addAll(getPart(rgNoise, R.id.rbNoiseMarked, MarkType.Noise))
                    }).toJsonString().stripJsonWL()

                    activity.chooseBySeeds(seeds)?.let { girl ->
                        activity.glide.load(girl.largePath).into(ivImage)
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

