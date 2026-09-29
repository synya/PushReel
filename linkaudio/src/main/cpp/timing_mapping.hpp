/*
 * Copyright (C) 2026 PushReel contributors
 * Licensed under the GNU General Public License version 2 or later.
 */
#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>

namespace pushreel::linkaudio {

struct BufferTimingIdentity {
  std::uint64_t count{};
  std::array<std::uint8_t, 8> sessionId{};
  std::uint32_t bufferFrames{};
  std::uint32_t sampleRate{};

  friend bool operator==(const BufferTimingIdentity& lhs,
                         const BufferTimingIdentity& rhs) noexcept {
    return lhs.count == rhs.count && lhs.sessionId == rhs.sessionId
           && lhs.bufferFrames == rhs.bufferFrames && lhs.sampleRate == rhs.sampleRate;
  }
};

inline bool addFrameOffsetUs(std::int64_t bufferBeginUs,
                             std::uint32_t frameOffset,
                             std::uint32_t bufferFrames,
                             std::uint32_t sampleRate,
                             std::int64_t& result) noexcept;

struct FallbackTimingResult {
  std::int64_t bufferBeginRawUs{};
  bool active{};
  bool entered{};
  bool reanchored{};
  bool countDiscontinuity{};
};

// Repairs streams whose sender keeps publishing an obviously stale Link beat anchor. Once
// entered, timing advances by the exact PCM duration. Receipt time is used only to establish a
// new epoch on entry or when the session/format changes; it is never followed packet by packet.
class FallbackTimingNormalizer {
 public:
  explicit FallbackTimingNormalizer(
      const std::int64_t staleThresholdUs = 2'000'000) noexcept
      : staleThresholdUs_(staleThresholdUs) {}

  FallbackTimingResult normalize(const BufferTimingIdentity& identity,
                                 const std::int64_t metadataBeginRawUs,
                                 const std::int64_t receivedRawUs) noexcept {
    const auto lateness = static_cast<long double>(receivedRawUs)
                          - static_cast<long double>(metadataBeginRawUs);
    const auto stale = staleThresholdUs_ >= 0
                       && lateness > static_cast<long double>(staleThresholdUs_);
    if (!active_ && !stale) {
      return {metadataBeginRawUs, false, false, false, false};
    }

    // A failed clock conversion may retry a later partial read of this same buffer.
    // Keep its original anchor instead of advancing by an entire buffer twice.
    if (active_ && previousIdentity_ == identity) {
      return {previousBeginRawUs_, true, false, false, false};
    }

    const auto entered = !active_;
    const auto sameFormat = active_ && previousIdentity_.sessionId == identity.sessionId
                            && previousIdentity_.sampleRate == identity.sampleRate;
    const auto countContinuous = sameFormat
                                 && identity.count == previousIdentity_.count + std::uint64_t{1};
    const auto reanchored = entered || !sameFormat;
    auto beginRawUs = sameFormat ? previousExpectedEndRawUs_ : receivedRawUs;
    if (active_ && beginRawUs < previousExpectedEndRawUs_) {
      beginRawUs = previousExpectedEndRawUs_;
    }

    std::int64_t expectedEndRawUs{};
    if (!addFrameOffsetUs(beginRawUs,
                          identity.bufferFrames,
                          identity.bufferFrames,
                          identity.sampleRate,
                          expectedEndRawUs)) {
      reset();
      return {metadataBeginRawUs, false, false, false, false};
    }
    active_ = true;
    previousIdentity_ = identity;
    previousBeginRawUs_ = beginRawUs;
    previousExpectedEndRawUs_ = expectedEndRawUs;
    return {beginRawUs,
            true,
            entered,
            reanchored,
            sameFormat && !countContinuous};
  }

  void reset() noexcept {
    active_ = false;
    previousIdentity_ = {};
    previousBeginRawUs_ = 0;
    previousExpectedEndRawUs_ = 0;
  }

 private:
  BufferTimingIdentity previousIdentity_{};
  std::int64_t previousBeginRawUs_{};
  std::int64_t previousExpectedEndRawUs_{};
  std::int64_t staleThresholdUs_{};
  bool active_{};
};

struct TimingDiagnosticsSnapshot {
  std::uint64_t timedBufferCount{};
  std::int64_t latestPresentationLatenessUs{};
  std::int64_t minPresentationLatenessUs{};
  std::int64_t maxPresentationLatenessUs{};
  std::uint64_t interBufferTimingCount{};
  std::int64_t latestInterBufferDeltaUs{};
  std::int64_t minInterBufferDeltaUs{};
  std::int64_t maxInterBufferDeltaUs{};
  std::uint64_t bufferCountDiscontinuityCount{};
  std::uint64_t timestampDiscontinuityCount{};
};

// Access must be serialized by the owner. Observations are made once per Link buffer on the
// non-realtime JNI reader thread, never in the Link Audio callback.
class TimingDiagnostics {
 public:
  static constexpr std::int64_t kTimestampDiscontinuityThresholdUs = 5'000;

  void observe(const BufferTimingIdentity& identity,
               const std::int64_t bufferBeginRawUs,
               const std::int64_t receivedRawUs) noexcept {
    const auto latenessUs = receivedRawUs - bufferBeginRawUs;
    latestPresentationLatenessUs_ = latenessUs;
    if (timedBufferCount_ == 0) {
      minPresentationLatenessUs_ = latenessUs;
      maxPresentationLatenessUs_ = latenessUs;
    } else {
      minPresentationLatenessUs_ = std::min(minPresentationLatenessUs_, latenessUs);
      maxPresentationLatenessUs_ = std::max(maxPresentationLatenessUs_, latenessUs);
    }
    ++timedBufferCount_;

    const auto sameStream = hasPreviousBuffer_
                            && previousIdentity_.sessionId == identity.sessionId
                            && previousIdentity_.sampleRate == identity.sampleRate;
    if (sameStream) {
      if (identity.count != previousIdentity_.count + std::uint64_t{1}) {
        ++bufferCountDiscontinuityCount_;
      }
      const auto deltaUs = bufferBeginRawUs - previousExpectedEndRawUs_;
      latestInterBufferDeltaUs_ = deltaUs;
      if (interBufferTimingCount_ == 0) {
        minInterBufferDeltaUs_ = deltaUs;
        maxInterBufferDeltaUs_ = deltaUs;
      } else {
        minInterBufferDeltaUs_ = std::min(minInterBufferDeltaUs_, deltaUs);
        maxInterBufferDeltaUs_ = std::max(maxInterBufferDeltaUs_, deltaUs);
      }
      ++interBufferTimingCount_;
      if (deltaUs < -kTimestampDiscontinuityThresholdUs
          || deltaUs > kTimestampDiscontinuityThresholdUs) {
        ++timestampDiscontinuityCount_;
      }
    }

    std::int64_t expectedEndRawUs{};
    if (addFrameOffsetUs(bufferBeginRawUs,
                         identity.bufferFrames,
                         identity.bufferFrames,
                         identity.sampleRate,
                         expectedEndRawUs)) {
      previousIdentity_ = identity;
      previousExpectedEndRawUs_ = expectedEndRawUs;
      hasPreviousBuffer_ = true;
    } else {
      hasPreviousBuffer_ = false;
    }
  }

  TimingDiagnosticsSnapshot snapshot() const noexcept {
    return TimingDiagnosticsSnapshot{timedBufferCount_,
                                     latestPresentationLatenessUs_,
                                     minPresentationLatenessUs_,
                                     maxPresentationLatenessUs_,
                                     interBufferTimingCount_,
                                     latestInterBufferDeltaUs_,
                                     minInterBufferDeltaUs_,
                                     maxInterBufferDeltaUs_,
                                     bufferCountDiscontinuityCount_,
                                     timestampDiscontinuityCount_};
  }

  void reset() noexcept { *this = TimingDiagnostics{}; }

 private:
  BufferTimingIdentity previousIdentity_{};
  std::int64_t previousExpectedEndRawUs_{};
  std::uint64_t timedBufferCount_{};
  std::uint64_t interBufferTimingCount_{};
  std::uint64_t bufferCountDiscontinuityCount_{};
  std::uint64_t timestampDiscontinuityCount_{};
  std::int64_t latestPresentationLatenessUs_{};
  std::int64_t minPresentationLatenessUs_{};
  std::int64_t maxPresentationLatenessUs_{};
  std::int64_t latestInterBufferDeltaUs_{};
  std::int64_t minInterBufferDeltaUs_{};
  std::int64_t maxInterBufferDeltaUs_{};
  bool hasPreviousBuffer_{};
};

// Access must be serialized by the owner. Keeping this type lock-free makes it usable in host
// tests and leaves the JNI owner in control of its threading policy.
class TimingAnchorCache {
 public:
  bool get(const BufferTimingIdentity& identity, std::int64_t& anchorUs) const noexcept {
    if (!valid_ || !(identity_ == identity)) return false;
    anchorUs = anchorUs_;
    return true;
  }

  void put(const BufferTimingIdentity& identity, const std::int64_t anchorUs) noexcept {
    identity_ = identity;
    anchorUs_ = anchorUs;
    valid_ = true;
  }

  void reset() noexcept { valid_ = false; }

 private:
  BufferTimingIdentity identity_{};
  std::int64_t anchorUs_{};
  bool valid_{};
};

// Link uses CLOCK_MONOTONIC_RAW on Android while Android's elapsedRealtimeNanos uses
// CLOCK_BOOTTIME. The BOOTTIME read is bracketed by RAW reads so scheduling delays can be
// detected instead of becoming a timestamp error.
inline bool mapRawToElapsedRealtimeUs(
    const std::int64_t targetRawUs,
    const std::int64_t rawBeforeUs,
    const std::int64_t bootUs,
    const std::int64_t rawAfterUs,
    const std::int64_t maxBracketUs,
    std::int64_t& result) noexcept {
  const auto bracketUs = static_cast<long double>(rawAfterUs)
                         - static_cast<long double>(rawBeforeUs);
  if (maxBracketUs < 0 || bracketUs < 0.0L
      || bracketUs > static_cast<long double>(maxBracketUs)) {
    return false;
  }

  const auto rawAtBoot = static_cast<long double>(rawBeforeUs)
                         + bracketUs / 2.0L;
  const auto elapsed = static_cast<long double>(bootUs)
                       + static_cast<long double>(targetRawUs) - rawAtBoot;
  if (!std::isfinite(elapsed)
      || elapsed < static_cast<long double>(std::numeric_limits<std::int64_t>::min())
      || elapsed > static_cast<long double>(std::numeric_limits<std::int64_t>::max())) {
    return false;
  }
  result = static_cast<std::int64_t>(std::llround(elapsed));
  return true;
}

// Link Audio PCM is not resampled. Once the buffer-begin anchor is fixed, every partial read
// advances solely by its source frame offset and sample rate, independent of tempo changes.
inline bool addFrameOffsetUs(const std::int64_t bufferBeginUs,
                             const std::uint32_t frameOffset,
                             const std::uint32_t bufferFrames,
                             const std::uint32_t sampleRate,
                             std::int64_t& result) noexcept {
  if (sampleRate == 0 || bufferFrames == 0 || frameOffset > bufferFrames) return false;
  const auto elapsed = static_cast<long double>(bufferBeginUs)
                       + static_cast<long double>(frameOffset) * 1'000'000.0L
                             / static_cast<long double>(sampleRate);
  if (!std::isfinite(elapsed)
      || elapsed < static_cast<long double>(std::numeric_limits<std::int64_t>::min())
      || elapsed > static_cast<long double>(std::numeric_limits<std::int64_t>::max())) {
    return false;
  }
  result = static_cast<std::int64_t>(std::llround(elapsed));
  return true;
}

}  // namespace pushreel::linkaudio
