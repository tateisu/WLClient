package jp.juggler.wlclient

import com.beust.klaxon.JsonArray
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

fun String.stripJsonWL() = toString().replace("""\.0+\b""".toRegex(), "")

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
                }.toString()
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
        appendRandomRange(StringBuilder()).toString().stripJsonWL()

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
            sb.append((if (isMax) max else min).toString())
        }
        return sb
    }

    fun dump(isMax: Boolean) =
        appendDump(StringBuilder(), isMax).toString().stripJsonWL()
}