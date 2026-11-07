package com.hebowiz.speakerarray.core

import org.junit.Assert.*
import org.junit.Test

class FirReuseTest {
    @Test fun safeSavedProcessingNeedsNoCurrentCertificate() {
        val s=Session(masterDb=-24.0,processing=Processing(delayUs=125.0,phaseDeg=-90.0,right=Channel(gainDb=-3.0)))
        val p=CalibrationProfile(1,s.autoFir,s.masterDb,false,7,0,false,s.processing,org.json.JSONObject(SessionRepository.encode(s)).toString(),"{}")
        val saved=s.copy(calibration=p)
        assertTrue(CalibrationPlaybackPolicy.matches(p,saved,7,0,true))
        assertFalse(CalibrationPlaybackPolicy.matches(p,saved,7,0,false))
        PlaybackSafety.requireSafe(saved.effectiveProcessing(),saved.masterDb)
        val reload=SessionRepository.decode(SessionRepository.encode(saved))
        assertEquals(saved.processing,reload.processing)
        assertEquals(saved.calibration,reload.calibration)
        assertFalse(CalibrationPlaybackPolicy.matches(p,reload,7,0,false))
    }
    @Test fun playbackSafetyRejectsNonfiniteAndPeakWithoutAnyProfile() {
        for(p in listOf(Processing(phaseDeg=Double.NaN),Processing(right=Channel(gainDb=12.0)),
            Processing(left=Channel(fir=FirSpec(mode=FirMode.COEFFICIENTS,coefficients=List(513) {Double.NaN}))))) {
            assertTrue(runCatching {PlaybackSafety.requireSafe(p,0.0)}.isFailure)
        }
        assertTrue(runCatching {PlaybackSafety.requireSafe(Processing(),Double.NaN)}.isFailure)
    }
}
