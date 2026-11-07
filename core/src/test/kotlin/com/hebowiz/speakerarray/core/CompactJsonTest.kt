package com.hebowiz.speakerarray.core

import java.io.StringWriter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompactJsonTest {
    @Test fun preservesJsonTypesEscapesAndNumericValues() {
        val original=JSONObject().put("text","引用\"と\\改行\n\t\u0001 日本語 / 😀")
            .put("values",JSONArray(listOf(JSONObject.NULL,true,false,-0.0,1e-20,1234567890123L)))
            .put("nested",JSONObject().put("empty",JSONArray()).put("null",JSONObject.NULL))
        val out=StringWriter();CompactJson.write(original,out)
        assertTrue(original.similar(JSONObject(out.toString())))
        assertFalse(out.toString().contains('\n'))
    }
    @Test fun sessionStreamingPreservesCandidatesSelectionUndoAndLegacyInput() {
        val baseline=Session(masterDb=-18.0,orientationChosen=true)
        val candidates=List(3) {i->ExperimentalCandidate("candidate-$i","a".repeat(64),"aggregate",
            AutoFirSettings(),-18.0,null,List(4097) {0.5},
            JSONObject().put("formalAccepted",false).put("notes",JSONArray(List(2000) {"品質注意 $it"})).toString(),"fixture")}
        val session=baseline.copy(experiments=ExperimentalLibrary(candidates,"candidate-2",undo=SessionRepository.encode(baseline)))
        val compact=SessionRepository.encode(session)
        val oldFormat=JSONObject(compact).toString(2)
        for(raw in listOf(oldFormat,compact)) {
            val restored=SessionRepository.decode(raw)
            assertEquals(session.copy(experiments=session.experiments.copy(undo=null)),
                restored.copy(experiments=restored.experiments.copy(undo=null)))
            assertEquals(baseline,SessionRepository.decode(requireNotNull(restored.experiments.undo)))
        }
        assertTrue(compact.length<oldFormat.length)
        val out=StringWriter();SessionRepository.write(session,out)
        assertTrue(JSONObject(compact).similar(JSONObject(out.toString())))
    }
}
