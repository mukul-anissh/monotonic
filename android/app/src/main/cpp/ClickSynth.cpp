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

    // Profile 0: Digital Click — sharp percussive transients for loud rehearsal rooms
    auto& digital = mBuffers[static_cast<size_t>(SoundProfile::DigitalClick)];
    digital[static_cast<size_t>(ClickType::Accent)] =
        buildDampedSineBurst(validRate, 1500.0, 3000.0, 0.22f, 0.020, 0.0040, 0.0003, 0.95f);
    digital[static_cast<size_t>(ClickType::Regular)] =
        buildDampedSineBurst(validRate, 1000.0, 0.0, 0.0f, 0.018, 0.0036, 0.0003, 0.75f);
    digital[static_cast<size_t>(ClickType::Subdivision)] =
        buildDampedSineBurst(validRate, 750.0, 0.0, 0.0f, 0.016, 0.0030, 0.0003, 0.42f);

    // Profile 1: Woodblock — resonant dual-mode woody knock (~2.57x modal ratio)
    auto& wood = mBuffers[static_cast<size_t>(SoundProfile::Woodblock)];
    wood[static_cast<size_t>(ClickType::Accent)] =
        buildDampedSineBurst(validRate, 1120.0, 2860.0, 0.35f, 0.024, 0.0052, 0.0003, 0.95f);
    wood[static_cast<size_t>(ClickType::Regular)] =
        buildDampedSineBurst(validRate, 820.0, 2110.0, 0.30f, 0.022, 0.0048, 0.0003, 0.76f);
    wood[static_cast<size_t>(ClickType::Subdivision)] =
        buildDampedSineBurst(validRate, 640.0, 1640.0, 0.22f, 0.018, 0.0038, 0.0003, 0.42f);

    // Profile 2: Soft Tick — warmer low-fatigue envelope for long wired-earphone sessions
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
