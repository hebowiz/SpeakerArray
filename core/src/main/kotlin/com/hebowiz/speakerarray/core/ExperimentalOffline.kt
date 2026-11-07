package com.hebowiz.speakerarray.core

import java.io.File
import org.json.JSONObject
import org.json.JSONArray

/** Sanitized report only: no endpoint identities, original diagnostics or capture data are copied. */
fun main(args: Array<String>) {
    require(args.size==2) {"input-directory output-directory"}
    val input=File(args[0]);val output=File(args[1]);require(input.isDirectory);output.mkdirs()
    val results=JSONArray()
    for(i in 1..3) {
        val file=File(input,"hfp-ready-500-1000-session$i.json")
        val row=JSONObject().put("session","prepared-500-1000-$i");results.put(row)
        if(!file.exists()) {row.put("failure","元診断データなし");continue}
        val raw=file.readText(Charsets.UTF_8);row.put("sourceSha256",sha256(file.readBytes()))
        try {
            val data=ExperimentalFir.readDiagnostic(raw)
            val candidates=JSONArray()
            for(method in listOf("aggregate","repeat1-to-2","repeat2-to-1")) {
                val c=ExperimentalFir.generate(data,method,nonce="offline-v1")
                val safe=c.copy(settings=c.settings.copy(output=null,input=null))
                val name="session$i-$method.json"
                File(output,name).writeText(ExperimentalRepository.export(safe),Charsets.UTF_8)
                candidates.put(JSONObject().put("id",c.id).put("file",name).put("method",method).put("generated",c.playable)
                    .put("taps",c.settings.taps).put("regularization",c.settings.regularization).put("maximumBoostDb",c.settings.maxBoostDb)
                    .put("masterDb",c.masterDb).put("coefficientHash",c.coefficientHash ?: JSONObject.NULL).put("result",JSONObject(c.report)))
                println("session$i $method: ${if(c.playable) "generated" else c.error}")
            }
            row.put("candidates",candidates)
        } catch(e: Exception) {row.put("failure",e.message)}
    }
    File(output,"index.json").writeText(JSONObject().put("policy",ExperimentalFir.VERSION).put("sessions",results).toString(2),Charsets.UTF_8)
}
