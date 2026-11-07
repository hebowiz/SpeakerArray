package com.hebowiz.speakerarray

import androidx.test.platform.app.InstrumentationRegistry
import com.hebowiz.speakerarray.core.SessionRepository
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, silent replay of the large legacy session from the device crash. No audio or app state edits. */
class LargeSessionPersistenceTest {
    @Test fun legacyHistorySurvivesRepeatedCompactSaves() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("largeSessionFixture")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val input=File(context.filesDir,"crash-session-fixture.json")
        val session=SessionRepository.decode(input.readText())
        assertEquals(5,session.experiments.candidates.size)
        assertEquals(6,session.experiments.candidates.takeLast(2).sumOf {it.comparisons.size})
        val before=session.experiments.candidates.map {it.coefficientHash}
        repeat(3) {
            val encoded=SessionRepository.encode(session)
            assertTrue(encoded.length<16_000_000)
            val restored=SessionRepository.decode(encoded)
            assertEquals(session,restored)
            assertEquals(before,restored.experiments.candidates.map {it.coefficientHash})
        }
    }
}
