#include "MetronomeEngine.h"

#include <algorithm>
#include <cmath>
#include <ctime>

#include <android/log.h>

namespace monotonic {

namespace {
constexpr const char* kLogTag = "MonotonicEngine";
constexpr int64_t kNsPerSec = 1000000000LL;
constexpr double kNsPerMinute = 60000000000.0;
constexpr double kDefaultFallbackLatencyMs = 25.0;

inline int64_t readClockMonotonicNs() noexcept {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * kNsPerSec + static_cast<int64_t>(ts.tv_nsec);
}
}  // namespace

MetronomeEngine::~MetronomeEngine() {
    mIsDestroying.store(true, std::memory_order_release);
    mShouldBeRunning.store(false, std::memory_order_release);
    stopStream();
}

bool MetronomeEngine::startStream() {
    std::lock_guard<std::mutex> lock(mStreamMutex);
    if (mIsDestroying.load(std::memory_order_acquire)) {
        return false;
    }
    mShouldBeRunning.store(true, std::memory_order_release);
    if (mStream && mStream->getState() == oboe::StreamState::Started) {
        return true;
    }
    closeStreamLocked();
    return openAndStartLocked();
}

void MetronomeEngine::stopStream() {
    mShouldBeRunning.store(false, std::memory_order_release);
    std::lock_guard<std::mutex> lock(mStreamMutex);
    closeStreamLocked();
    mCurrentBeatIndex.store(-1, std::memory_order_relaxed);
}

bool MetronomeEngine::restartStream() {
    std::lock_guard<std::mutex> lock(mStreamMutex);
    if (mIsDestroying.load(std::memory_order_acquire) ||
        !mShouldBeRunning.load(std::memory_order_acquire)) {
        return false;
    }
    closeStreamLocked();
    return openAndStartLocked();
}

void MetronomeEngine::updateTransport(const TransportParams& params) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    mLastPublishedParams = params;
    mTransportBuffer.publish(params);
}

bool MetronomeEngine::openAndStartLocked() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(oboe::ChannelCount::Mono);
    builder.setUsage(oboe::Usage::Media);
    builder.setContentType(oboe::ContentType::Music);
    builder.setDataCallback(this);
    builder.setErrorCallback(this);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK || !mStream) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            kLogTag,
            "Failed to open Oboe stream: %s",
            oboe::convertToText(result)
        );
        mStream.reset();
        return false;
    }

    const int32_t sampleRate = std::max(mStream->getSampleRate(), 8000);
    const int32_t framesPerBurst = mStream->getFramesPerBurst();
    if (framesPerBurst > 0) {
        mStream->setBufferSizeInFrames(framesPerBurst * 2);
    }

    mClickSynth.generate(sampleRate);

    // Reset audio-thread state while stream is stopped and re-publish latest transport
    mActiveSchedule = TransportParams{};
    mPendingSchedule = TransportParams{};
    mHasPending = false;
    mLastAppliedStateVersion = -1;
    mLastTriggeredStep = -1;
    mSawPreStart = false;
    mVoices.fill(ActiveVoice{});
    mCurrentBeatIndex.store(-1, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> controlLock(mControlMutex);
        mTransportBuffer.publish(mLastPublishedParams);
    }

    mSampleRateAtomic.store(sampleRate, std::memory_order_relaxed);
    mIsExclusiveAtomic.store(
        mStream->getSharingMode() == oboe::SharingMode::Exclusive,
        std::memory_order_relaxed
    );
    mAudioApiAtomic.store(static_cast<int32_t>(mStream->getAudioApi()), std::memory_order_relaxed);

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            kLogTag,
            "Failed to start Oboe stream: %s",
            oboe::convertToText(result)
        );
        closeStreamLocked();
        return false;
    }

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "Oboe stream started: rate=%d Hz, burst=%d, sharing=%s, api=%s",
        sampleRate,
        framesPerBurst,
        oboe::convertToText(mStream->getSharingMode()),
        oboe::convertToText(mStream->getAudioApi())
    );
    return true;
}

void MetronomeEngine::closeStreamLocked() {
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
}

void MetronomeEngine::onErrorAfterClose(oboe::AudioStream* /*audioStream*/, oboe::Result error) {
    if (error == oboe::Result::ErrorDisconnected &&
        mShouldBeRunning.load(std::memory_order_acquire) &&
        !mIsDestroying.load(std::memory_order_acquire)) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "Oboe stream disconnected; restarting stream"
        );
        restartStream();
    }
}

void MetronomeEngine::triggerClick(SoundProfile profile, ClickType type) noexcept {
    size_t targetIdx = 0;
    size_t maxPos = 0;

    for (size_t i = 0; i < kMaxVoices; ++i) {
        if (!mVoices[i].active) {
            targetIdx = i;
            break;
        }
        if (mVoices[i].pos >= maxPos) {
            maxPos = mVoices[i].pos;
            targetIdx = i;
        }
    }

    mVoices[targetIdx].profile = profile;
    mVoices[targetIdx].type = type;
    mVoices[targetIdx].pos = 0;
    mVoices[targetIdx].active = true;
}

oboe::DataCallbackResult MetronomeEngine::onAudioReady(
    oboe::AudioStream* audioStream,
    void* audioData,
    int32_t numFrames
) {
    // STRICT REAL-TIME CALLBACK:
    // No heap allocations, no mutex locks, no JNI, and no logging.
    auto* out = static_cast<float*>(audioData);
    if (!out || numFrames <= 0) {
        return oboe::DataCallbackResult::Continue;
    }
    std::fill(out, out + numFrames, 0.0f);

    const int64_t nowNs = readClockMonotonicNs();

    TransportParams incoming{};
    if (mTransportBuffer.consume(incoming)) {
        if (incoming.stateVersion != mLastAppliedStateVersion) {
            if (!incoming.isPlaying) {
                mActiveSchedule.isPlaying = false;
                mHasPending = false;
                mLastTriggeredStep = -1;
                mSawPreStart = false;
                mCurrentBeatIndex.store(-1, std::memory_order_relaxed);
            } else {
                const int64_t incomingEffective =
                    incoming.localStartTimeNs + incoming.manualOffsetNs;
                const int64_t activeEffective =
                    mActiveSchedule.localStartTimeNs + mActiveSchedule.manualOffsetNs;

                if (mActiveSchedule.isPlaying &&
                    incomingEffective > nowNs &&
                    incomingEffective > activeEffective) {
                    mPendingSchedule = incoming;
                    mHasPending = true;
                } else {
                    mActiveSchedule = incoming;
                    mHasPending = false;
                    mLastTriggeredStep = -1;
                    mSawPreStart = false;
                }
            }
            mLastAppliedStateVersion = incoming.stateVersion;
        } else {
            // Same stateVersion: drift smoothing, manual calibration, or focus ducking
            if (mHasPending) {
                const int64_t delta =
                    incoming.localStartTimeNs - mPendingSchedule.localStartTimeNs;
                mActiveSchedule.localStartTimeNs += delta;
                mPendingSchedule.localStartTimeNs = incoming.localStartTimeNs;
                mPendingSchedule.manualOffsetNs = incoming.manualOffsetNs;
                mPendingSchedule.volume = incoming.volume;
            } else {
                mActiveSchedule.localStartTimeNs = incoming.localStartTimeNs;
            }
            mActiveSchedule.manualOffsetNs = incoming.manualOffsetNs;
            mActiveSchedule.volume = incoming.volume;
        }
    }

    const int32_t sampleRate = std::max(audioStream->getSampleRate(), 8000);
    const int64_t bufferStartFrame = audioStream->getFramesWritten();

    int64_t timestampFramePosition = 0;
    int64_t timestampTimeNs = 0;
    const oboe::Result tsResult = audioStream->getTimestamp(
        CLOCK_MONOTONIC,
        &timestampFramePosition,
        &timestampTimeNs
    );

    bool useHardwareTimestamp = false;
    if (tsResult == oboe::Result::OK && timestampTimeNs > 0) {
        const int64_t bufferStartPlayTimeNs =
            timestampTimeNs + ((bufferStartFrame - timestampFramePosition) * kNsPerSec) / sampleRate;
        const int64_t estimatedLatencyNs = bufferStartPlayTimeNs - nowNs;
        if (estimatedLatencyNs >= -5000000LL && estimatedLatencyNs <= 500000000LL) {
            useHardwareTimestamp = true;
            mMeasuredHardwareLatencyMs.store(
                static_cast<double>(estimatedLatencyNs) / 1000000.0,
                std::memory_order_relaxed
            );
        }
    }

    int64_t fallbackBufferStartPlayNs = 0;
    if (!useHardwareTimestamp) {
        const auto latencyResult = audioStream->calculateLatencyMillis();
        double latencyMs = kDefaultFallbackLatencyMs;
        if (latencyResult == oboe::Result::OK &&
            latencyResult.value() > 0.0 &&
            latencyResult.value() < 500.0) {
            latencyMs = latencyResult.value();
        }
        mMeasuredHardwareLatencyMs.store(latencyMs, std::memory_order_relaxed);
        fallbackBufferStartPlayNs =
            nowNs + static_cast<int64_t>(std::llround(latencyMs * 1000000.0));
    }

    const uint32_t accentBitmask = mAccentBitmask.load(std::memory_order_relaxed);
    const int32_t rawProfile = mSoundProfile.load(std::memory_order_relaxed);
    constexpr int32_t kMaxProfile = static_cast<int32_t>(ClickSynth::kNumSoundProfiles) - 1;
    const SoundProfile activeProfile = static_cast<SoundProfile>(
        rawProfile < 0 ? 0 : (rawProfile > kMaxProfile ? kMaxProfile : rawProfile)
    );

    for (int32_t i = 0; i < numFrames; ++i) {
        const int64_t framePlayTimeNs = useHardwareTimestamp
            ? (timestampTimeNs +
               (((bufferStartFrame + static_cast<int64_t>(i)) - timestampFramePosition) * kNsPerSec) /
                   sampleRate)
            : (fallbackBufferStartPlayNs + (static_cast<int64_t>(i) * kNsPerSec) / sampleRate);

        if (mHasPending) {
            const int64_t pendingEffectiveNs =
                mPendingSchedule.localStartTimeNs + mPendingSchedule.manualOffsetNs;
            if (framePlayTimeNs >= pendingEffectiveNs) {
                mActiveSchedule = mPendingSchedule;
                mHasPending = false;
                mLastTriggeredStep = -1;
                mSawPreStart = true;
            }
        }

        if (mActiveSchedule.isPlaying &&
            mActiveSchedule.bpm >= 20.0 && mActiveSchedule.bpm <= 500.0 &&
            mActiveSchedule.beatsPerBar >= 1 && mActiveSchedule.beatsPerBar <= 16 &&
            mActiveSchedule.subdivision >= 1 && mActiveSchedule.subdivision <= 4) {
            const int64_t effectiveStartNs =
                mActiveSchedule.localStartTimeNs + mActiveSchedule.manualOffsetNs;

            if (framePlayTimeNs < effectiveStartNs) {
                mSawPreStart = true;
                mCurrentBeatIndex.store(-1, std::memory_order_relaxed);
            } else {
                const int64_t elapsedNs = framePlayTimeNs - effectiveStartNs;
                const double stepDurationNs =
                    (kNsPerMinute / mActiveSchedule.bpm) /
                    static_cast<double>(mActiveSchedule.subdivision);
                const int64_t stepIndex =
                    static_cast<int64_t>(static_cast<double>(elapsedNs) / stepDurationNs);

                bool shouldTrigger = false;
                if (mLastTriggeredStep < 0) {
                    if (stepIndex == 0 && (mSawPreStart || elapsedNs < 20000000LL)) {
                        shouldTrigger = true;
                    } else {
                        // Joined mid-stream: align step state silently until next beat edge
                        mLastTriggeredStep = stepIndex;
                        const int32_t beatIdx = static_cast<int32_t>(
                            (stepIndex / mActiveSchedule.subdivision) % mActiveSchedule.beatsPerBar
                        );
                        mCurrentBeatIndex.store(beatIdx, std::memory_order_relaxed);
                    }
                } else if (stepIndex > mLastTriggeredStep) {
                    shouldTrigger = true;
                }

                if (shouldTrigger) {
                    mLastTriggeredStep = stepIndex;
                    const int32_t beatIdx = static_cast<int32_t>(
                        (stepIndex / mActiveSchedule.subdivision) % mActiveSchedule.beatsPerBar
                    );
                    const uint32_t accentLevel = (beatIdx >= 0 && beatIdx < 16)
                        ? ((accentBitmask >> (static_cast<uint32_t>(beatIdx) * 2u)) & 0x3u)
                        : 1u;
                    const bool isMainBeat = (stepIndex % mActiveSchedule.subdivision == 0);

                    if (accentLevel != 0u) {
                        if (isMainBeat) {
                            triggerClick(
                                activeProfile,
                                accentLevel >= 2u ? ClickType::Accent : ClickType::Regular
                            );
                        } else {
                            triggerClick(activeProfile, ClickType::Subdivision);
                        }
                    }

                    if (isMainBeat) {
                        mCurrentBeatIndex.store(beatIdx, std::memory_order_relaxed);
                        mBeatSequence.fetch_add(1, std::memory_order_relaxed);
                    }
                }
            }
        }

        float mix = 0.0f;
        for (size_t v = 0; v < kMaxVoices; ++v) {
            ActiveVoice& voice = mVoices[v];
            if (!voice.active) continue;

            const std::vector<float>& buf = mClickSynth.getBuffer(voice.profile, voice.type);
            if (voice.pos < buf.size()) {
                mix += buf[voice.pos];
                ++voice.pos;
            }
            if (voice.pos >= buf.size()) {
                voice.active = false;
            }
        }

        mix *= mActiveSchedule.volume;
        out[i] = std::max(-1.0f, std::min(1.0f, mix));
    }

    return oboe::DataCallbackResult::Continue;
}

}  // namespace monotonic
