#include "ClickSynth.h"

#include <algorithm>
#include <cmath>

namespace monotonic {

namespace {
constexpr double kTwoPi = 6.28318530717958647692;
constexpr double kPi = 3.14159265358979323846;
}  // namespace

void ClickSynth::generate(int32_t sampleRate) {
    const int32_t validRate = std::max(sampleRate, 8000);
    mSampleRate = validRate;

    // Profile 0: Classic — authentic mechanical wooden metronome via physical modal synthesis
    auto& classic = mBuffers[static_cast<size_t>(SoundProfile::Classic)];
    static constexpr ModalComponent kClassicAccentModes[] = {
        {1580.0, 0.65, 0.0065},
        {2490.0, 0.35, 0.0042},
        {3920.0, 0.15, 0.0022}
    };
    static constexpr ModalComponent kClassicNormalModes[] = {
        {1060.0, 0.65, 0.0075},
        {1740.0, 0.32, 0.0048},
        {2890.0, 0.12, 0.0025}
    };
    static constexpr ModalComponent kClassicSubModes[] = {
        {1320.0, 0.35, 0.0040},
        {2180.0, 0.18, 0.0025}
    };

    classic[static_cast<size_t>(ClickType::Accent)] = buildMechanicalClick(
        validRate,
        kClassicAccentModes,
        sizeof(kClassicAccentModes) / sizeof(kClassicAccentModes[0]),
        3400.0,
        4800.0,
        1650.0,
        0.45,
        0.95f,
        0x1A2B3C4Du
    );
    classic[static_cast<size_t>(ClickType::Regular)] = buildMechanicalClick(
        validRate,
        kClassicNormalModes,
        sizeof(kClassicNormalModes) / sizeof(kClassicNormalModes[0]),
        2600.0,
        3600.0,
        1150.0,
        0.38,
        0.78f,
        0x5E6F7A8Bu
    );
    classic[static_cast<size_t>(ClickType::Subdivision)] = buildMechanicalClick(
        validRate,
        kClassicSubModes,
        sizeof(kClassicSubModes) / sizeof(kClassicSubModes[0]),
        3000.0,
        4000.0,
        1400.0,
        0.20,
        0.42f,
        0x9C0D1E2Fu
    );

    // Profile 1: Digital Click — sharp percussive transients for loud rehearsal rooms
    auto& digital = mBuffers[static_cast<size_t>(SoundProfile::DigitalClick)];
    digital[static_cast<size_t>(ClickType::Accent)] =
        buildDampedSineBurst(validRate, 1500.0, 3000.0, 0.22f, 0.020, 0.0040, 0.0003, 0.95f);
    digital[static_cast<size_t>(ClickType::Regular)] =
        buildDampedSineBurst(validRate, 1000.0, 0.0, 0.0f, 0.018, 0.0036, 0.0003, 0.75f);
    digital[static_cast<size_t>(ClickType::Subdivision)] =
        buildDampedSineBurst(validRate, 750.0, 0.0, 0.0f, 0.016, 0.0030, 0.0003, 0.42f);

    // Profile 2: Woodblock — resonant dual-mode woody knock (~2.57x modal ratio)
    auto& wood = mBuffers[static_cast<size_t>(SoundProfile::Woodblock)];
    wood[static_cast<size_t>(ClickType::Accent)] =
        buildDampedSineBurst(validRate, 1120.0, 2860.0, 0.35f, 0.024, 0.0052, 0.0003, 0.95f);
    wood[static_cast<size_t>(ClickType::Regular)] =
        buildDampedSineBurst(validRate, 820.0, 2110.0, 0.30f, 0.022, 0.0048, 0.0003, 0.76f);
    wood[static_cast<size_t>(ClickType::Subdivision)] =
        buildDampedSineBurst(validRate, 640.0, 1640.0, 0.22f, 0.018, 0.0038, 0.0003, 0.42f);

    // Profile 3: Soft Tick — warmer low-fatigue envelope for long wired-earphone sessions
    auto& soft = mBuffers[static_cast<size_t>(SoundProfile::SoftTick)];
    soft[static_cast<size_t>(ClickType::Accent)] =
        buildDampedSineBurst(validRate, 920.0, 0.0, 0.0f, 0.018, 0.0032, 0.0008, 0.85f);
    soft[static_cast<size_t>(ClickType::Regular)] =
        buildDampedSineBurst(validRate, 660.0, 0.0, 0.0f, 0.016, 0.0028, 0.0008, 0.65f);
    soft[static_cast<size_t>(ClickType::Subdivision)] =
        buildDampedSineBurst(validRate, 480.0, 0.0, 0.0f, 0.014, 0.0024, 0.0008, 0.36f);
}

const std::vector<float>& ClickSynth::getBuffer(
    SoundProfile profile,
    ClickType type
) const noexcept {
    const size_t rawProfile = static_cast<size_t>(profile);
    const size_t profileIdx = rawProfile < kNumSoundProfiles ? rawProfile : 0u;
    const size_t rawType = static_cast<size_t>(type);
    const size_t typeIdx = rawType < kNumClickTypes ? rawType : 1u;
    return mBuffers[profileIdx][typeIdx];
}

std::vector<float> ClickSynth::buildMechanicalClick(
    int32_t sampleRate,
    const ModalComponent* modes,
    size_t modeCount,
    double strikerBandpassHz,
    double strikerSnapStartHz,
    double strikerSnapEndHz,
    double strikerMix,
    float targetPeak,
    uint32_t noiseSeed
) {
    const double sr = static_cast<double>(sampleRate);
    const size_t numSamples = static_cast<size_t>(
        std::max(1.0, std::round(0.035 * sr))
    );
    std::vector<float> buffer(numSamples, 0.0f);

    // Audio EQ Cookbook bandpass biquad (constant 0 dB peak gain, Q = 1.8)
    const double nyquistSafeMax = 0.45 * sr;
    const double fc = std::min(strikerBandpassHz, nyquistSafeMax);
    const double w0 = kTwoPi * fc / sr;
    const double alpha = std::sin(w0) / (2.0 * 1.8);
    const double a0 = 1.0 + alpha;
    const double b0 = alpha / a0;
    const double b2 = -alpha / a0;
    const double a1 = (-2.0 * std::cos(w0)) / a0;
    const double a2 = (1.0 - alpha) / a0;

    double x1 = 0.0;
    double x2 = 0.0;
    double y1 = 0.0;
    double y2 = 0.0;

    uint32_t rng = noiseSeed != 0u ? noiseSeed : 0x6D2B79F5u;
    const double fStart = std::min(strikerSnapStartHz, nyquistSafeMax);
    const double fEnd = std::min(strikerSnapEndHz, nyquistSafeMax);
    double snapPhase = 0.0;

    const size_t attackSamples = std::max<size_t>(
        1, static_cast<size_t>(std::round(0.00025 * sr))
    );
    const size_t fadeOutSamples = std::max<size_t>(
        1, static_cast<size_t>(std::round(0.002 * sr))
    );

    for (size_t i = 0; i < numSamples; ++i) {
        const double t = static_cast<double>(i) / sr;

        // 1. Wooden striker transient (first 2.5ms)
        double striker = 0.0;
        if (t < 0.0025) {
            rng ^= rng << 13;
            rng ^= rng >> 17;
            rng ^= rng << 5;
            const double whiteNoise =
                (static_cast<double>(rng) / 2147483647.5) - 1.0;

            const double filteredNoise =
                b0 * whiteNoise + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = whiteNoise;
            y2 = y1;
            y1 = filteredNoise;

            const double instFreq = (t <= 0.0015)
                ? fStart * std::pow(fEnd / fStart, t / 0.0015)
                : fEnd;
            snapPhase += kTwoPi * instFreq / sr;

            const double strikerRaw = 0.65 * filteredNoise + 0.35 * std::sin(snapPhase);
            double strikerEnv = std::exp(-t / 0.0008);
            if (t > 0.0020) {
                const double taperPhase = (0.0025 - t) / 0.0005;
                strikerEnv *= 0.5 * (1.0 - std::cos(kPi * taperPhase));
            }
            striker = strikerRaw * strikerEnv;
        }

        // 2. Hollow wooden box cavity resonances (modal synthesis)
        double body = 0.0;
        for (size_t m = 0; m < modeCount; ++m) {
            if (modes[m].freqHz < nyquistSafeMax) {
                body += modes[m].amplitude *
                        std::sin(kTwoPi * modes[m].freqHz * t) *
                        std::exp(-t / modes[m].decayTauSec);
            }
        }

        // 3. Finishing: attack ramp, subtle saturation warmth, and tail fade-out
        double combined = body + strikerMix * striker;
        if (i < attackSamples) {
            combined *= static_cast<double>(i) / static_cast<double>(attackSamples);
        }

        float warm = std::tanh(static_cast<float>(combined) * 1.25f);
        if (i >= numSamples - fadeOutSamples) {
            const size_t rem = numSamples - 1 - i;
            const double fadePhase = static_cast<double>(rem) / static_cast<double>(fadeOutSamples);
            warm *= static_cast<float>(0.5 * (1.0 - std::cos(kPi * fadePhase)));
        }
        buffer[i] = warm;
    }

    float maxAbs = 0.0f;
    for (float sample : buffer) {
        maxAbs = std::max(maxAbs, std::abs(sample));
    }
    if (maxAbs > 1e-6f) {
        const float scale = targetPeak / maxAbs;
        for (float& sample : buffer) {
            sample *= scale;
        }
    }

    return buffer;
}

std::vector<float> ClickSynth::buildDampedSineBurst(
    int32_t sampleRate,
    double frequencyHz,
    double harmonicHz,
    float harmonicMix,
    double durationSec,
    double decayTauSec,
    double attackSec,
    float peakAmplitude
) {
    const size_t numSamples = static_cast<size_t>(
        std::max(1.0, std::round(durationSec * static_cast<double>(sampleRate)))
    );
    std::vector<float> buffer(numSamples, 0.0f);

    const size_t attackSamples = std::max<size_t>(
        1, static_cast<size_t>(std::round(attackSec * static_cast<double>(sampleRate)))
    );
    const size_t fadeOutSamples = std::max<size_t>(
        1, static_cast<size_t>(std::round(0.001 * static_cast<double>(sampleRate)))
    );

    for (size_t i = 0; i < numSamples; ++i) {
        const double t = static_cast<double>(i) / static_cast<double>(sampleRate);
        double env = std::exp(-t / decayTauSec);

        if (i < attackSamples) {
            env *= static_cast<double>(i) / static_cast<double>(attackSamples);
        }
        if (i >= numSamples - fadeOutSamples) {
            const size_t rem = numSamples - 1 - i;
            const double fadePhase = static_cast<double>(rem) / static_cast<double>(fadeOutSamples);
            env *= 0.5 * (1.0 - std::cos(kPi * fadePhase));
        }

        double wave = std::sin(kTwoPi * frequencyHz * t);
        if (harmonicHz > 0.0 && harmonicMix > 0.0f) {
            wave += static_cast<double>(harmonicMix) * std::sin(kTwoPi * harmonicHz * t);
            wave /= (1.0 + static_cast<double>(harmonicMix));
        }

        buffer[i] = static_cast<float>(wave * env * static_cast<double>(peakAmplitude));
    }

    return buffer;
}

}  // namespace monotonic
