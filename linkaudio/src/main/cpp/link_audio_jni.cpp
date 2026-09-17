/*
 * Copyright (C) 2026 PushReel contributors
 *
 * This file integrates Ableton Link, which is available under GPLv2+ or a
 * proprietary Ableton license. See third_party/ableton-link/LICENSE.md.
 */

#include <jni.h>
#include <android/log.h>

#include <ableton/LinkAudio.hpp>

#include <array>
#include <cstdint>
#include <iomanip>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

struct LinkInstance {
  explicit LinkInstance(std::string peerName) : link(120.0, std::move(peerName)) {}
  ableton::LinkAudio link;
};

std::mutex gRegistryMutex;
std::unordered_map<std::int64_t, std::shared_ptr<LinkInstance>> gInstances;
std::int64_t gNextHandle = 1;

void throwJava(JNIEnv* env, const char* className, const char* message) noexcept {
  if (env->ExceptionCheck()) {
    return;
  }
  const auto exceptionClass = env->FindClass(className);
  if (exceptionClass != nullptr) {
    env->ThrowNew(exceptionClass, message);
  }
}

void translateCurrentException(JNIEnv* env, const char* fallback) noexcept {
  try {
    throw;
  } catch (const std::exception& error) {
    throwJava(env, "java/lang/IllegalStateException", error.what());
  } catch (...) {
    throwJava(env, "java/lang/IllegalStateException", fallback);
  }
}

void logCurrentException(const char* operation) noexcept {
  try {
    throw;
  } catch (const std::exception& error) {
    __android_log_print(ANDROID_LOG_ERROR, "PushReelLinkAudio", "%s: %s", operation, error.what());
  } catch (...) {
    __android_log_print(ANDROID_LOG_ERROR, "PushReelLinkAudio", "%s failed", operation);
  }
}

std::shared_ptr<LinkInstance> findInstance(JNIEnv* env, jlong handle) {
  std::lock_guard<std::mutex> lock(gRegistryMutex);
  const auto it = gInstances.find(static_cast<std::int64_t>(handle));
  if (it == gInstances.end()) {
    throwJava(env, "java/lang/IllegalStateException", "Link Audio handle is closed or invalid");
    return nullptr;
  }
  return it->second;
}

std::string fromJavaBytes(JNIEnv* env, jbyteArray value) {
  if (value == nullptr) {
    return {};
  }
  const auto size = env->GetArrayLength(value);
  std::string result(static_cast<std::size_t>(size), '\0');
  if (size > 0) {
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(result.data()));
  }
  if (env->ExceptionCheck()) {
    return {};
  }
  return result;
}

jbyteArray toJavaBytes(JNIEnv* env, const std::string& value) {
  const auto result = env->NewByteArray(static_cast<jsize>(value.size()));
  if (result != nullptr && !value.empty()) {
    env->SetByteArrayRegion(
        result,
        0,
        static_cast<jsize>(value.size()),
        reinterpret_cast<const jbyte*>(value.data()));
  }
  return result;
}

std::string idToHex(const ableton::link_audio::Id& id) {
  std::ostringstream stream;
  stream << std::hex << std::setfill('0');
  for (const auto byte : id) {
    stream << std::setw(2) << static_cast<unsigned int>(byte);
  }
  return stream.str();
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_pushreel_linkaudio_JniNativeBridge_nativeCreate(
    JNIEnv* env, jobject, jbyteArray peerName) {
  try {
    auto instance = std::make_shared<LinkInstance>(fromJavaBytes(env, peerName));
    if (env->ExceptionCheck()) {
      return 0;
    }
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    const auto handle = gNextHandle++;
    gInstances.emplace(handle, std::move(instance));
    return static_cast<jlong>(handle);
  } catch (const std::exception& error) {
    throwJava(env, "java/lang/IllegalStateException", error.what());
  } catch (...) {
    throwJava(env, "java/lang/IllegalStateException", "Unable to create Link Audio");
  }
  return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pushreel_linkaudio_JniNativeBridge_nativeClose(JNIEnv*, jobject, jlong handle) noexcept {
  try {
    std::shared_ptr<LinkInstance> removed;
    {
      std::lock_guard<std::mutex> lock(gRegistryMutex);
      const auto it = gInstances.find(static_cast<std::int64_t>(handle));
      if (it == gInstances.end()) {
        return;
      }
      removed = std::move(it->second);
      gInstances.erase(it);
    }
    try {
      removed->link.enableLinkAudio(false);
    } catch (...) {
      logCurrentException("disable Link Audio during close");
    }
    try {
      removed->link.enable(false);
    } catch (...) {
      logCurrentException("disable Link during close");
    }
    removed.reset();
  } catch (...) {
    logCurrentException("close");
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_pushreel_linkaudio_JniNativeBridge_nativeSetEnabled(
    JNIEnv* env, jobject, jlong handle, jboolean enabled) {
  try {
    const auto instance = findInstance(env, handle);
    if (!instance) {
      return;
    }
    const bool shouldEnable = enabled == JNI_TRUE;
    if (shouldEnable) {
      instance->link.enable(true);
      instance->link.enableLinkAudio(true);
    } else {
      instance->link.enableLinkAudio(false);
      instance->link.enable(false);
    }
  } catch (...) {
    translateCurrentException(env, "Unable to change Link Audio state");
  }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pushreel_linkaudio_JniNativeBridge_nativeGetStatus(
    JNIEnv* env, jobject, jlong handle) {
  try {
    const auto instance = findInstance(env, handle);
    if (!instance) {
      return nullptr;
    }
    const std::array<jlong, 3> values{
        instance->link.isEnabled() ? 1 : 0,
        instance->link.isLinkAudioEnabled() ? 1 : 0,
        static_cast<jlong>(instance->link.numPeers())};
    const auto result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
      env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
  } catch (...) {
    translateCurrentException(env, "Unable to read Link Audio status");
    return nullptr;
  }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_pushreel_linkaudio_JniNativeBridge_nativeGetChannels(
    JNIEnv* env, jobject, jlong handle) {
  try {
    const auto instance = findInstance(env, handle);
    if (!instance) {
      return nullptr;
    }
    const auto channels = instance->link.channels();
    const auto byteArrayClass = env->FindClass("[B");
    if (byteArrayClass == nullptr) {
      return nullptr;
    }
    constexpr std::size_t fieldsPerChannel = 4;
    const auto result = env->NewObjectArray(
        static_cast<jsize>(channels.size() * fieldsPerChannel), byteArrayClass, nullptr);
    if (result == nullptr) {
      return nullptr;
    }
    std::size_t outputIndex = 0;
    for (const auto& channel : channels) {
      const std::array<std::string, fieldsPerChannel> fields{
          idToHex(channel.id), channel.name, idToHex(channel.peerId), channel.peerName};
      for (const auto& field : fields) {
        const auto value = toJavaBytes(env, field);
        if (value == nullptr) {
          return nullptr;
        }
        env->SetObjectArrayElement(result, static_cast<jsize>(outputIndex++), value);
        env->DeleteLocalRef(value);
      }
    }
    return result;
  } catch (...) {
    translateCurrentException(env, "Unable to read Link Audio channels");
    return nullptr;
  }
}
