"""Unit tests for audiofx.limiter. Run: python3 tools/verify.py"""

import math
import os
import sys
import unittest

sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python")
)

from audiofx.limiter import Limiter


SR = 48000


def sine(freq_hz, seconds, amplitude=0.8):
    return [
        amplitude * math.sin(2.0 * math.pi * freq_hz * i / SR)
        for i in range(int(seconds * SR))
    ]


class LimiterTest(unittest.TestCase):
    def test_hot_signal_never_exceeds_ceiling(self):
        # 1.5x full-scale sine: every peak would clip without limiting.
        lim = Limiter(ceiling_db=-1.0)
        out = lim.process(sine(1000, 1.0, amplitude=1.5), SR)
        ceiling = 10.0 ** (-1.0 / 20.0)
        self.assertLessEqual(max(abs(v) for v in out), ceiling * (1.0 + 1e-6))

    def test_impulse_caught_by_lookahead(self):
        # A single full-scale-exceeding impulse must not overshoot either.
        sig = [0.0] * SR + [2.0] + [0.0] * SR
        lim = Limiter(ceiling_db=-1.0)
        out = lim.process(sig, SR)
        ceiling = 10.0 ** (-1.0 / 20.0)
        self.assertLessEqual(max(abs(v) for v in out), ceiling * (1.0 + 1e-6))

    def test_quiet_signal_is_transparent(self):
        # Below the ceiling the limiter must be bit-transparent apart from the
        # lookahead delay: out[L:] == in[:-L].
        lim = Limiter(ceiling_db=-1.0)
        sig = sine(440, 1.0, amplitude=0.5)
        out = lim.process(sig, SR)
        L = lim.lookahead_samples(SR)
        for a, b in zip(out[L:], sig[:-L]):
            self.assertAlmostEqual(a, b, places=9)

    def test_ceiling_param_is_honored(self):
        lim = Limiter(ceiling_db=-6.0)
        out = lim.process(sine(1000, 1.0, amplitude=1.5), SR)
        ceiling = 10.0 ** (-6.0 / 20.0)
        self.assertLessEqual(max(abs(v) for v in out), ceiling * (1.0 + 1e-6))

    def test_output_length_matches_input(self):
        lim = Limiter()
        sig = sine(440, 0.1, amplitude=0.9)
        out = lim.process(sig, SR)
        self.assertEqual(len(out), len(sig))

    def test_does_not_mutate_input(self):
        lim = Limiter()
        sig = sine(1000, 0.5, amplitude=1.5)
        snapshot = list(sig)
        lim.process(sig, SR)
        self.assertEqual(sig, snapshot)


# ---------------------------------------------------------------------------
# True-peak mode (DSP-3). The adversarial signal is a sine at exactly fs/4
# whose *sample* peak is 0.89 (just under the -1 dBFS ceiling, so a
# sample-peak limiter leaves it alone) while its *true* (inter-sample)
# peak is ~1.0, almost 1 dB over the ceiling.
# ---------------------------------------------------------------------------

def adversarial_sine(seconds=0.5):
    """fs/4 sine, amplitude 1.0, phase chosen so max|sample| == 0.89."""
    phase = math.acos(0.89)
    return [
        math.sin(2.0 * math.pi * (SR / 4.0) * i / SR + phase)
        for i in range(int(seconds * SR))
    ]


def measure_true_peak(samples):
    """Independent 4x oversampled peak measurement.

    Spectral zero-padding via FFT (numpy): the signal's spectrum is
    zero-padded to 4x and inverse-transformed, giving the band-limited
    interpolation on a 4x grid. Deliberately shares NO code with the
    limiter's FIR interpolator. The max is taken over the middle half
    so the limiter's start-up transient cannot pollute the reading.
    """
    import numpy as np

    x = np.asarray(samples, dtype=np.float64)
    n = len(x)
    spectrum = np.fft.rfft(x)
    padded = np.zeros(2 * n + 1, dtype=complex)
    padded[: len(spectrum)] = spectrum
    over = np.fft.irfft(padded, 4 * n) * 4.0
    mid = over[len(over) // 4 : 3 * len(over) // 4]
    return float(np.max(np.abs(mid)))


def dbfs(value):
    return 20.0 * math.log10(value)


# Fixture signal + the CURRENT (pre-true-peak) default-mode output on it,
# captured from the limiter before the true-peak mode existed. Case C of
# DSP-3: adding the mode must not change default outputs by one bit.
_DEFAULT_FIXTURE_IN = [0.1773121239968037, 0.3192098184286257, 0.4539399781148441, 0.5791574623700777, 0.6927581619131986, 0.792930160010002, 0.878197288213173, 0.9474539499620778, 0.999990313582126, 1.0355072279642596, 1.0541204828242332, 1.056354313912053, 1.043124334579905, 1.0157103513707946, 0.9757197855338511, 0.9250426676941097, 0.8657993928879567, 0.8002826120667693, 0.7308947890305042, 0.6600830645828928, 0.5902731395504308, 0.5238039133532075, 0.4628645944034861, 0.40943593327410444, 0.36523712103457506, 0.3316797462402775, 0.3098300186774703, 0.30038025096630616, 0.3036303461851949, 0.319479777160829, 0.3474302678394403, 0.38659910641934636, 0.43574274102956895, 0.49329003898321994, 0.5573843370771463, 0.6259331796870478, 0.6966644395584166, 0.7671873485013616, 0.8350568360511088, 0.8978394869367713, 0.9531793851928523, 0.9988621150783001, 1.0328752365778364, 1.053463644906277, 1.059178356722897, 1.0489174372044796, 1.0219579872389715, 0.9779783434222373, 0.9170698991630446, 0.8397382263400244, 0.7468934565167267, 0.6398301613944052, 0.5201972466248753, 0.3899586341387675, 0.2513457489393587, 0.10680304057000327, -0.04107204842092914, -0.18959310948385466, -0.3360511285016559, -0.47778145654807247, -0.6122296518356483, -0.7370144831441564, -0.849986459132924, -0.9492803648011281, -1.0333604429121244, -1.101057050346425, -1.1515938420669214, -1.184604782843708, -1.2001405526214448, -1.198664188468788, -1.1810360871801153, -1.148488770451214, -1.1025920818553592, -1.0452097346127924, -0.9784483548425906, -0.9046003607085478, -0.8260821785015758, -0.745369418039965, -0.6649307086433734, -0.5871619312853257, -0.514322571456056, -0.4484758610646758, -0.39143427784968154, -0.34471182986470394, -0.3094843743507921, -0.2865590093634547, -0.27635333841318555, -0.27888514933425845, -0.2937727753976078, -0.32024612647954653, -0.35716809822193635, -0.4030657948957558, -0.45617074423297954, -0.514467046558951, -0.5757461923136753, -0.6376671069459231, -0.6978198447819354, -0.7537912574070755, -0.8032309098691308, -0.8439155109845862, -0.07381016238946247, -0.8911248137227051, -0.894364436299083, -0.882371591552467, -0.854360269121818, -0.8099400975171114, -0.7491302818751299, -0.672362891815187, -0.5804754008085169, -0.47469265949977113, -0.35659876171300503, -0.22809952620998514, -0.09137656273033423, 0.051165888990613, 0.19695996941919322, 0.3433375277207843, 0.48759612454229195, 0.627065994933397, 0.7591762319320086, 0.881518471477856, 0.9919064243973064, 1.0884297095205653, 1.1695005907170026, 1.2338924058753042, 1.2807686927681068, 1.3097022596518868, 1.3206837110091239, 1.3141192141735445, 1.2908175734894394, 1.2519669577951227, 1.1991018970880207, 1.1340614181610154, 1.0589394201555047, 0.9760285933108875, 0.8877593524115549, 0.7966353861425666, 0.705167511372667, 0.6158075649935324, 0.5308840642186412, 0.4525413192313293, 0.3826835910023384, 0.3229257543568179, 0.2745517554353176, 0.2384819480364696, 0.21525016030815403, 0.20499108796707577, 0.20743833935544587, 0.22193317827641634, 0.247443729998238, 0.28259414142276207, 0.3257029253577559, 0.37482947794618504, 0.42782754389357125, 0.4824042218075495, 0.5361829564943287, 0.586768860251813, 0.6318146438026263, 0.6690854211297765, 0.6965206815432432, 0.7122917960582148, 0.7148535416769228, 0.702988283388525, 0.6758416455497493, 0.6329487267640458, 0.5742501596202558, 0.5000975822150038, 0.41124836531923753, 0.308849720113494, 0.19441258925558272, 0.06977599138954282, -0.06293726093826507, -0.20137232415867948, -0.3430002256598898, -0.4851804023354913, -0.6252262515979075, -0.7604719916345746, -0.8883390930846824, -1.0064005551415556, -1.1124413550578818, -1.204513499685211, -1.2809842484094425, -1.3405762549708453, -1.3823985865214503, -1.4059678163603075, -1.4112186458706995, -1.3985037844872643, -1.3685830968967987, -1.3226023067915398, -1.2620618190216404, -1.188776479807166, -1.1048273310262537, -1.0125066233145976, -0.9142575283333652, -0.812610128496731, -0.7101153590894459, -0.6092786305242095, -0.5124948661081976, -0.42198665291136367, -0.339747121146652, -0.2674890430367506, -0.20660147871104184, -0.1581150985283737, -0.12267708354203433, -0.10053625457200521, -0.09153881210131753, -0.09513479098830957, -0.11039505306570846, -0.13603836443207834, -0.17046783986079694, -0.21181579117492436, -0.2579957960893592, -0.30676061467111143, -0.35576442716917206, -0.4026277535324513, -0.44500334443587064, -0.480641307916996, -0.5074517554618554, -0.5235633160538687, -0.5273759746222939, -0.5176068396739668, -0.49332762978329264, -0.4539928852139975, -0.399458153585558, -0.3299876608294472, -0.24625125384256924, -0.14931068203797399, -0.04059556407026371, 0.0781303439272244, 0.20481170827970402, 0.33715190432120123, 0.47266972552777065, 0.6087610428125521, 0.7427638116547198, 0.8720247367112467, 0.9939658597259049, 1.1061493380516856, 1.2063387279040005, 1.292555177339231, 1.3631270665315445, 1.4167318037330903, 1.452428689882773, 1.4696819978261717, 1.4683736674306118, 1.4488052888475558, 1.4116893257025294, 1.358129810770204, 1.2895930213758573, 1.2078689031702252, 1.11502425221676, 1.013348880197781, 0.9052961703708652, 0.7934195779076445, 0.6803067336077929, 0.5685128719463453, 0.4604953213650331, 0.3585507662158262]

_DEFAULT_FIXTURE_OUT = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.10933467366827544, 0.19418882710712665, 0.2754439856324902, 0.35125446908002583, 0.4201142498456002, 0.4808540526875131, 0.5325606086756904, 0.5745592183506751, 0.606418483641595, 0.6279567922174182, 0.6392443205312873, 0.6405989695955198, 0.632575987996174, 0.615951481256458, 0.5917002286889061, 0.5609683908598918]

# Generator output of limiter._tp_fir_coeffs(), checked in so coefficient
# drift (in Python OR in the pasted LiveLimiter.java table) fails tests.
_TP_FIR_FIXTURE = [-3.125914973077897e-18, -0.005394269095077871, -0.010733004038699265, -0.01131983727588515, 8.716427020585094e-18, 0.024406082142981584, 0.04871748881906059, 0.04734121107565832, -2.2010294790945375e-17, -0.0847129417311841, -0.15856974657115114, -0.1488472760615212, 3.473785298412755e-17, 0.28420329890006324, 0.6271013006437518, 0.9037343471003871, 1.0, 0.8858244329754273, 0.6035877486089342, 0.2684488373446078, 3.21019257900245e-17, -0.13439451236913652, -0.13993185622490914, -0.0728966597200525, -1.837816151528738e-17, 0.03823158844319808, 0.03801113135811911, 0.01837069650487362, 6.3475148694834046e-18, -0.008163679266271689, -0.008183062595106079, -0.004831318968067989]


class TruePeakLimiterTest(unittest.TestCase):
    def test_adversarial_signal_shape(self):
        sig = adversarial_sine()
        self.assertAlmostEqual(max(abs(v) for v in sig), 0.89, places=6)
        measured = measure_true_peak(sig)
        # True peak ~1.0: at least 0.9 dB over the -1 dBFS ceiling.
        self.assertGreater(dbfs(measured), -1.0 + 0.9)

    def test_sample_peak_mode_overshoots_ceiling(self):
        # Case A: sample-peak mode leaves the adversarial sine alone and
        # the independent measurement shows it over the ceiling.
        lim = Limiter(ceiling_db=-1.0)
        out = lim.process(adversarial_sine(), SR)
        overshoot_db = dbfs(measure_true_peak(out)) - (-1.0)
        self.assertGreater(overshoot_db, 0.5)

    def test_true_peak_mode_holds_ceiling(self):
        # Case B: true-peak mode must measure <= ceiling + 0.05 dB.
        lim = Limiter(ceiling_db=-1.0, true_peak=True)
        out = lim.process(adversarial_sine(), SR)
        measured_db = dbfs(measure_true_peak(out))
        self.assertLessEqual(measured_db, -1.0 + 0.05)
        # ... and must not over-attenuate either: it is still a -1 dBFS
        # limiter, not a mute.
        self.assertGreater(measured_db, -1.0 - 1.0)

    def test_default_mode_bit_identical_to_pre_truepeak_output(self):
        # Case C: default (sample-peak) output on the fixture signal is
        # bit-for-bit the output the limiter produced before DSP-3.
        lim = Limiter(ceiling_db=-1.0)
        out = lim.process(list(_DEFAULT_FIXTURE_IN), SR)
        self.assertEqual(out, _DEFAULT_FIXTURE_OUT)

    def test_explicit_sample_peak_mode_matches_default(self):
        sig = adversarial_sine(0.1)
        self.assertEqual(
            Limiter().process(sig, SR),
            Limiter(true_peak=False).process(sig, SR),
        )

    def test_true_peak_quiet_signal_is_transparent(self):
        # Below the ceiling nothing changes apart from the lookahead
        # delay, exactly as in sample-peak mode.
        lim = Limiter(ceiling_db=-1.0, true_peak=True)
        sig = sine(440, 0.5, amplitude=0.5)
        out = lim.process(sig, SR)
        L = lim.lookahead_samples(SR)
        for a, b in zip(out[L:], sig[:-L]):
            self.assertAlmostEqual(a, b, places=9)

    def test_tp_fir_coeffs_match_fixture(self):
        from audiofx import limiter as limiter_mod

        self.assertEqual(limiter_mod.TRUE_PEAK_OVERSAMPLE, 4)
        coeffs = limiter_mod._tp_fir_coeffs()
        self.assertEqual(len(coeffs), 32)
        self.assertEqual(len(_TP_FIR_FIXTURE), 32)
        for got, want in zip(coeffs, _TP_FIR_FIXTURE):
            self.assertAlmostEqual(got, want, places=12)

    def test_java_coeff_table_matches_generator(self):
        # LiveLimiter.java pastes the SAME coefficients; parse them out
        # and diff against the generator so the two cannot drift apart.
        import re

        from audiofx import limiter as limiter_mod

        java_path = os.path.join(
            os.path.dirname(__file__), "..", "app", "src", "main", "java",
            "com", "audiofx", "app", "LiveLimiter.java",
        )
        with open(java_path, encoding="utf-8") as f:
            text = f.read()
        m = re.search(r"TP_FIR_COEFFS\s*=\s*\{(.*?)\};", text, re.S)
        self.assertIsNotNone(m, "TP_FIR_COEFFS table not found in LiveLimiter.java")
        java_coeffs = [float(v) for v in re.findall(r"-?\d+\.\d+(?:e-?\d+)?", m.group(1))]
        coeffs = limiter_mod._tp_fir_coeffs()
        self.assertEqual(len(java_coeffs), len(coeffs))
        for got, want in zip(java_coeffs, coeffs):
            self.assertAlmostEqual(got, want, places=12)


if __name__ == "__main__":
    unittest.main()
