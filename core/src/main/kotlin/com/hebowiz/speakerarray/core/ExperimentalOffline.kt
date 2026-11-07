package com.hebowiz.speakerarray.core

import java.io.File
import org.json.JSONObject
import org.json.JSONArray

/** Sanitized report only: no endpoint identities, original diagnostics or capture data are copied. */
fun main(args: Array<String>) {
    require(args.size in 2..3) {"input-directory output-directory [narrow|wideband]"}
    val input=File(args[0]);val output=File(args[1]);require(input.isDirectory);output.mkdirs()
    val results=JSONArray()
    val wide=args.getOrNull(2)=="wideband"
    val files=if(wide) listOf("hfp-ready-minus24-session1.json")+listOf("hfp-minus24-session1.json")+(1..3).map {"hfp-minus18-session$it.json"}
        else (1..3).map {"hfp-ready-500-1000-session$it.json"}
    for((index,filename) in files.withIndex()) {
        val i=index+1;val file=File(input,filename)
        val row=JSONObject().put("session",if(wide) file.nameWithoutExtension else "prepared-500-1000-$i")
            .put("file",filename).put("group",if(!wide || index==0) "prepared-primary" else "before-preparation-reference");results.put(row)
        if(!file.exists()) {row.put("failure","元診断データなし");continue}
        val raw=file.readText(Charsets.UTF_8);row.put("sourceSha256",sha256(file.readBytes()))
        try {
            val data=ExperimentalFir.readDiagnostic(raw)
            val original=JSONObject(raw);val quality=original.optJSONObject("quality")
            row.put("originalReasonCode",original.optString("reasonCode")).put("originalCoverage",quality?.opt("coverage") ?: JSONObject.NULL)
                .put("originalEligible",quality?.opt("eligible") ?: JSONObject.NULL)
            row.put("settings",CalibrationRepository.settings(data.settings.copy(output=null,input=null)))
                .put("masterDb",data.masterDb).put("timing",JSONObject(data.timing)).put("originalStage",data.originalStage)
            val candidates=JSONArray()
            for(method in listOf("aggregate","repeat1-to-2","repeat2-to-1")) {
                val c=ExperimentalFir.generate(data,method,nonce="offline-v1")
                val safe=c.copy(settings=c.settings.copy(output=null,input=null))
                val name=if(wide) "${file.nameWithoutExtension}-$method.json" else "session$i-$method.json"
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
