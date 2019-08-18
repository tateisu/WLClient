package jp.juggler.wlclient

import android.annotation.SuppressLint
import android.app.Dialog
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import com.beust.klaxon.JsonArray
import jp.juggler.util.*
import jp.juggler.wlclient.table.Girl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random


class RangeItem(
    private var min: Double = 0.0,
    private var max: Double = 0.0,
    private var hasDot: Boolean = false,
    private var list: ArrayList<RangeItem>? = null
) {

    private fun checkRange1(src: Double): RangeItem {
        min = min(this.min, src)
        max = max(this.max, src)
        if (floor(src) != src) hasDot = true
        return this
    }

    fun checkRangeList(srcArray: JsonArray<*>): RangeItem {
        var list = this.list
        if (list == null) {
            list = ArrayList()
            this.list = list
        }
        srcArray.forEachIndexed { index, src ->

            var dst = if (index < list.size) list[index] else null

            dst = when (src) {

                null -> error("src is null")

                is JsonArray<*> ->
                    (dst ?: RangeItem()).checkRangeList(src)

                is Number -> {
                    val d = src.toDouble()
                    when (dst) {
                        null -> RangeItem(min = d, max = d)
                        else -> dst.checkRange1(d)
                    }
                }

                else -> error("unknown item type: ${src::class.java.simpleName}")
            }

            if (index >= list.size) {
                list.add(dst)
            } else {
                list[index] = dst
            }
        }
        return this
    }

    private fun Double.format() =
        toString().replace("""\.0+\z""".toRegex(), "")

    private fun appendRandomRange(sb: StringBuilder): StringBuilder {
        val list = this.list
        when {
            list != null -> {
                sb.append('[')
                list.forEachIndexed { index, it ->
                    if (index > 0) sb.append(',')
                    it.appendRandomRange(sb)
                }
                sb.append(']')
            }
            hasDot -> sb.append(
                if (min == max) {
                    min
                } else {
                    Random.nextDouble(min, max)
                }.format()
            )
            else -> sb.append(
                if (min == max) {
                    min.toLong()
                } else {
                    Random.nextLong(min.toLong(), max.toLong())
                }.toString()
            )
        }
        return sb
    }

    fun getRandomSeeds() =
        appendRandomRange(StringBuilder()).toString()

    private fun appendDump(sb: StringBuilder, isMax: Boolean): StringBuilder {
        val list = this.list
        if (list != null) {
            sb.append('[')
            list.forEachIndexed { index, it ->
                if (index > 0) sb.append(',')
                it.appendDump(sb, isMax)
            }
            sb.append(']')
        } else {
            sb.append((if (isMax) max else min).format())
        }
        return sb
    }

    fun dump(isMax: Boolean) =
        appendDump(StringBuilder(), isMax).toString()
}


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

    private fun prepareRandomRange(): RangeItem {
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
            x = ProgressRunner(activity).progressPrefix("scanning value range from history…").run {
                withContext(Dispatchers.IO){
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
                activity.launch {
                    try {
                        etSeeds.setText(getRange().getRandomSeeds())
                    } catch (ex: Throwable) {
                        log.trace(ex)
                        showToast(activity, ex, "randomize failed.")
                    }
                }
            }

            btnGenerate.setOnClickListener {
                try {
                    activity.launch {
                        if (callback(spStep.selectedItemPosition + 1, etSeeds.text.toString()))
                            showToast(activity, false, "generated!")
                    }
                } catch (ex: Throwable) {
                    log.trace(ex)
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

