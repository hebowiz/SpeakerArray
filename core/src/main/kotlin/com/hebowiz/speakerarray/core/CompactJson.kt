package com.hebowiz.speakerarray.core

import java.io.Writer
import org.json.JSONArray
import org.json.JSONObject

/** Writes without building a second, complete JSON string (including on Android's org.json). */
object CompactJson {
    fun write(value: Any?, out: Writer) {
        when (value) {
            null, JSONObject.NULL -> out.write("null")
            is JSONObject -> {
                out.write("{")
                val keys=value.keys();var first=true
                while(keys.hasNext()) {
                    val key=keys.next()
                    if(!first) out.write(",");first=false
                    out.write(JSONObject.quote(key));out.write(":");write(value.get(key),out)
                }
                out.write("}")
            }
            is JSONArray -> {
                out.write("[")
                for(i in 0 until value.length()) {if(i>0) out.write(",");write(value.get(i),out)}
                out.write("]")
            }
            is Number -> out.write(JSONObject.numberToString(value))
            is Boolean -> out.write(value.toString())
            else -> out.write(JSONObject.quote(value.toString()))
        }
    }
}
