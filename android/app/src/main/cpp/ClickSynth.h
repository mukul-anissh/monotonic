#pragma once

#include <array>
#include <cstdint>
#include <vector>

namespace monotonic {

enum class ClickType : int32_t {
    Accent = 0,
    Regular = 1,
    Subdivision = 2
};

enum class SoundProfile : int32_t {
    DigitalClick = 0,
    Woodblock = 1,
    SoftTick = 2
};

/**
 * Pre-generates short percussive click waveforms across multiple sound profiles
 * in memory during stream initialization so the real-time audio callback performs
 * zero allocation and zero math setup per sample beyond array reads.
 */
class ClickSynth {
public:
    static constexpr size_t kNumSoundProfiles = 3;
    static constexpr size_t kNumClickTypes = 3;

    void generate(int32_t sampleRate);

    const std::vector<float>& getBuffer(SoundProfile profile, ClickType type) const noexcept;
    int32_t sampleRate() const noexcept { return mSampleRate; }

private:
    static std::vector<float> buildDampedSineBurst(
        int32_t sampleRate,
        double frequencyHz,
        double harmonicHz,
        float harmonicMix,
        double durationSec,
        double decayTauSec,
        double attackSec,
        float peakAmplitude
    );

    int32_t mSampleRate{48000};
    std::array<std::array<std::vector<float>, kNumClickTypes>, kNumSoundProfiles> mBuffers;
};

}  // namespace monotonic
