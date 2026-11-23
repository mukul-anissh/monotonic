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

    // 20ms sharp percussive click waveforms tuned for crisp wired-earphone transients
    mAccentBuffer = buildDampedSineBurst(
        validRate,
        1500.0,
        3000.0,
        0.22f,
        0.020,
        0.0040,
        0.95f
    );
    mRegularBuffer = buildDampedSineBurst(
        validRate,
        1000.0,
        0.0,
        0.0f,
        0.018,
        0.0036,
        0.75f
    );
    mSubdivisionBuffer = buildDampedSineBurst(
        validRate,
        750.0,
        0.0,
        0.0f,
        0.016,
        0.0030,
        0.42f
    );
}

const std::vector<float>& ClickSynth::getBuffer(ClickType type) const noexcept {
    switch (type) {
        case ClickType::Accent:
            return mAccentBuffer;
        case ClickType::Regular:
            return mRegularBuffer;
        case ClickType::Subdivision:
            return mSubdivisionBuffer;
    }
    return mRegularBuffer;
}

std::vector<float> ClickSynth::buildDampedSineBurst(
    int32_t sampleRate,
    double frequencyHz,
    double harmonicHz,
    float harmonicMix,
    double durationSec,
    double decayTauSec,
    float peakAmplitude
) {
    const size_t numSamples = static_cast<size_t>(
        std::max(1.0, std::round(durationSec * static_cast<double>(sampleRate)))
    );
    std::vector<float> buffer(numSamples, 0.0f);

    const double attackSec = 0.0003;  // 0.3ms anti-pop linear attack
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
