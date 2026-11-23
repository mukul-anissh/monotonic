#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <mutex>

#include <oboe/Oboe.h>

#include "ClickSynth.h"

namespace monotonic {

struct TransportParams {
    bool isPlaying{false};
    int64_t localStartTimeNs{0};
    double bpm{120.0};
    int32_t beatsPerBar{4};
    int32_t subdivision{1};
    int64_t manualOffsetNs{0};
    float volume{0.85f};
    int64_t stateVersion{0};
};

/**
 * Single-producer, single-consumer wait-free triple buffer for passing transport
 * snapshots from JNI control calls to the real-time audio callback without locks.
 */
template <typename T>
class TripleBuffer {
public:
    TripleBuffer() {
        mMiddle.store(static_cast<uint8_t>(2u << 1u), std::memory_order_relaxed);
    }

    void publish(const T& value) noexcept {
        mSlots[mWriterIdx] = value;
        const uint8_t nextMiddle = static_cast<uint8_t>((mWriterIdx << 1u) | 1u);
        const uint8_t prev = mMiddle.exchange(nextMiddle, std::memory_order_acq_rel);
        mWriterIdx = static_cast<uint8_t>(prev >> 1u);
    }

    bool consume(T& out) noexcept {
        const uint8_t curr = mMiddle.load(std::memory_order_acquire);
        if ((curr & 1u) == 0u) {
            return false;
        }
        const uint8_t nextMiddle = static_cast<uint8_t>(mReaderIdx << 1u);
        const uint8_t prev = mMiddle.exchange(nextMiddle, std::memory_order_acq_rel);
        mReaderIdx = static_cast<uint8_t>(prev >> 1u);
        out = mSlots[mReaderIdx];
        return true;
    }

private:
    std::array<T, 3> mSlots{};
    uint8_t mWriterIdx{0};
    uint8_t mReaderIdx{1};
    std::atomic<uint8_t> mMiddle{0};
};

/**
 * Low-latency Oboe output engine that schedules precomputed click bursts against
 * hardware output presentation timestamps on CLOCK_MONOTONIC.
 */
class MetronomeEngine final : public oboe::AudioStreamDataCallback,
                              public oboe::AudioStreamErrorCallback {
public:
    MetronomeEngine() = default;
    ~MetronomeEngine() override;

    MetronomeEngine(const MetronomeEngine&) = delete;
    MetronomeEngine& operator=(const MetronomeEngine&) = delete;

    bool startStream();
    void stopStream();
    bool restartStream();

    void updateTransport(const TransportParams& params);

    void setAccentBitmask(uint32_t mask) noexcept {
        mAccentBitmask.store(mask, std::memory_order_relaxed);
    }

    void setSoundProfile(int32_t profileIndex) noexcept {
        const int32_t clamped = profileIndex < 0 ? 0 : (profileIndex > 2 ? 2 : profileIndex);
        mSoundProfile.store(clamped, std::memory_order_relaxed);
    }

    int32_t getCurrentBeatIndex() const noexcept {
        return mCurrentBeatIndex.load(std::memory_order_relaxed);
    }

    int64_t getBeatSequence() const noexcept {
        return mBeatSequence.load(std::memory_order_relaxed);
    }

    double getMeasuredHardwareLatencyMs() const noexcept {
        return mMeasuredHardwareLatencyMs.load(std::memory_order_relaxed);
    }

    int32_t getSampleRate() const noexcept {
        return mSampleRateAtomic.load(std::memory_order_relaxed);
    }

    bool isExclusiveSharingMode() const noexcept {
        return mIsExclusiveAtomic.load(std::memory_order_relaxed);
    }

    int32_t getAudioApi() const noexcept {
        return mAudioApiAtomic.load(std::memory_order_relaxed);
    }

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames
    ) override;

    void onErrorAfterClose(oboe::AudioStream* audioStream, oboe::Result error) override;

private:
    struct ActiveVoice {
        SoundProfile profile{SoundProfile::DigitalClick};
        ClickType type{ClickType::Regular};
        size_t pos{0};
        bool active{false};
    };

    bool openAndStartLocked();
    void closeStreamLocked();
    void triggerClick(SoundProfile profile, ClickType type) noexcept;

    std::mutex mStreamMutex;
    std::mutex mControlMutex;
    std::shared_ptr<oboe::AudioStream> mStream;

    std::atomic<bool> mShouldBeRunning{false};
    std::atomic<bool> mIsDestroying{false};

    ClickSynth mClickSynth;
    TripleBuffer<TransportParams> mTransportBuffer;
    TransportParams mLastPublishedParams{};

    // Audio-thread-only playback state (reset while stream is stopped)
    TransportParams mActiveSchedule{};
    TransportParams mPendingSchedule{};
    bool mHasPending{false};
    int64_t mLastAppliedStateVersion{-1};
    int64_t mLastTriggeredStep{-1};
    bool mSawPreStart{false};
    static constexpr size_t kMaxVoices = 4;
    std::array<ActiveVoice, kMaxVoices> mVoices{};

    // Lock-free local sound & accent preferences
    std::atomic<uint32_t> mAccentBitmask{0x55555556u};
    std::atomic<int32_t> mSoundProfile{0};

    // Lock-free diagnostics exposed to Kotlin UI
    std::atomic<int32_t> mCurrentBeatIndex{-1};
    std::atomic<int64_t> mBeatSequence{0};
    std::atomic<double> mMeasuredHardwareLatencyMs{0.0};
    std::atomic<int32_t> mSampleRateAtomic{48000};
    std::atomic<bool> mIsExclusiveAtomic{false};
    std::atomic<int32_t> mAudioApiAtomic{0};
};

}  // namespace monotonic
