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

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val DEBUG_LOG_TAG = "PushReelRecordingLog"
private const val DEBUG_LOG_QUEUE_CAPACITY = 64
private const val DEBUG_LOG_MAX_EVENTS = 256
private const val DEBUG_LOG_MAX_MESSAGE_LENGTH = 65_536

/** A best-effort, bounded breadcrumb log. File I/O is owned by one IO coroutine. */
internal class PushReelDebugRecordingLog internal constructor(
    private val writer: BufferedWriter
) {
    private val events = Channel<String>(DEBUG_LOG_QUEUE_CAPACITY)
    // A terminal failure must still fit when normal milestones exhaust their queue or event cap.
    private val terminalFailures = Channel<String>(Channel.CONFLATED)
    private val accepted = AtomicInteger()
    private val dropped = AtomicInteger()
    private val worker: Job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            fun writeEvent(event: String) {
                writer.write(event)
                writer.newLine()
                // Every milestone remains readable after a crash or forced process exit.
                writer.flush()
            }
            var eventsOpen = true
            var failuresOpen = true
            while (eventsOpen || failuresOpen) {
                // Priority may overtake queued milestones; timestamps preserve their order.
                val urgent = terminalFailures.tryReceive().getOrNull()
                if (urgent != null) {
                    writeEvent(urgent)
                    continue
                }
                select<Unit> {
                    if (failuresOpen) {
                        terminalFailures.onReceiveCatching { result ->
                            if (result.isClosed) failuresOpen = false
                            else writeEvent(result.getOrThrow())
                        }
                    }
                    if (eventsOpen) {
                        events.onReceiveCatching { result ->
                            if (result.isClosed) eventsOpen = false
                            else writeEvent(result.getOrThrow())
                        }
                    }
                }
            }
            val droppedCount = dropped.get()
            if (droppedCount > 0) {
                writer.write("Dropped $droppedCount debug log events")
                writer.newLine()
                writer.flush()
            }
        } catch (error: Throwable) {
            Log.w(DEBUG_LOG_TAG, "Unable to write recording debug log", error)
        } finally {
            runCatching { writer.close() }
        }
    }

    fun event(message: String) {
        if (accepted.incrementAndGet() > DEBUG_LOG_MAX_EVENTS) {
            dropped.incrementAndGet()
            return
        }
        val line = "${System.currentTimeMillis()} ${message.take(DEBUG_LOG_MAX_MESSAGE_LENGTH)}"
        if (!events.trySend(line).isSuccess) dropped.incrementAndGet()
    }

    fun failure(stage: String, error: Throwable) {
        val line = "${System.currentTimeMillis()} $stage failed: ${error.stackTraceToString()}"
        if (!terminalFailures.trySend(line.take(DEBUG_LOG_MAX_MESSAGE_LENGTH)).isSuccess) {
            dropped.incrementAndGet()
        }
    }

    suspend fun close() {
        events.close()
        terminalFailures.close()
        withContext(NonCancellable + Dispatchers.IO) {
            if (withTimeoutOrNull(3_000) { worker.join() } == null) {
                // Keep the IO worker alive so a slow MediaStore flush can still finish.
                Log.w(DEBUG_LOG_TAG, "Recording debug log is still flushing")
            }
        }
    }

    companion object {
        suspend fun create(context: Context): PushReelDebugRecordingLog? {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                var uri: android.net.Uri? = null
                var writer: BufferedWriter? = null
                try {
                    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val name = "PushReel-$timestamp-${UUID.randomUUID().toString().take(8)}.txt"
                    uri = checkNotNull(
                        resolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            ContentValues().apply {
                                put(MediaStore.Downloads.DISPLAY_NAME, name)
                                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                                put(
                                    MediaStore.Downloads.RELATIVE_PATH,
                                    "${Environment.DIRECTORY_DOWNLOADS}/PushReel/"
                                )
                                put(MediaStore.MediaColumns.IS_PENDING, 0)
                            }
                        )
                    ) { "MediaStore did not create a debug log" }
                    val stream = checkNotNull(resolver.openOutputStream(uri, "w")) {
                        "MediaStore did not open a debug log"
                    }
                    writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8))
                    writer.write("PushReel recording attempt; created=$timestamp; logUri=$uri")
                    writer.newLine()
                    writer.flush()
                    PushReelDebugRecordingLog(writer)
                } catch (error: Throwable) {
                    runCatching { writer?.close() }
                    uri?.let { runCatching { resolver.delete(it, null, null) } }
                    if (error is CancellationException) throw error
                    Log.w(DEBUG_LOG_TAG, "Unable to create recording debug log", error)
                    null
                }
            }
        }
    }
}
