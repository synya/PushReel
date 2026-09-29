/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pushreel.linkaudio

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkAudioDelegateTest {
    @Test
    fun nativeWorkIsQueuedOnDispatcher() = runTest {
        val native = FakeNativeBridge()
        val delegate = createDelegate(native)

        assertThat(native.operations).isEmpty()
        runCurrent()

        assertThat(native.operations.first()).isEqualTo("create")
        delegate.close()
        runCurrent()
    }

    @Test
    fun enableUpdatesSnapshotAndAcquiresMulticastLock() = runTest {
        val native = FakeNativeBridge()
        val lock = FakeMulticastLock()
        val delegate = createDelegate(native, lock)
        runCurrent()
        native.operations.clear()
        native.status = longArrayOf(1, 1, 2)

        delegate.setEnabled(true)
        assertThat(native.operations).isEmpty()
        runCurrent()

        assertThat(native.operations)
            .containsExactly("enable:true", "status", "channels", "audio-status")
            .inOrder()
        assertThat(lock.acquireCount).isEqualTo(1)
        assertThat(delegate.status.value.peerCount).isEqualTo(2)
        delegate.close()
        runCurrent()
    }

    @Test
    fun snapshotFailureAfterEnableRollsBackBeforeReleasingLock() = runTest {
        val native = FakeNativeBridge()
        val lock = FakeMulticastLock()
        val delegate = createDelegate(native, lock)
        runCurrent()
        native.operations.clear()
        native.failChannels = true

        delegate.setEnabled(true)
        runCurrent()

        assertThat(native.operations)
            .containsExactly("enable:true", "status", "channels", "enable:false")
            .inOrder()
        assertThat(lock.releaseCount).isEqualTo(1)
        assertThat(delegate.status.value.error).contains("snapshot failed")
        native.failChannels = false
        delegate.close()
        runCurrent()
    }

    @Test
    fun closeStillClosesNativeHandleWhenLockReleaseThrows() = runTest {
        val native = FakeNativeBridge()
        val lock = FakeMulticastLock(failRelease = true)
        val delegate = createDelegate(native, lock)
        runCurrent()

        delegate.close()
        runCurrent()

        assertThat(native.operations).contains("close")
        assertThat(delegate.status.value.error).contains("release failed")
    }

    @Test
    fun pollingQueuesPeriodicSnapshot() = runTest {
        val native = FakeNativeBridge()
        val delegate = createDelegate(native, pollIntervalMillis = 100)
        runCurrent()
        native.operations.clear()

        advanceTimeBy(99)
        runCurrent()
        assertThat(native.operations).isEmpty()

        advanceTimeBy(1)
        runCurrent()
        assertThat(native.operations).containsExactly("status", "channels", "audio-status")
        delegate.close()
        runCurrent()
    }

    @Test
    fun selectionAndPcmReadAreSerializedThroughActor() = runTest {
        val native = FakeNativeBridge()
        val delegate = createDelegate(native)
        runCurrent()
        native.operations.clear()
        native.readResult = longArrayOf(2, 0, 2, 48_000, 7, 0, 0, 0)

        delegate.selectChannel("0102030405060708")
        val destination = ShortArray(8)
        val read = async(start = CoroutineStart.UNDISPATCHED) {
            delegate.readPcmFrames(destination, 4)
        }
        runCurrent()

        assertThat(read.await().framesRead).isEqualTo(2)
        assertThat(native.operations)
            .containsExactly(
                "select:0102030405060708",
                "audio-status",
                "read:4",
                "audio-status"
            )
            .inOrder()
        assertThat(delegate.pcmStatus.value.selectedChannelId).isEqualTo("0102030405060708")
        assertThat(delegate.pcmStatus.value.generation).isEqualTo(1)
        delegate.close()
        runCurrent()
    }

    @Test
    fun discardAndPcmReadAreSerializedThroughActor() = runTest {
        val native = FakeNativeBridge().apply { discardedFrames = 12_345L }
        val delegate = createDelegate(native)
        runCurrent()
        native.operations.clear()

        val discarded = async(start = CoroutineStart.UNDISPATCHED) {
            delegate.discardBufferedPcmFrames()
        }
        val read = async(start = CoroutineStart.UNDISPATCHED) {
            delegate.readPcmFrames(ShortArray(2), 1)
        }
        runCurrent()

        assertThat(discarded.await()).isEqualTo(12_345L)
        assertThat(read.await().framesRead).isEqualTo(0)
        assertThat(native.operations)
            .containsExactly("discard", "audio-status", "read:1", "audio-status")
            .inOrder()
        delegate.close()
        runCurrent()
    }

    @Test
    fun cancellationDoesNotReleaseDestinationBeforeNativeReadCompletes() = runTest {
        val native = FakeNativeBridge()
        val delegate = createDelegate(native)
        runCurrent()
        val destination = ShortArray(2)
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            delegate.readPcmFrames(destination, 1)
        }

        request.cancel()
        assertThat(request.isCompleted).isFalse()
        runCurrent()

        assertThat(destination.asList())
            .containsExactly(101.toShort(), 202.toShort())
            .inOrder()
        assertThat(request.isCompleted).isTrue()
        delegate.close()
        runCurrent()
    }

    @Test
    fun closeDrainsQueuedReadWithoutTouchingNativeDestination() = runTest {
        val native = FakeNativeBridge()
        val delegate = createDelegate(native)
        runCurrent()
        native.operations.clear()
        val destination = ShortArray(2)
        val read = async(start = CoroutineStart.UNDISPATCHED) {
            delegate.readPcmFrames(destination, 1)
        }

        delegate.close()
        runCurrent()

        assertThat(read.await()).isEqualTo(LinkAudioPcmRead(framesRead = 0, metadata = null))
        assertThat(native.operations).doesNotContain("read:1")
        assertThat(destination.asList()).containsExactly(0.toShort(), 0.toShort())
    }

    @Test
    fun closeDrainsQueuedDiscardWithoutCallingNative() = runTest {
        val native = FakeNativeBridge()
        val delegate = createDelegate(native)
        runCurrent()
        native.operations.clear()
        val discard = async(start = CoroutineStart.UNDISPATCHED) {
            delegate.discardBufferedPcmFrames()
        }

        delegate.close()
        runCurrent()

        assertThat(discard.await()).isEqualTo(0L)
        assertThat(native.operations).doesNotContain("discard")
    }

    @Test
    fun refreshRequestsAreConflatedWhileActorIsBusy() = runTest {
        val native = FakeNativeBridge()
        val delegate = createDelegate(native)

        repeat(100) { delegate.refresh() }
        runCurrent()

        assertThat(native.operations.count { it == "status" }).isEqualTo(2)
        assertThat(native.operations.count { it == "channels" }).isEqualTo(2)
        delegate.close()
        runCurrent()
    }

    @Test
    fun saturatedQueueCannotLoseClearOrDisable() = runTest {
        val native = FakeNativeBridge()
        val lock = FakeMulticastLock()
        val delegate = createDelegate(native, lock)
        runCurrent()
        native.operations.clear()

        repeat(16) { index -> delegate.selectChannel("channel-$index") }
        delegate.selectChannel(null)
        delegate.setEnabled(false)
        runCurrent()

        assertThat(native.operations).contains("select:null")
        assertThat(native.operations).contains("enable:false")
        assertThat(native.operations.none { it.startsWith("select:channel-") }).isTrue()
        assertThat(lock.releaseCount).isEqualTo(1)
        delegate.close()
        runCurrent()
    }

    @Test
    fun failedDisableReleasesLockAndRetriesBeforeMarkingGenerationApplied() = runTest {
        val native = FakeNativeBridge().apply { disableFailuresRemaining = 1 }
        val lock = FakeMulticastLock()
        val delegate = createDelegate(native, lock)
        runCurrent()
        native.operations.clear()

        delegate.setEnabled(false)
        runCurrent()

        val disableAttempts = native.operations.count { it == "enable:false" }
        val releasesBeforeClose = lock.releaseCount
        delegate.close()
        runCurrent()

        assertThat(disableAttempts).isEqualTo(2)
        assertThat(releasesBeforeClose).isEqualTo(2)
    }

    @Test
    fun sameChannelReselectionStaysPendingUntilLatestGenerationIsApplied() = runTest {
        val native = FakeNativeBridge()
        val delegate = createDelegate(native)
        runCurrent()
        native.operations.clear()

        delegate.selectChannel("channel-a")
        delegate.selectChannel("channel-b")
        delegate.selectChannel("channel-a")
        assertThat(delegate.pcmStatus.value).isEqualTo(LinkAudioPcmStatus())
        runCurrent()

        assertThat(native.operations.filter { it.startsWith("select:") })
            .containsExactly("select:channel-a")
        assertThat(delegate.pcmStatus.value.selectedChannelId).isEqualTo("channel-a")
        assertThat(delegate.pcmStatus.value.generation).isEqualTo(3)
        delegate.close()
        runCurrent()
    }

    private fun kotlinx.coroutines.test.TestScope.createDelegate(
        native: FakeNativeBridge,
        lock: FakeMulticastLock = FakeMulticastLock(),
        pollIntervalMillis: Long = 500
    ) = LinkAudioDelegate(
        nativeBridge = native,
        multicastLock = lock,
        pollIntervalMillis = pollIntervalMillis,
        dispatcher = StandardTestDispatcher(testScheduler),
        peerName = "PushReel"
    )
}

private class FakeNativeBridge : NativeBridge {
    val operations = mutableListOf<String>()
    var status = longArrayOf(0, 0, 0)
    var failChannels = false
    var disableFailuresRemaining = 0
    var readResult: LongArray? = null
    var discardedFrames = 0L

    override fun create(peerNameUtf8: ByteArray): Long {
        operations += "create"
        return 7
    }

    override fun close(handle: Long) {
        operations += "close"
    }

    override fun setEnabled(handle: Long, enabled: Boolean) {
        operations += "enable:$enabled"
        if (!enabled && disableFailuresRemaining > 0) {
            disableFailuresRemaining--
            error("disable failed")
        }
    }

    override fun getStatus(handle: Long): LongArray {
        operations += "status"
        return status
    }

    override fun getChannels(handle: Long): Array<ByteArray> {
        operations += "channels"
        if (failChannels) error("snapshot failed")
        return emptyArray()
    }

    override fun selectChannel(handle: Long, channelIdUtf8: ByteArray?) {
        operations += "select:${channelIdUtf8?.decodeToString()}"
    }

    override fun readAudioFrames(
        handle: Long,
        destination: ShortArray,
        requestedFrames: Int
    ): LongArray? {
        operations += "read:$requestedFrames"
        if (destination.size >= 2) {
            destination[0] = 101
            destination[1] = 202
        }
        return readResult
    }

    override fun discardBufferedAudio(handle: Long): Long {
        operations += "discard"
        return discardedFrames
    }

    override fun getAudioStatus(handle: Long): LongArray {
        operations += "audio-status"
        return LongArray(22).also { values ->
            values[2] = 2
            values[4] = 96_000
        }
    }
}

private class FakeMulticastLock(
    private val failRelease: Boolean = false
) : MulticastLock {
    var acquireCount = 0
    var releaseCount = 0

    override fun acquire() {
        acquireCount++
    }

    override fun release() {
        releaseCount++
        if (failRelease) error("release failed")
    }
}
