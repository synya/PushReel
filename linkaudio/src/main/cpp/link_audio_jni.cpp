/*
 * Copyright (C) 2026 PushReel contributors
 *
 * This file integrates Ableton Link, which is available under GPLv2+ or a
 * proprietary Ableton license. See third_party/ableton-link/LICENSE.md.
 */

#include <jni.h>
#include <android/log.h>

#include <ableton/LinkAudio.hpp>

#include "pcm_fifo.hpp"
#include "timing_mapping.hpp"

#include <algorithm>
#include <array>
#include <cinttypes>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <iomanip>
#include <memory>
#include <mutex>
#include <optional>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>
#include <time.h>

namespace {

constexpr double kRecordingQuantum = 4.0;
constexpr std::int64_t kMaxClockBracketUs = 2'000;
constexpr int kClockMappingAttempts = 3;
constexpr std::uint32_t kTimingMappingLogBudget = 12;

struct LinkInstance {
  explicit LinkInstance(std::string peerName)
      : link(120.0, std::move(peerName)), fifo(std::make_shared<pushreel::linkaudio::PcmFifo>()) {}

  void select(const std::optional<ableton::ChannelId>& channelId) {
    auto replacement = std::make_shared<pushreel::linkaudio::PcmFifo>();
    std::lock_guard<std::mutex> lock(sourceMutex);
    source.reset();
    fifo = replacement;
    {
      std::lock_guard<std::mutex> timingLock(timingMutex);
      timingAnchorCache.reset();
      timingDiagnostics.reset();
      fallbackTiming.reset();
      timingMappingLogsRemaining = kTimingMappingLogBudget;
    }
    if (channelId) {
      const auto callbackClock = link.clock();
      source = std::make_unique<ableton::LinkAudioSource>(
          link,
          *channelId,
          [replacement, callbackClock](
              const ableton::LinkAudioSource::BufferHandle buffer) noexcept {
            pushreel::linkaudio::PcmFifo::BufferMetadata metadata{
                buffer.info.count,
                buffer.info.sessionBeatTime,
                buffer.info.tempo,
                {},
                callbackClock.micros().count()};
            std::copy(
                buffer.info.sessionId.begin(),
                buffer.info.sessionId.end(),
                metadata.sessionId.begin());
            replacement->push(
                buffer.samples,
                buffer.info.numFrames,
                buffer.info.numChannels,
                buffer.info.sampleRate,
                metadata);
          });
    }
  }

  void clearSource() {
    std::lock_guard<std::mutex> lock(sourceMutex);
    source.reset();
    std::lock_guard<std::mutex> timingLock(timingMutex);
    timingAnchorCache.reset();
    timingDiagnostics.reset();
    fallbackTiming.reset();
    timingMappingLogsRemaining = kTimingMappingLogBudget;
  }

  bool hasSource() const {
    std::lock_guard<std::mutex> lock(sourceMutex);
    return source != nullptr;
  }

  std::shared_ptr<pushreel::linkaudio::PcmFifo> fifoSnapshot() const {
    std::lock_guard<std::mutex> lock(sourceMutex);
    return fifo;
  }

  std::uint32_t discardBufferedAudio() {
    const auto discarded = fifoSnapshot()->discardBufferedFrames();
    std::lock_guard<std::mutex> timingLock(timingMutex);
    timingAnchorCache.reset();
    timingDiagnostics.reset();
    fallbackTiming.reset();
    timingMappingLogsRemaining = kTimingMappingLogBudget;
    return discarded;
  }

  ableton::LinkAudio link;
  std::shared_ptr<pushreel::linkaudio::PcmFifo> fifo;
  mutable std::mutex sourceMutex;
  std::unique_ptr<ableton::LinkAudioSource> source;
  std::mutex timingMutex;
  pushreel::linkaudio::TimingAnchorCache timingAnchorCache;
  pushreel::linkaudio::TimingDiagnostics timingDiagnostics;
  pushreel::linkaudio::FallbackTimingNormalizer fallbackTiming;
  std::uint32_t timingMappingLogsRemaining{kTimingMappingLogBudget};
};

std::optional<std::int64_t> elapsedRealtimeUsForRead(
    LinkInstance& instance,
    const pushreel::linkaudio::PcmFifo::ReadResult& read) {
  struct TimingMappingLog {
    std::uint64_t count{};
    std::uint32_t bufferFrames{};
    std::uint32_t sampleRate{};
    double sessionBeatTime{};
    double tempo{};
    std::array<std::uint8_t, 8> sessionId{};
    double beginBeats{};
    std::int64_t metadataBeginRawUs{};
    std::int64_t effectiveBeginRawUs{};
    std::int64_t receivedRawUs{};
    std::int64_t mappedElapsedRealtimeUs{};
    std::int64_t interBufferDeltaUs{};
    bool hasInterBufferDelta{};
    bool countDiscontinuity{};
    bool fallbackActive{};
    bool fallbackEntered{};
    bool fallbackReanchored{};
    std::uint32_t logIndex{};
  };
  if (read.frames == 0 || read.sampleRate == 0 || read.bufferFrames == 0
      || read.bufferOffsetFrames > read.bufferFrames
      || !std::isfinite(read.metadata.sessionBeatTime)
      || !std::isfinite(read.metadata.tempo) || read.metadata.tempo <= 0.0) {
    return std::nullopt;
  }

  auto info = ableton::LinkAudioSource::BufferHandle::Info{};
  info.numChannels = pushreel::linkaudio::PcmFifo::kOutputChannels;
  info.numFrames = read.bufferFrames;
  info.sampleRate = read.sampleRate;
  info.count = read.metadata.count;
  info.sessionBeatTime = read.metadata.sessionBeatTime;
  info.tempo = read.metadata.tempo;
  std::copy(
      read.metadata.sessionId.begin(), read.metadata.sessionId.end(), info.sessionId.begin());

  // This runs on the JNI reader thread, never in the Link realtime callback.
  const auto state = instance.link.captureAppSessionState();
  const auto beginBeats = info.beginBeats(state, kRecordingQuantum);
  if (!beginBeats || !std::isfinite(*beginBeats)) {
    // beginBeats rejects buffers from a different Link session. This check intentionally runs
    // before consulting the cache so an old-session anchor can never bypass validation.
    return std::nullopt;
  }
  const auto identity = pushreel::linkaudio::BufferTimingIdentity{
      read.metadata.count,
      read.metadata.sessionId,
      read.bufferFrames,
      read.sampleRate};
  std::int64_t bufferBeginElapsedRealtimeUs{};
  std::optional<TimingMappingLog> timingMappingLog;
  {
    std::lock_guard<std::mutex> lock(instance.timingMutex);
    if (!instance.timingAnchorCache.get(identity, bufferBeginElapsedRealtimeUs)) {
      const auto metadataBeginRawUs =
          state.timeAtBeat(*beginBeats, kRecordingQuantum).count();
      const auto fallback = instance.fallbackTiming.normalize(
          identity, metadataBeginRawUs, read.metadata.receivedRawUs);
      const auto effectiveBeginRawUs = fallback.bufferBeginRawUs;
      const auto clock = instance.link.clock();
      bool mapped = false;
      for (auto attempt = 0; attempt < kClockMappingAttempts && !mapped; ++attempt) {
        const auto rawBeforeUs = clock.micros().count();
        ::timespec bootTime{};
        if (::clock_gettime(CLOCK_BOOTTIME, &bootTime) != 0) return std::nullopt;
        const auto rawAfterUs = clock.micros().count();
        const auto bootUs = static_cast<std::int64_t>(bootTime.tv_sec) * 1'000'000
                            + static_cast<std::int64_t>(bootTime.tv_nsec) / 1'000;
        mapped = pushreel::linkaudio::mapRawToElapsedRealtimeUs(
            effectiveBeginRawUs,
            rawBeforeUs,
            bootUs,
            rawAfterUs,
            kMaxClockBracketUs,
            bufferBeginElapsedRealtimeUs);
      }
      if (!mapped) return std::nullopt;
      const auto diagnosticsBefore = instance.timingDiagnostics.snapshot();
      instance.timingDiagnostics.observe(
          identity, metadataBeginRawUs, read.metadata.receivedRawUs);
      const auto diagnosticsAfter = instance.timingDiagnostics.snapshot();
      instance.timingAnchorCache.put(identity, bufferBeginElapsedRealtimeUs);
      if (instance.timingMappingLogsRemaining > 0) {
        const auto logIndex = kTimingMappingLogBudget - instance.timingMappingLogsRemaining + 1;
        timingMappingLog = TimingMappingLog{
            read.metadata.count,
            read.bufferFrames,
            read.sampleRate,
            read.metadata.sessionBeatTime,
            read.metadata.tempo,
            read.metadata.sessionId,
            *beginBeats,
            metadataBeginRawUs,
            effectiveBeginRawUs,
            read.metadata.receivedRawUs,
            bufferBeginElapsedRealtimeUs,
            diagnosticsAfter.latestInterBufferDeltaUs,
            diagnosticsAfter.interBufferTimingCount
                > diagnosticsBefore.interBufferTimingCount,
            diagnosticsAfter.bufferCountDiscontinuityCount
                > diagnosticsBefore.bufferCountDiscontinuityCount,
            fallback.active,
            fallback.entered,
            fallback.reanchored,
            logIndex};
        --instance.timingMappingLogsRemaining;
      }
    }
  }

  if (timingMappingLog) {
    const auto currentRawUs = static_cast<std::int64_t>(
        instance.link.clock().micros().count());
    const auto presentationLatenessUs =
        timingMappingLog->receivedRawUs - timingMappingLog->metadataBeginRawUs;
    __android_log_print(
        timingMappingLog->fallbackEntered ? ANDROID_LOG_WARN : ANDROID_LOG_INFO,
        "PushReelLinkAudio",
        "timing-chunk index=%" PRIu32 "/%" PRIu32 " count=%" PRIu64
        " numFrames=%" PRIu32 " sampleRate=%" PRIu32
        " sessionBeatTime=%.6f tempo=%.6f sessionId=%02x%02x%02x%02x"
        " beginBeats=%.6f metadataBeginRawUs=%" PRId64
        " effectiveBeginRawUs=%" PRId64
        " receivedRawUs=%" PRId64 " currentRawUs=%" PRId64
        " latenessUs=%" PRId64 " mappedElapsedRealtimeUs=%" PRId64
        " interBufferDeltaUs=%" PRId64 " interBufferDeltaValid=%d"
        " countDiscontinuity=%d fallbackActive=%d fallbackEntered=%d"
        " fallbackReanchored=%d",
        timingMappingLog->logIndex,
        kTimingMappingLogBudget,
        timingMappingLog->count,
        timingMappingLog->bufferFrames,
        timingMappingLog->sampleRate,
        timingMappingLog->sessionBeatTime,
        timingMappingLog->tempo,
        static_cast<unsigned int>(timingMappingLog->sessionId[0]),
        static_cast<unsigned int>(timingMappingLog->sessionId[1]),
        static_cast<unsigned int>(timingMappingLog->sessionId[2]),
        static_cast<unsigned int>(timingMappingLog->sessionId[3]),
        timingMappingLog->beginBeats,
        timingMappingLog->metadataBeginRawUs,
        timingMappingLog->effectiveBeginRawUs,
        timingMappingLog->receivedRawUs,
        currentRawUs,
        presentationLatenessUs,
        timingMappingLog->mappedElapsedRealtimeUs,
        timingMappingLog->interBufferDeltaUs,
        timingMappingLog->hasInterBufferDelta ? 1 : 0,
        timingMappingLog->countDiscontinuity ? 1 : 0,
        timingMappingLog->fallbackActive ? 1 : 0,
        timingMappingLog->fallbackEntered ? 1 : 0,
        timingMappingLog->fallbackReanchored ? 1 : 0);
  }

  std::int64_t result{};
  if (!pushreel::linkaudio::addFrameOffsetUs(
      bufferBeginElapsedRealtimeUs,
      read.bufferOffsetFrames,
      read.bufferFrames,
      read.sampleRate,
      result)) return std::nullopt;
  return result;
}

std::mutex gRegistryMutex;
std::unordered_map<std::int64_t, std::shared_ptr<LinkInstance>> gInstances;
std::int64_t gNextHandle = 1;

void throwJava(JNIEnv* env, const char* className, const char* message) noexcept {
  if (env->ExceptionCheck()) {
    return;
  }
  const auto exceptionClass = env->FindClass(className);
  if (exceptionClass != nullptr) {
    env->ThrowNew(exceptionClass, message);
  }
}

void translateCurrentException(JNIEnv* env, const char* fallback) noexcept {
  try {
    throw;
  } catch (const std::exception& error) {
    throwJava(env, "java/lang/IllegalStateException", error.what());
  } catch (...) {
    throwJava(env, "java/lang/IllegalStateException", fallback);
  }
}

void logCurrentException(const char* operation) noexcept {
  try {
    throw;
  } catch (const std::exception& error) {
    __android_log_print(ANDROID_LOG_ERROR, "PushReelLinkAudio", "%s: %s", operation, error.what());
  } catch (...) {
    __android_log_print(ANDROID_LOG_ERROR, "PushReelLinkAudio", "%s failed", operation);
  }
}

std::shared_ptr<LinkInstance> findInstance(JNIEnv* env, jlong handle) {
  std::lock_guard<std::mutex> lock(gRegistryMutex);
  const auto it = gInstances.find(static_cast<std::int64_t>(handle));
  if (it == gInstances.end()) {
    throwJava(env, "java/lang/IllegalStateException", "Link Audio handle is closed or invalid");
    return nullptr;
  }
  return it->second;
}

std::string fromJavaBytes(JNIEnv* env, jbyteArray value) {
  if (value == nullptr) {
    return {};
  }
  const auto size = env->GetArrayLength(value);
  std::string result(static_cast<std::size_t>(size), '\0');
  if (size > 0) {
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(result.data()));
  }
  if (env->ExceptionCheck()) {
    return {};
  }
  return result;
}

jbyteArray toJavaBytes(JNIEnv* env, const std::string& value) {
  const auto result = env->NewByteArray(static_cast<jsize>(value.size()));
  if (result != nullptr && !value.empty()) {
    env->SetByteArrayRegion(
        result,
        0,
        static_cast<jsize>(value.size()),
        reinterpret_cast<const jbyte*>(value.data()));
  }
  return result;
}

std::string idToHex(const ableton::link_audio::Id& id) {
  std::ostringstream stream;
  stream << std::hex << std::setfill('0');
  for (const auto byte : id) {
    stream << std::setw(2) << static_cast<unsigned int>(byte);
  }
  return stream.str();
}

std::optional<ableton::ChannelId> parseChannelId(const std::string& value) {
  if (value.size() != 16) {
    return std::nullopt;
  }
  ableton::ChannelId result{};
  const auto nibble = [](const char character) -> int {
    if (character >= '0' && character <= '9') return character - '0';
    if (character >= 'a' && character <= 'f') return character - 'a' + 10;
    if (character >= 'A' && character <= 'F') return character - 'A' + 10;
    return -1;
  };
  for (std::size_t index = 0; index < result.size(); ++index) {
    const auto high = nibble(value[index * 2]);
    const auto low = nibble(value[index * 2 + 1]);
    if (high < 0 || low < 0) return std::nullopt;
    result[index] = static_cast<std::uint8_t>((high << 4) | low);
  }
  return result;
}

jlong unsignedToJavaLong(const std::uint32_t value) noexcept {
  return static_cast<jlong>(static_cast<std::uint64_t>(value));
}

jlong doubleBits(const double value) noexcept {
  std::uint64_t bits = 0;
  std::memcpy(&bits, &value, sizeof(bits));
  return static_cast<jlong>(bits);
}

jlong packId(const std::array<std::uint8_t, 8>& id) noexcept {
  std::uint64_t packed = 0;
  for (const auto byte : id) packed = (packed << 8) | byte;
  return static_cast<jlong>(packed);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_pushreel_linkaudio_JniNativeBridge_nativeCreate(
    JNIEnv* env, jobject, jbyteArray peerName) {
  try {
    auto instance = std::make_shared<LinkInstance>(fromJavaBytes(env, peerName));
    if (env->ExceptionCheck()) {
      return 0;
    }
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    const auto handle = gNextHandle++;
    gInstances.emplace(handle, std::move(instance));
    return static_cast<jlong>(handle);
  } catch (const std::exception& error) {
    throwJava(env, "java/lang/IllegalStateException", error.what());
  } catch (...) {
    throwJava(env, "java/lang/IllegalStateException", "Unable to create Link Audio");
  }
  return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pushreel_linkaudio_JniNativeBridge_nativeClose(JNIEnv*, jobject, jlong handle) noexcept {
  try {
    std::shared_ptr<LinkInstance> removed;
    {
      std::lock_guard<std::mutex> lock(gRegistryMutex);
      const auto it = gInstances.find(static_cast<std::int64_t>(handle));
      if (it == gInstances.end()) {
        return;
      }
      removed = std::move(it->second);
      gInstances.erase(it);
    }
    try {
      removed->clearSource();
    } catch (...) {
      logCurrentException("clear Link Audio source during close");
    }
    try {
      removed->link.enableLinkAudio(false);
    } catch (...) {
      logCurrentException("disable Link Audio during close");
    }
    try {
      removed->link.enable(false);
    } catch (...) {
      logCurrentException("disable Link during close");
    }
    removed.reset();
  } catch (...) {
    logCurrentException("close");
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pushreel_linkaudio_JniNativeBridge_nativeSelectChannel(
    JNIEnv* env, jobject, jlong handle, jbyteArray channelId) {
  try {
    const auto instance = findInstance(env, handle);
    if (!instance) return;
    if (channelId == nullptr) {
      instance->select(std::nullopt);
      return;
    }
    const auto parsed = parseChannelId(fromJavaBytes(env, channelId));
    if (!parsed) {
      throwJava(env, "java/lang/IllegalArgumentException", "Channel ID must be 16 hexadecimal characters");
      return;
    }
    instance->select(parsed);
  } catch (...) {
    translateCurrentException(env, "Unable to select Link Audio channel");
  }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pushreel_linkaudio_JniNativeBridge_nativeReadAudioFrames(
    JNIEnv* env, jobject, jlong handle, jshortArray destination, jint requestedFrames) {
  try {
    const auto instance = findInstance(env, handle);
    if (!instance || destination == nullptr || requestedFrames <= 0) return nullptr;
    const auto destinationSamples = env->GetArrayLength(destination);
    const auto destinationFrames = destinationSamples / 2;
    const auto frames = std::min(requestedFrames, destinationFrames);
    if (frames <= 0) return nullptr;
    auto* output = env->GetShortArrayElements(destination, nullptr);
    if (output == nullptr) return nullptr;
    const auto read =
        instance->fifoSnapshot()->read(output, static_cast<std::uint32_t>(frames));
    env->ReleaseShortArrayElements(destination, output, 0);
    const auto elapsedRealtimeUs = elapsedRealtimeUsForRead(*instance, read);
    // Keep the original eight fields stable and append local timing fields.
    const std::array<jlong, 10> values{
        unsignedToJavaLong(read.frames),
        unsignedToJavaLong(read.bufferOffsetFrames),
        unsignedToJavaLong(read.bufferFrames),
        unsignedToJavaLong(read.sampleRate),
        static_cast<jlong>(read.metadata.count),
        doubleBits(read.metadata.sessionBeatTime),
        doubleBits(read.metadata.tempo),
        packId(read.metadata.sessionId),
        elapsedRealtimeUs ? 1 : 0,
        elapsedRealtimeUs.value_or(0)};
    const auto result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
      env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
  } catch (...) {
    translateCurrentException(env, "Unable to read Link Audio PCM");
    return nullptr;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pushreel_linkaudio_JniNativeBridge_nativeDiscardBufferedAudio(
    JNIEnv* env, jobject, jlong handle) {
  try {
    const auto instance = findInstance(env, handle);
    if (!instance) return 0;
    return unsignedToJavaLong(instance->discardBufferedAudio());
  } catch (...) {
    translateCurrentException(env, "Unable to discard buffered Link Audio PCM");
    return 0;
  }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pushreel_linkaudio_JniNativeBridge_nativeGetAudioStatus(
    JNIEnv* env, jobject, jlong handle) {
  try {
    const auto instance = findInstance(env, handle);
    if (!instance) return nullptr;
    const auto status = instance->fifoSnapshot()->status();
    pushreel::linkaudio::TimingDiagnosticsSnapshot timingStatus;
    {
      std::lock_guard<std::mutex> lock(instance->timingMutex);
      timingStatus = instance->timingDiagnostics.snapshot();
    }
    const std::array<jlong, 22> values{
        instance->hasSource() ? 1 : 0,
        unsignedToJavaLong(status.sampleRate),
        pushreel::linkaudio::PcmFifo::kOutputChannels,
        unsignedToJavaLong(status.bufferedFrames),
        pushreel::linkaudio::PcmFifo::kCapacityFrames,
        unsignedToJavaLong(status.receivedFrames),
        unsignedToJavaLong(status.readFrames),
        unsignedToJavaLong(status.droppedFrames),
        unsignedToJavaLong(status.overflowCount),
        unsignedToJavaLong(status.underrunFrames),
        unsignedToJavaLong(status.underrunCount),
        unsignedToJavaLong(status.invalidBufferCount),
        static_cast<jlong>(timingStatus.timedBufferCount),
        static_cast<jlong>(timingStatus.latestPresentationLatenessUs),
        static_cast<jlong>(timingStatus.minPresentationLatenessUs),
        static_cast<jlong>(timingStatus.maxPresentationLatenessUs),
        static_cast<jlong>(timingStatus.interBufferTimingCount),
        static_cast<jlong>(timingStatus.latestInterBufferDeltaUs),
        static_cast<jlong>(timingStatus.minInterBufferDeltaUs),
        static_cast<jlong>(timingStatus.maxInterBufferDeltaUs),
        static_cast<jlong>(timingStatus.bufferCountDiscontinuityCount),
        static_cast<jlong>(timingStatus.timestampDiscontinuityCount)};
    const auto result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
      env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
  } catch (...) {
    translateCurrentException(env, "Unable to read Link Audio PCM status");
    return nullptr;
  }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pushreel_linkaudio_JniNativeBridge_nativeGetPeakLevels(
    JNIEnv* env, jobject, jlong handle) {
  try {
    const auto instance = findInstance(env, handle);
    if (!instance) return nullptr;
    const auto peaks = instance->fifoSnapshot()->drainPeakLevels();
    const std::array<jlong, 3> values{
        unsignedToJavaLong(peaks.left),
        unsignedToJavaLong(peaks.right),
        unsignedToJavaLong(peaks.framesObserved)};
    const auto result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
      env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
  } catch (...) {
    translateCurrentException(env, "Unable to read Link Audio peaks");
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pushreel_linkaudio_JniNativeBridge_nativeSetEnabled(
    JNIEnv* env, jobject, jlong handle, jboolean enabled) {
  try {
    const auto instance = findInstance(env, handle);
    if (!instance) {
      return;
    }
    const bool shouldEnable = enabled == JNI_TRUE;
    if (shouldEnable) {
      instance->link.enable(true);
      instance->link.enableLinkAudio(true);
    } else {
      instance->link.enableLinkAudio(false);
      instance->link.enable(false);
    }
  } catch (...) {
    translateCurrentException(env, "Unable to change Link Audio state");
  }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pushreel_linkaudio_JniNativeBridge_nativeGetStatus(
    JNIEnv* env, jobject, jlong handle) {
  try {
    const auto instance = findInstance(env, handle);
    if (!instance) {
      return nullptr;
    }
    const std::array<jlong, 3> values{
        instance->link.isEnabled() ? 1 : 0,
        instance->link.isLinkAudioEnabled() ? 1 : 0,
        static_cast<jlong>(instance->link.numPeers())};
    const auto result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
      env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
  } catch (...) {
    translateCurrentException(env, "Unable to read Link Audio status");
    return nullptr;
  }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_pushreel_linkaudio_JniNativeBridge_nativeGetChannels(
    JNIEnv* env, jobject, jlong handle) {
  try {
    const auto instance = findInstance(env, handle);
    if (!instance) {
      return nullptr;
    }
    const auto channels = instance->link.channels();
    const auto byteArrayClass = env->FindClass("[B");
    if (byteArrayClass == nullptr) {
      return nullptr;
    }
    constexpr std::size_t fieldsPerChannel = 4;
    const auto result = env->NewObjectArray(
        static_cast<jsize>(channels.size() * fieldsPerChannel), byteArrayClass, nullptr);
    if (result == nullptr) {
      return nullptr;
    }
    std::size_t outputIndex = 0;
    for (const auto& channel : channels) {
      const std::array<std::string, fieldsPerChannel> fields{
          idToHex(channel.id), channel.name, idToHex(channel.peerId), channel.peerName};
      for (const auto& field : fields) {
        const auto value = toJavaBytes(env, field);
        if (value == nullptr) {
          return nullptr;
        }
        env->SetObjectArrayElement(result, static_cast<jsize>(outputIndex++), value);
        env->DeleteLocalRef(value);
      }
    }
    return result;
  } catch (...) {
    translateCurrentException(env, "Unable to read Link Audio channels");
    return nullptr;
  }
}
