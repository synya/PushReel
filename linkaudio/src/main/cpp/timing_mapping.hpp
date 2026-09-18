/*
 * Copyright (C) 2026 PushReel contributors
 * Licensed under the GNU General Public License version 2 or later.
 */
#pragma once

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
