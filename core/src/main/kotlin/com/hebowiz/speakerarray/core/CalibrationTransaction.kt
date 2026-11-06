package com.hebowiz.speakerarray.core

/** Trial coefficients remain local until all verification passes for this exact generation. */
class CalibrationTransaction(val original: Session, val generation: Long) {
    private var ended=false
    fun rollback(): Session {ended=true;return original}
    fun accept(profile: CalibrationProfile, verification: List<Verification>): Session {
        check(!ended && profile.generation==generation) {"古い測定結果・取消済みの結果は反映できません"}
        require(verification.size>=2 && verification.all {
            it.reductionSingleDb>=MeasurementLimits.IMPROVEMENT_DB && it.reductionBothDb>=MeasurementLimits.IMPROVEMENT_DB &&
                it.bandReduction.isNotEmpty() && it.bandReduction.all {v->v>=-MeasurementLimits.MAX_WORSENING_DB}
        }) {"再測定の品質基準を満たしていません"}
        val next=original.copy(processing=profile.applied,autoFir=profile.settings,calibration=profile,
            a=if(original.processing.length==profile.applied.length) original.a else null,
            b=if(original.processing.length==profile.applied.length) original.b else null)
        next.validate();ended=true;return next
    }
}
