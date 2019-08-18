package jp.juggler.util

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser

fun String.toJsonAny(): Any {
    return try {
        val parser = Parser.default()
        when {
            isEmpty() -> JsonObject() // 204 no content
            get(0) == '{' -> parser.parse(StringBuilder(this)) as JsonObject
            get(0) == '[' -> parser.parse(StringBuilder(this)) as JsonArray<*>
            else -> JsonObject().apply { put("content", this) }
        }
    } catch (ex: Throwable) {
        error("decodeJson failed. $ex content=$this")
    }
}

fun String.toJsonObject(): JsonObject {
    val o = toJsonAny()
    return if( o is JsonObject) o else JsonObject().apply { put("content", o) }
}

fun String.toJsonArray() =
    Parser.default().parse(StringBuilder(this)) as JsonArray<*>
