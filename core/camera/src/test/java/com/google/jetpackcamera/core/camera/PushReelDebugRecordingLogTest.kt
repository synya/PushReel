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
package com.google.jetpackcamera.core.camera

import com.google.common.truth.Truth.assertThat
import java.io.BufferedWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PushReelDebugRecordingLogTest {
    @Test
    fun closePersistsMilestonesAndFailureStack() = runBlocking {
        val contents = StringWriter()
        val log = PushReelDebugRecordingLog(BufferedWriter(contents))

        log.event("PCM prepared")
        log.failure("muxer", IllegalStateException("muxer stopped unexpectedly"))
        log.close()

        assertThat(contents.toString()).contains("PCM prepared")
        assertThat(contents.toString()).contains("IllegalStateException")
        assertThat(contents.toString()).contains("muxer stopped unexpectedly")
        assertThat(contents.toString()).contains("PushReelDebugRecordingLogTest")
    }

    @Test
    fun eventLimitIsBoundedAndReportsDroppedCount() = runBlocking {
        val contents = StringWriter()
        val log = PushReelDebugRecordingLog(BufferedWriter(contents))

        repeat(300) { log.event("event $it") }
        log.close()

        assertThat(contents.toString()).contains("Dropped ")
        assertThat(contents.toString()).doesNotContain("event 299")
        assertThat(contents.toString().lines().count { "event " in it }).isAtMost(256)
    }

    @Test
    fun terminalFailureSurvivesExhaustedNormalEventBudget() = runBlocking {
        val contents = StringWriter()
        val log = PushReelDebugRecordingLog(BufferedWriter(contents))

        repeat(300) { log.event("milestone $it") }
        log.failure("recording", IllegalStateException("stop failed"))
        log.close()

        assertThat(contents.toString()).contains("recording failed: java.lang.IllegalStateException")
        assertThat(contents.toString()).contains("stop failed")
        assertThat(contents.toString()).contains("Dropped ")
    }

    @Test
    fun closeWaitsForSlowFlushBeyondOneSecond() = runBlocking {
        val flushed = AtomicBoolean(false)
        val contents = object : StringWriter() {
            override fun flush() {
                Thread.sleep(1_200)
                flushed.set(true)
            }
        }
        val log = PushReelDebugRecordingLog(BufferedWriter(contents))

        log.event("slow milestone")
        log.close()

        assertThat(flushed.get()).isTrue()
        assertThat(contents.toString()).contains("slow milestone")
    }
}
