/*
 * Copyright (C) 2026 PushReel contributors
 *
 * Licensed under the GNU General Public License version 2 or later.
 */
#include "../../main/cpp/pcm_fifo.hpp"
#include "../../main/cpp/timing_mapping.hpp"

#include <array>
#include <cassert>
#include <cstdint>
#include <memory>
#include <vector>

using pushreel::linkaudio::PcmFifo;
using TinyPcmFifo = pushreel::linkaudio::BasicPcmFifo<8, 8>;

namespace {

PcmFifo::BufferMetadata metadata(const std::uint64_t count) {
  return PcmFifo::BufferMetadata{
      count,
      12.5 + static_cast<double>(count),
      120.0,
      {1, 2, 3, 4, 5, 6, 7, static_cast<std::uint8_t>(count)}};
}

void testMonoAndTimingMetadata() {
  auto fifo = std::make_unique<PcmFifo>();
  const std::array<std::int16_t, 3> mono{1, -2, 3};
  fifo->push(mono.data(), 3, 1, 48'000, metadata(7));

  std::array<std::int16_t, 8> stereo{};
  const auto read = fifo->read(stereo.data(), 4);
  assert(read.frames == 3);
  assert(read.bufferOffsetFrames == 0);
  assert(read.bufferFrames == 3);
  assert(read.sampleRate == 48'000);
  assert(read.metadata.count == 7);
  assert(read.metadata.sessionBeatTime == 19.5);
  assert((stereo == std::array<std::int16_t, 8>{1, 1, -2, -2, 3, 3, 0, 0}));

  auto status = fifo->status();
  assert(status.receivedFrames == 3);
  assert(status.readFrames == 3);
  assert(status.underrunCount == 0);
  assert(fifo->read(stereo.data(), 1).frames == 0);
  status = fifo->status();
  assert(status.underrunFrames == 1);
  assert(status.underrunCount == 1);
}

void testReadsDoNotCrossLinkBufferBoundaries() {
  auto fifo = std::make_unique<PcmFifo>();
  const std::array<std::int16_t, 4> first{10, 11, 12, 13};
  const std::array<std::int16_t, 4> second{20, 21, 22, 23};
  fifo->push(first.data(), 2, 2, 48'000, metadata(1));
  fifo->push(second.data(), 2, 2, 48'000, metadata(2));

  std::array<std::int16_t, 8> output{};
  const auto firstRead = fifo->read(output.data(), 4);
  assert(firstRead.frames == 2);
  assert(firstRead.metadata.count == 1);
  assert((output[0] == 10 && output[1] == 11 && output[2] == 12 && output[3] == 13));
  const auto secondRead = fifo->read(output.data(), 4);
  assert(secondRead.frames == 2);
  assert(secondRead.metadata.count == 2);
  assert((output[0] == 20 && output[1] == 21 && output[2] == 22 && output[3] == 23));
}

void testOverflowDropsWholeNewestBuffer() {
  auto fifo = std::make_unique<PcmFifo>();
  std::vector<std::int16_t> full(PcmFifo::kCapacityFrames * 2);
  fifo->push(full.data(), PcmFifo::kCapacityFrames, 2, 48'000, metadata(1));
  const std::array<std::int16_t, 2> newest{7, 8};
  fifo->push(newest.data(), 1, 2, 48'000, metadata(2));

  const auto status = fifo->status();
  assert(status.bufferedFrames == PcmFifo::kCapacityFrames);
  assert(status.droppedFrames == 1);
  assert(status.overflowCount == 1);
}

void testSampleRateChangeAndPhysicalWrap() {
  auto fifo = std::make_unique<PcmFifo>();
  const std::array<std::int16_t, 2> frame{7, 8};
  fifo->push(frame.data(), 1, 2, 48'000, metadata(1));
  fifo->push(frame.data(), 1, 2, 44'100, metadata(2));
  auto status = fifo->status();
  assert(status.bufferedFrames == 1);
  assert(status.droppedFrames == 1);
  assert(status.invalidBufferCount == 1);

  std::array<std::int16_t, 2> output{};
  assert(fifo->read(output.data(), 1).frames == 1);

  std::vector<std::int16_t> nearCapacity((PcmFifo::kCapacityFrames - 2) * 2);
  fifo->push(
      nearCapacity.data(), PcmFifo::kCapacityFrames - 2, 2, 44'100, metadata(3));
  std::vector<std::int16_t> drained((PcmFifo::kCapacityFrames - 2) * 2);
  assert(fifo->read(drained.data(), PcmFifo::kCapacityFrames - 2).frames
         == PcmFifo::kCapacityFrames - 2);

  const std::array<std::int16_t, 8> acrossBoundary{1, 2, 3, 4, 5, 6, 7, 8};
  fifo->push(acrossBoundary.data(), 4, 2, 44'100, metadata(4));
  std::array<std::int16_t, 8> wrappedOutput{};
  const auto wrappedRead = fifo->read(wrappedOutput.data(), 4);
  assert(wrappedRead.frames == 4);
  assert(wrappedRead.metadata.count == 4);
  assert(wrappedOutput == acrossBoundary);
}

void testUnsignedPositionWrap() {
  TinyPcmFifo fifo{UINT32_MAX - 2};
  const std::array<std::int16_t, 8> input{11, 12, 21, 22, 31, 32, 41, 42};
  const TinyPcmFifo::BufferMetadata wrapMetadata{
      9, 21.5, 120.0, {1, 2, 3, 4, 5, 6, 7, 9}};
  fifo.push(input.data(), 4, 2, 48'000, wrapMetadata);

  assert(fifo.status().bufferedFrames == 4);
  std::array<std::int16_t, 8> output{};
  const auto read = fifo.read(output.data(), 4);
  assert(read.frames == 4);
  assert(read.metadata.count == 9);
  assert(output == input);
  assert(fifo.status().bufferedFrames == 0);
}

void testTimingMappingUsesSampleRateAndStableCachedAnchor() {
  std::int64_t anchor{};
  assert(pushreel::linkaudio::mapRawToElapsedRealtimeUs(
      1'000'000, 2'000'000, 3'000'100, 2'000'200, 2'000, anchor));
  assert(anchor == 2'000'000);

  const pushreel::linkaudio::BufferTimingIdentity identity{
      7, {1, 2, 3, 4, 5, 6, 7, 8}, 480, 48'000};
  pushreel::linkaudio::TimingAnchorCache cache;
  cache.put(identity, anchor);
  std::int64_t cachedAnchor{};
  assert(cache.get(identity, cachedAnchor));
  assert(cachedAnchor == anchor);

  std::int64_t firstPartial{};
  std::int64_t secondPartial{};
  assert(pushreel::linkaudio::addFrameOffsetUs(anchor, 120, 480, 48'000, firstPartial));
  assert(pushreel::linkaudio::addFrameOffsetUs(
      cachedAnchor, 240, 480, 48'000, secondPartial));
  assert(firstPartial == anchor + 2'500);
  assert(secondPartial == anchor + 5'000);

  // Tempo and a beat-derived buffer end are deliberately absent: PCM duration follows the
  // source sample rate because PushReel does not resample.
  assert(!pushreel::linkaudio::mapRawToElapsedRealtimeUs(
      1'000'000, 2'000'000, 3'000'000, 2'003'000, 2'000, anchor));
  assert(!pushreel::linkaudio::addFrameOffsetUs(anchor, 481, 480, 48'000, firstPartial));
  assert(!pushreel::linkaudio::addFrameOffsetUs(anchor, 0, 480, 0, firstPartial));

  const auto changedIdentity = pushreel::linkaudio::BufferTimingIdentity{
      7, {1, 2, 3, 4, 5, 6, 7, 8}, 480, 44'100};
  assert(!cache.get(changedIdentity, cachedAnchor));
  cache.reset();
  assert(!cache.get(identity, cachedAnchor));
}

}  // namespace

int main() {
  testMonoAndTimingMetadata();
  testReadsDoNotCrossLinkBufferBoundaries();
  testOverflowDropsWholeNewestBuffer();
  testSampleRateChangeAndPhysicalWrap();
  testUnsignedPositionWrap();
  testTimingMappingUsesSampleRateAndStableCachedAnchor();
}
