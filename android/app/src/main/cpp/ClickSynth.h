#pragma once

#include <cstdint>
#include <vector>

namespace monotonic {

enum class ClickType : int32_t {
    Accent = 0,
    Regular = 1,
    Subdivision = 2
};

/**
 * Pre-generates short percussive damped-sine click waveforms in memory during
 * stream initialization so the real-time audio callback performs zero allocation
 * and zero math setup per sample beyond array reads.
 */
class ClickSynth {
public:
    void generate(int32_t sampleRate);

    const std::vector<float>& getBuffer(ClickType type) const noexcept;
    int32_t sampleRate() const noexcept { return mSampleRate; }

private:
    static std::vector<float> buildDampedSineBurst(
        int32_t sampleRate,
        double frequencyHz,
        double harmonicHz,
        float harmonicMix,
        double durationSec,
        double decayTauSec,
        float peakAmplitude
    );

    int32_t mSampleRate{48000};
    std::vector<float> mAccentBuffer;
    std::vector<float> mRegularBuffer;
    std::vector<float> mSubdivisionBuffer;
};

}  // namespace monotonic
