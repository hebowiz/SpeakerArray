package com.hebowiz.speakerarray

import java.util.concurrent.atomic.AtomicLong

/** Process-local counts of capture/communication start attempts; contains no device or PCM data. */
internal object AudioResourceUsage {
    val recordingStarts = AtomicLong()
    val communicationStarts = AtomicLong()
}
