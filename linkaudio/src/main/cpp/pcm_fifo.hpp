/*
 * Copyright (C) 2026 PushReel contributors
 * Licensed under the GNU General Public License version 2 or later.
 */
#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace pushreel::linkaudio {

// One producer (the Link callback) and one consumer (the JNI audio worker).
// The uint32_t positions intentionally wrap. Unsigned subtraction stays valid because each
// power-of-two queue is bounded to far less than half the counter range.
template <std::uint32_t CapacityFrames = 131'072,
          std::uint32_t DescriptorCapacity = 2'048>
class BasicPcmFifo {
 public:
  static constexpr std::uint32_t kCapacityFrames = CapacityFrames;
  static constexpr std::uint32_t kOutputChannels = 2;
  static constexpr std::uint32_t kDescriptorCapacity = DescriptorCapacity;

  struct BufferMetadata {
    std::uint64_t count{};
    double sessionBeatTime{};
    double tempo{};
    std::array<std::uint8_t, 8> sessionId{};
  };

  struct ReadResult {
    std::uint32_t frames{};
    std::uint32_t bufferOffsetFrames{};
    std::uint32_t bufferFrames{};
    std::uint32_t sampleRate{};
    BufferMetadata metadata{};
  };

  struct Status {
    std::uint32_t sampleRate{};
    std::uint32_t bufferedFrames{};
    std::uint32_t receivedFrames{};
    std::uint32_t readFrames{};
    std::uint32_t droppedFrames{};
    std::uint32_t overflowCount{};
    std::uint32_t underrunFrames{};
    std::uint32_t underrunCount{};
    std::uint32_t invalidBufferCount{};
  };

  static_assert(kCapacityFrames > 0 && (kCapacityFrames & (kCapacityFrames - 1)) == 0);
  static_assert(kDescriptorCapacity > 0
                && (kDescriptorCapacity & (kDescriptorCapacity - 1)) == 0);
  static_assert(kCapacityFrames < (std::numeric_limits<std::uint32_t>::max() / 2));
  static_assert(kDescriptorCapacity < (std::numeric_limits<std::uint32_t>::max() / 2));
  static_assert(sizeof(std::uint32_t) == sizeof(unsigned int));
  static_assert(ATOMIC_INT_LOCK_FREE == 2);

  // initialPosition lets host tests exercise uint32_t wrap without billions of writes.
  explicit BasicPcmFifo(const std::uint32_t initialPosition = 0) noexcept
      : readIndex_(initialPosition),
        writeIndex_(initialPosition),
        descriptorReadIndex_(initialPosition),
        descriptorWriteIndex_(initialPosition) {}

  BasicPcmFifo(const BasicPcmFifo&) = delete;
  BasicPcmFifo& operator=(const BasicPcmFifo&) = delete;

  void push(const std::int16_t* samples,
            const std::size_t numFrames,
            const std::size_t numChannels,
            const std::uint32_t sampleRate,
            const BufferMetadata& metadata) noexcept {
    if (samples == nullptr || numFrames == 0 || (numChannels != 1 && numChannels != 2)
        || sampleRate == 0 || numFrames > std::numeric_limits<std::uint32_t>::max()) {
      invalidBufferCount_.fetch_add(1, std::memory_order_relaxed);
      return;
    }
    const auto frames = static_cast<std::uint32_t>(numFrames);
    receivedFrames_.fetch_add(frames, std::memory_order_relaxed);
    if (frames > kCapacityFrames) {
      recordOverflow(frames);
      return;
    }

    const auto write = writeIndex_.load(std::memory_order_relaxed);
    const auto read = readIndex_.load(std::memory_order_acquire);
    const auto descriptorWrite = descriptorWriteIndex_.load(std::memory_order_relaxed);
    const auto descriptorRead = descriptorReadIndex_.load(std::memory_order_acquire);
    const auto buffered = write - read;
    const auto bufferedDescriptors = descriptorWrite - descriptorRead;
    if (buffered > kCapacityFrames || bufferedDescriptors > kDescriptorCapacity) {
      invalidBufferCount_.fetch_add(1, std::memory_order_relaxed);
      return;
    }
    const auto currentSampleRate = sampleRate_.load(std::memory_order_relaxed);
    if (buffered != 0 && currentSampleRate != 0 && currentSampleRate != sampleRate) {
      droppedFrames_.fetch_add(frames, std::memory_order_relaxed);
      invalidBufferCount_.fetch_add(1, std::memory_order_relaxed);
      return;
    }
    if (frames > kCapacityFrames - buffered
        || bufferedDescriptors == kDescriptorCapacity) {
      // Preserve all older data and drop the complete newest Link buffer.
      recordOverflow(frames);
      return;
    }

    for (std::uint32_t frame = 0; frame < frames; ++frame) {
      const auto destination = ((write + frame) & kFrameMask) * kOutputChannels;
      const auto source = static_cast<std::size_t>(frame) * numChannels;
      storage_[destination] = samples[source];
      storage_[destination + 1] = samples[source + (numChannels == 2 ? 1 : 0)];
    }
    descriptors_[descriptorWrite & kDescriptorMask] =
        Descriptor{write, frames, 0, sampleRate, metadata};
    sampleRate_.store(sampleRate, std::memory_order_relaxed);
    writeIndex_.store(write + frames, std::memory_order_relaxed);
    // The descriptor is the commit point; this release publishes PCM and timing metadata.
    descriptorWriteIndex_.store(descriptorWrite + 1, std::memory_order_release);
  }

  // A read never crosses a Link buffer boundary and returns that buffer's exact frame range.
  ReadResult read(std::int16_t* destination, const std::uint32_t requestedFrames) noexcept {
    if (destination == nullptr || requestedFrames == 0) return {};
    const auto descriptorRead = descriptorReadIndex_.load(std::memory_order_relaxed);
    const auto descriptorWrite = descriptorWriteIndex_.load(std::memory_order_acquire);
    if (descriptorRead == descriptorWrite) {
      recordUnderrun(requestedFrames);
      return {};
    }
    if (descriptorWrite - descriptorRead > kDescriptorCapacity) {
      invalidBufferCount_.fetch_add(1, std::memory_order_relaxed);
      return {};
    }

    auto& descriptor = descriptors_[descriptorRead & kDescriptorMask];
    if (descriptor.consumedFrames > descriptor.frames) {
      invalidBufferCount_.fetch_add(1, std::memory_order_relaxed);
      return {};
    }
    const auto descriptorRemaining = descriptor.frames - descriptor.consumedFrames;
    const auto frames = std::min(requestedFrames, descriptorRemaining);
    const auto read = readIndex_.load(std::memory_order_relaxed);
    const auto write = writeIndex_.load(std::memory_order_relaxed);
    const auto available = write - read;
    if (available > kCapacityFrames || frames > available
        || read != descriptor.startFrame + descriptor.consumedFrames) {
      invalidBufferCount_.fetch_add(1, std::memory_order_relaxed);
      return {};
    }
    for (std::uint32_t frame = 0; frame < frames; ++frame) {
      const auto source = ((read + frame) & kFrameMask) * kOutputChannels;
      destination[frame * kOutputChannels] = storage_[source];
      destination[frame * kOutputChannels + 1] = storage_[source + 1];
    }

    const auto result = ReadResult{frames,
                                   descriptor.consumedFrames,
                                   descriptor.frames,
                                   descriptor.sampleRate,
                                   descriptor.metadata};
    descriptor.consumedFrames += frames;
    readIndex_.store(read + frames, std::memory_order_release);
    readFrames_.fetch_add(frames, std::memory_order_relaxed);
    if (descriptor.consumedFrames == descriptor.frames) {
      descriptorReadIndex_.store(descriptorRead + 1, std::memory_order_release);
    }
    return result;
  }

  Status status() const noexcept {
    // Read consumer position first. Concurrent progress can only overestimate fill; loading in
    // the opposite order could underflow after the consumer advances.
    const auto read = readIndex_.load(std::memory_order_acquire);
    const auto write = writeIndex_.load(std::memory_order_acquire);
    const auto buffered = write - read;
    return Status{sampleRate_.load(std::memory_order_relaxed),
                  buffered <= kCapacityFrames ? buffered : 0,
                  receivedFrames_.load(std::memory_order_relaxed),
                  readFrames_.load(std::memory_order_relaxed),
                  droppedFrames_.load(std::memory_order_relaxed),
                  overflowCount_.load(std::memory_order_relaxed),
                  underrunFrames_.load(std::memory_order_relaxed),
                  underrunCount_.load(std::memory_order_relaxed),
                  invalidBufferCount_.load(std::memory_order_relaxed)};
  }

 private:
  struct Descriptor {
    std::uint32_t startFrame;
    std::uint32_t frames;
    std::uint32_t consumedFrames;
    std::uint32_t sampleRate;
    BufferMetadata metadata;
  };

  void recordOverflow(const std::uint32_t frames) noexcept {
    droppedFrames_.fetch_add(frames, std::memory_order_relaxed);
    overflowCount_.fetch_add(1, std::memory_order_relaxed);
  }
  void recordUnderrun(const std::uint32_t frames) noexcept {
    underrunFrames_.fetch_add(frames, std::memory_order_relaxed);
    underrunCount_.fetch_add(1, std::memory_order_relaxed);
  }

  static constexpr std::uint32_t kFrameMask = kCapacityFrames - 1;
  static constexpr std::uint32_t kDescriptorMask = kDescriptorCapacity - 1;
  alignas(64) std::array<std::int16_t, kCapacityFrames * kOutputChannels> storage_{};
  alignas(64) std::array<Descriptor, kDescriptorCapacity> descriptors_{};
  alignas(64) std::atomic<std::uint32_t> readIndex_;
  alignas(64) std::atomic<std::uint32_t> writeIndex_;
  alignas(64) std::atomic<std::uint32_t> descriptorReadIndex_;
  alignas(64) std::atomic<std::uint32_t> descriptorWriteIndex_;
  std::atomic<std::uint32_t> sampleRate_{0};
  std::atomic<std::uint32_t> receivedFrames_{0};
  std::atomic<std::uint32_t> readFrames_{0};
  std::atomic<std::uint32_t> droppedFrames_{0};
  std::atomic<std::uint32_t> overflowCount_{0};
  std::atomic<std::uint32_t> underrunFrames_{0};
  std::atomic<std::uint32_t> underrunCount_{0};
  std::atomic<std::uint32_t> invalidBufferCount_{0};
};

using PcmFifo = BasicPcmFifo<>;

}  // namespace pushreel::linkaudio
