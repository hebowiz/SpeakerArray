import cmath
import math
import unittest
from analyze_diagnostics import phase_fit, failures, runs, percentile


class AnalysisTest(unittest.TestCase):
    def test_thresholds_and_overlap(self):
        q=dict(left={'snrDb': [19, 30]}, right={'snrDb': [30, 30]}, leftRepeatDb=[2, 1.5], rightRepeatDb=[0, 0],
               leftRepeatPhaseDeg=[25, 15], rightRepeatPhaseDeg=[0, 0])
        self.assertEqual(['LEFT_SNR', 'LEFT_REPEAT_GAIN', 'LEFT_REPEAT_PHASE'], failures(q, 0, 1, 5))
        self.assertEqual([], failures(q, 1, 2, 20))
        self.assertEqual(['LEFT_SNR', 'LEFT_REPEAT_PHASE'], failures(q, 0, 2, 20))
        self.assertEqual([[1, 2], [4]], runs([1, 2, 4]))
        self.assertEqual(2.8, percentile([1, 2, 3], .9))

    def test_wrap_and_low_snr_gaps_are_not_stitched(self):
        f=[500+i*6 for i in range(85)]
        a=[1+0j]*len(f)
        b=[cmath.exp(1j*(170*math.pi/180-2*math.pi*x*3/48000)) for x in f]
        fit=phase_fit(f, a, b, [40]*len(f))
        self.assertAlmostEqual(3, fit['delaySamples'], places=8)
        snr=[40]*len(f)
        for i in range(30, 55):
            snr[i]=0
            b[i]=cmath.exp(1j*i*2.3)
        fit=phase_fit(f, a, b, snr)
        self.assertEqual(30, fit['fittedBins'])
        self.assertAlmostEqual(3, fit['delaySamples'], places=8)
        self.assertFalse(phase_fit(f, a, b, [0]*len(f))['usable'])


if __name__ == '__main__':
    unittest.main()
