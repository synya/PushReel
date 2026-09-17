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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

        assertThat(native.operations).containsExactly("enable:true", "status", "channels").inOrder()
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
        assertThat(native.operations).containsExactly("status", "channels")
        delegate.close()
        runCurrent()
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

    override fun create(peerNameUtf8: ByteArray): Long {
        operations += "create"
        return 7
    }

    override fun close(handle: Long) {
        operations += "close"
    }

    override fun setEnabled(handle: Long, enabled: Boolean) {
        operations += "enable:$enabled"
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
