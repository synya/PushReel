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
      {1, 2, 3, 4, 5, 6, 7, static_cast<std::uint8_t>(count)},
      1'000'000 + static_cast<std::int64_t>(count)};
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
  assert(read.metadata.receivedRawUs == 1'000'007);
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
      9, 21.5, 120.0, {1, 2, 3, 4, 5, 6, 7, 9}, 1'000'009};
  fifo.push(input.data(), 4, 2, 48'000, wrapMetadata);

  assert(fifo.status().bufferedFrames == 4);
  std::array<std::int16_t, 8> output{};
  const auto read = fifo.read(output.data(), 4);
  assert(read.frames == 4);
  assert(read.metadata.count == 9);
  assert(output == input);
  assert(fifo.status().bufferedFrames == 0);
}

void testDiscardBufferedFramesSkipsCommittedData() {
  TinyPcmFifo fifo;
  const auto tinyMetadata = [](const std::uint64_t count) {
    return TinyPcmFifo::BufferMetadata{
        count,
        12.5 + static_cast<double>(count),
        120.0,
        {1, 2, 3, 4, 5, 6, 7, static_cast<std::uint8_t>(count)},
        1'000'000 + static_cast<std::int64_t>(count)};
  };
  const std::array<std::int16_t, 8> first{11, 12, 21, 22, 31, 32, 41, 42};
  const std::array<std::int16_t, 4> second{51, 52, 61, 62};
  fifo.push(first.data(), 4, 2, 48'000, tinyMetadata(1));
  fifo.push(second.data(), 2, 2, 48'000, tinyMetadata(2));

  std::array<std::int16_t, 4> partial{};
  assert(fifo.read(partial.data(), 2).frames == 2);
  assert(fifo.discardBufferedFrames() == 4);
  assert(fifo.status().bufferedFrames == 0);
  assert(fifo.discardBufferedFrames() == 0);

  const std::array<std::int16_t, 4> newest{71, 72, 81, 82};
  fifo.push(newest.data(), 2, 2, 48'000, tinyMetadata(3));
  std::array<std::int16_t, 4> output{};
  const auto read = fifo.read(output.data(), 2);
  assert(read.frames == 2);
  assert(read.metadata.count == 3);
  assert(output == newest);
}

void testDiscardBufferedFramesAcrossUnsignedWrap() {
  TinyPcmFifo fifo{UINT32_MAX - 2};
  const std::array<std::int16_t, 8> input{11, 12, 21, 22, 31, 32, 41, 42};
  const TinyPcmFifo::BufferMetadata wrapMetadata{
      9, 21.5, 120.0, {1, 2, 3, 4, 5, 6, 7, 9}, 1'000'009};
  fifo.push(input.data(), 4, 2, 48'000, wrapMetadata);

  assert(fifo.discardBufferedFrames() == 4);
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

void testTimingDiagnosticsTrackLatenessContinuityAndTimestampGaps() {
  pushreel::linkaudio::TimingDiagnostics diagnostics;
  const std::array<std::uint8_t, 8> session{1, 2, 3, 4, 5, 6, 7, 8};
  const auto first = pushreel::linkaudio::BufferTimingIdentity{10, session, 480, 48'000};
  diagnostics.observe(first, 1'000'000, 1'180'000);

  auto snapshot = diagnostics.snapshot();
  assert(snapshot.timedBufferCount == 1);
  assert(snapshot.latestPresentationLatenessUs == 180'000);
  assert(snapshot.minPresentationLatenessUs == 180'000);
  assert(snapshot.maxPresentationLatenessUs == 180'000);
  assert(snapshot.interBufferTimingCount == 0);

  const auto second = pushreel::linkaudio::BufferTimingIdentity{11, session, 480, 48'000};
  diagnostics.observe(second, 1'010'500, 1'200'500);
  snapshot = diagnostics.snapshot();
  assert(snapshot.interBufferTimingCount == 1);
  assert(snapshot.latestInterBufferDeltaUs == 500);
  assert(snapshot.bufferCountDiscontinuityCount == 0);
  assert(snapshot.timestampDiscontinuityCount == 0);

  const auto skipped = pushreel::linkaudio::BufferTimingIdentity{13, session, 480, 48'000};
  diagnostics.observe(skipped, 1'030'000, 1'150'000);
  snapshot = diagnostics.snapshot();
  assert(snapshot.timedBufferCount == 3);
  assert(snapshot.latestPresentationLatenessUs == 120'000);
  assert(snapshot.minPresentationLatenessUs == 120'000);
  assert(snapshot.maxPresentationLatenessUs == 190'000);
  assert(snapshot.interBufferTimingCount == 2);
  assert(snapshot.latestInterBufferDeltaUs == 9'500);
  assert(snapshot.minInterBufferDeltaUs == 500);
  assert(snapshot.maxInterBufferDeltaUs == 9'500);
  assert(snapshot.bufferCountDiscontinuityCount == 1);
  assert(snapshot.timestampDiscontinuityCount == 1);

  const std::array<std::uint8_t, 8> nextSession{8, 7, 6, 5, 4, 3, 2, 1};
  const auto sessionChange =
      pushreel::linkaudio::BufferTimingIdentity{1, nextSession, 480, 48'000};
  diagnostics.observe(sessionChange, 2'000'000, 2'100'000);
  snapshot = diagnostics.snapshot();
  assert(snapshot.timedBufferCount == 4);
  assert(snapshot.interBufferTimingCount == 2);
  assert(snapshot.bufferCountDiscontinuityCount == 1);
  assert(snapshot.timestampDiscontinuityCount == 1);

  diagnostics.reset();
  snapshot = diagnostics.snapshot();
  assert(snapshot.timedBufferCount == 0);
  assert(snapshot.interBufferTimingCount == 0);
}

void testReceiptTimeAffectsOnlyLatenessDiagnostics() {
  pushreel::linkaudio::TimingDiagnostics earlyReceipt;
  pushreel::linkaudio::TimingDiagnostics lateReceipt;
  const std::array<std::uint8_t, 8> session{1, 1, 2, 3, 5, 8, 13, 21};
  const auto first = pushreel::linkaudio::BufferTimingIdentity{1, session, 480, 48'000};
  const auto second = pushreel::linkaudio::BufferTimingIdentity{2, session, 480, 48'000};

  earlyReceipt.observe(first, 1'000'000, 1'100'000);
  earlyReceipt.observe(second, 1'010'250, 1'110'250);
  lateReceipt.observe(first, 1'000'000, 1'800'000);
  lateReceipt.observe(second, 1'010'250, 1'810'250);

  const auto early = earlyReceipt.snapshot();
  const auto late = lateReceipt.snapshot();
  assert(early.latestPresentationLatenessUs == 100'000);
  assert(late.latestPresentationLatenessUs == 800'000);
  assert(early.latestInterBufferDeltaUs == 250);
  assert(late.latestInterBufferDeltaUs == early.latestInterBufferDeltaUs);
  assert(late.timestampDiscontinuityCount == early.timestampDiscontinuityCount);
  assert(late.bufferCountDiscontinuityCount == early.bufferCountDiscontinuityCount);
}

void testFallbackTimingNormalizesStaleContinuousStream() {
  pushreel::linkaudio::FallbackTimingNormalizer normalizer;
  const std::array<std::uint8_t, 8> session{1, 2, 3, 4, 5, 6, 7, 8};
  const auto first = pushreel::linkaudio::BufferTimingIdentity{
      1'938, session, 441, 44'100};
  const auto firstResult = normalizer.normalize(
      first, 10'000'000, 29'412'111);
  assert(firstResult.active);
  assert(firstResult.entered);
  assert(firstResult.reanchored);
  assert(!firstResult.countDiscontinuity);
  assert(firstResult.bufferBeginRawUs == 29'412'111);

  // A retry after failed clock conversion must keep the first buffer's anchor.
  const auto retry = normalizer.normalize(first, 10'000'000, 29'500'000);
  assert(retry.active);
  assert(!retry.reanchored);
  assert(retry.bufferBeginRawUs == firstResult.bufferBeginRawUs);

  const auto second = pushreel::linkaudio::BufferTimingIdentity{
      1'939, session, 441, 44'100};
  const auto secondResult = normalizer.normalize(
      second, 10'010'000, 29'423'500);
  assert(secondResult.active);
  assert(!secondResult.entered);
  assert(!secondResult.reanchored);
  assert(secondResult.bufferBeginRawUs == 29'422'111);

  // Arrival jitter does not affect a continuous stream once the fallback epoch is fixed.
  const auto third = pushreel::linkaudio::BufferTimingIdentity{
      1'940, session, 220, 44'100};
  const auto thirdResult = normalizer.normalize(
      third, 10'020'000, 29'900'000);
  assert(thirdResult.bufferBeginRawUs == 29'432'111);
}

void testFallbackTimingReanchorsSafelyOnDiscontinuity() {
  pushreel::linkaudio::FallbackTimingNormalizer normalizer;
  const std::array<std::uint8_t, 8> session{1, 2, 3, 4, 5, 6, 7, 8};
  const auto first = pushreel::linkaudio::BufferTimingIdentity{10, session, 480, 48'000};
  assert(normalizer.normalize(first, 1'000'000, 4'000'000).bufferBeginRawUs
         == 4'000'000);

  const auto skipped = pushreel::linkaudio::BufferTimingIdentity{12, session, 480, 48'000};
  const auto skippedResult = normalizer.normalize(skipped, 1'020'000, 4'050'000);
  assert(!skippedResult.reanchored);
  assert(skippedResult.countDiscontinuity);
  assert(skippedResult.bufferBeginRawUs == 4'010'000);

  // A delayed/out-of-order receipt can never move the fallback presentation clock backwards.
  const auto nextSkip = pushreel::linkaudio::BufferTimingIdentity{14, session, 480, 48'000};
  const auto clamped = normalizer.normalize(nextSkip, 1'040'000, 4'040'000);
  assert(clamped.bufferBeginRawUs == 4'020'000);

  normalizer.reset();
  const auto valid = normalizer.normalize(first, 7'000'000, 7'100'000);
  assert(!valid.active);
  assert(valid.bufferBeginRawUs == 7'000'000);
}

}  // namespace

int main() {
  testMonoAndTimingMetadata();
  testReadsDoNotCrossLinkBufferBoundaries();
  testOverflowDropsWholeNewestBuffer();
  testSampleRateChangeAndPhysicalWrap();
  testUnsignedPositionWrap();
  testDiscardBufferedFramesSkipsCommittedData();
  testDiscardBufferedFramesAcrossUnsignedWrap();
  testTimingMappingUsesSampleRateAndStableCachedAnchor();
  testTimingDiagnosticsTrackLatenessContinuityAndTimestampGaps();
  testReceiptTimeAffectsOnlyLatenessDiagnostics();
  testFallbackTimingNormalizesStaleContinuousStream();
  testFallbackTimingReanchorsSafelyOnDiscontinuity();
}
