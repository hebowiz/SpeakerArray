package com.hebowiz.speakerarray.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object ExperimentalRuns {
    fun start(c: ExperimentalCandidate)=JSONObject().put("type","speakerarray-experimental-comparison").put("schemaVersion",1)
        .put("runId",UUID.randomUUID().toString()).put("startedAtEpochMs",System.currentTimeMillis())
        .put("candidateId",c.id).put("coefficientHash",c.coefficientHash).put("formalAccepted",false).put("stage","STARTING")
    private fun canonical(v: Any?): String=when(v) {
        is JSONObject->v.keys().asSequence().toList().sorted().joinToString(",","{","}") {JSONObject.quote(it)+":"+canonical(v.get(it))}
        is JSONArray->(0 until v.length()).joinToString(",","[","]") {canonical(v.get(it))}
        is String->JSONObject.quote(v)
        else->v.toString()
    }
    fun id(raw: String): String {val o=JSONObject(raw);return o.optString("runId").takeIf {it.isNotBlank()}
        ?: "legacy-"+sha256(canonical(o).toByteArray(Charsets.UTF_8))}
    fun identity(raw: String)=sha256(canonical(JSONObject(raw)).toByteArray(Charsets.UTF_8))
    fun migrate(o: JSONObject,index: Int): String {
        if(o.optString("runId").isBlank()) {
            o.put("runId",id(o.toString())+"-$index").put("legacyRun",true)
            if(!o.has("startedAtEpochMs")) o.put("startedAtEpochMs",JSONObject.NULL).put("timeNote","旧履歴の日時不明")
        }
        return o.toString()
    }
}

/** A successful write acknowledges an immutable snapshot, never permission to delete automatically. */
data class ExperimentalArchive(val candidateId: String,val coefficientHash: String?,val results: Map<String,String>,val payload: String,
    val token: String=UUID.randomUUID().toString(),val saved: Boolean=false) {
    fun completed(success: Boolean)=copy(saved=success)
    fun remove(c: ExperimentalCandidate,selected: Set<String>): ExperimentalCandidate {
        require(saved) {"JSON退避の保存成功を確認してから整理してください"}
        require(c.id==candidateId && c.coefficientHash==coefficientHash && selected.isNotEmpty() && results.keys.containsAll(selected))
        val current=c.comparisons.associateBy(ExperimentalRuns::id)
        require(selected.all {current[it]?.let(ExperimentalRuns::identity)==results[it]}) {"保存後に履歴が変わりました。再度退避してください"}
        return c.copy(comparisons=c.comparisons.filterNot {ExperimentalRuns.id(it) in selected})
    }
    companion object {
        fun prepare(c: ExperimentalCandidate)=ExperimentalArchive(c.id,c.coefficientHash,c.comparisons.associate {ExperimentalRuns.id(it) to ExperimentalRuns.identity(it)},ExperimentalRepository.export(c))
    }
}
