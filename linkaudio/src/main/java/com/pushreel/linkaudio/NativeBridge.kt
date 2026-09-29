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

internal interface NativeBridge {
    fun create(peerNameUtf8: ByteArray): Long
    fun close(handle: Long)
    fun setEnabled(handle: Long, enabled: Boolean)
    fun getStatus(handle: Long): LongArray
    fun getChannels(handle: Long): Array<ByteArray>
    fun selectChannel(handle: Long, channelIdUtf8: ByteArray?)
    fun readAudioFrames(handle: Long, destination: ShortArray, requestedFrames: Int): LongArray?
    fun discardBufferedAudio(handle: Long): Long
    fun getAudioStatus(handle: Long): LongArray
    fun getPeakLevels(handle: Long): LongArray
}

internal object JniNativeBridge : NativeBridge {
    private val loaded by lazy {
        System.loadLibrary("pushreel_link_audio")
        true
    }

    override fun create(peerNameUtf8: ByteArray): Long {
        check(loaded)
        return nativeCreate(peerNameUtf8)
    }
    override fun close(handle: Long) = nativeClose(handle)
    override fun setEnabled(handle: Long, enabled: Boolean) = nativeSetEnabled(handle, enabled)
    override fun getStatus(handle: Long): LongArray = nativeGetStatus(handle)
    override fun getChannels(handle: Long): Array<ByteArray> = nativeGetChannels(handle)
    override fun selectChannel(handle: Long, channelIdUtf8: ByteArray?) =
        nativeSelectChannel(handle, channelIdUtf8)
    override fun readAudioFrames(
        handle: Long,
        destination: ShortArray,
        requestedFrames: Int
    ): LongArray? = nativeReadAudioFrames(handle, destination, requestedFrames)
    override fun discardBufferedAudio(handle: Long): Long = nativeDiscardBufferedAudio(handle)
    override fun getAudioStatus(handle: Long): LongArray = nativeGetAudioStatus(handle)
    override fun getPeakLevels(handle: Long): LongArray = nativeGetPeakLevels(handle)

    private external fun nativeCreate(peerNameUtf8: ByteArray): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeSetEnabled(handle: Long, enabled: Boolean)
    private external fun nativeGetStatus(handle: Long): LongArray
    private external fun nativeGetChannels(handle: Long): Array<ByteArray>
    private external fun nativeSelectChannel(handle: Long, channelIdUtf8: ByteArray?)
    private external fun nativeReadAudioFrames(
        handle: Long,
        destination: ShortArray,
        requestedFrames: Int
    ): LongArray?
    private external fun nativeDiscardBufferedAudio(handle: Long): Long
    private external fun nativeGetAudioStatus(handle: Long): LongArray
    private external fun nativeGetPeakLevels(handle: Long): LongArray
}
