#include <jni.h>

#include <algorithm>
#include <fstream>
#include <string>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

#if defined(__ANDROID__) || defined(__linux__)
#include <unistd.h>
#endif

#include "whisper.h"

namespace {

int detectWhisperThreadCount() {
#if defined(_SC_NPROCESSORS_ONLN)
    const long detected = sysconf(_SC_NPROCESSORS_ONLN);
    if (detected > 0) {
        return std::max(1L, detected / 2L);
    }
#endif

#if defined(__ANDROID__) || defined(__linux__)
    std::ifstream cpuInfo("/proc/cpuinfo");
    int count = 0;
    std::string line;
    while (std::getline(cpuInfo, line)) {
        if (line.rfind("processor", 0) == 0) {
            ++count;
        }
    }
    if (count > 0) {
        return std::max(1, count / 2);
    }
#endif

    return 1;
}

void throwIllegalState(JNIEnv * env, const char * message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_distressdetector_WhisperEngine_nativeInit(
        JNIEnv * env,
        jobject /* this */,
        jstring modelPath) {
    if (modelPath == nullptr) {
        throwIllegalState(env, "Whisper model path is missing.");
        return 0;
    }

    const char * path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        return 0;
    }

    const int selectedThreadCount = detectWhisperThreadCount();
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "WhisperJNI", "Whisper startup threads=%d", selectedThreadCount);
#endif

    whisper_context_params contextParams = whisper_context_default_params();
    contextParams.use_gpu = false;
    whisper_context * context = whisper_init_from_file_with_params(path, contextParams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (context == nullptr) {
        throwIllegalState(env, "Whisper Tiny could not load its model file.");
        return 0;
    }
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_distressdetector_WhisperEngine_nativeGetThreadCount(
        JNIEnv * /* env */,
        jobject /* this */) {
    return detectWhisperThreadCount();
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_distressdetector_WhisperEngine_nativeGetLanguages(
        JNIEnv * env,
        jobject /* this */) {
    const int languageCount = whisper_lang_max_id() + 1;
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    jobjectArray languages = env->NewObjectArray(languageCount, stringClass, nullptr);
    if (languages == nullptr) return nullptr;
    for (int id = 0; id < languageCount; ++id) {
        const char * code = whisper_lang_str(id);
        const char * name = whisper_lang_str_full(id);
        if (code == nullptr || name == nullptr) continue;
        const std::string entry = std::string(code) + '\t' + name;
        jstring value = env->NewStringUTF(entry.c_str());
        if (value == nullptr) return nullptr;
        env->SetObjectArrayElement(languages, id, value);
        env->DeleteLocalRef(value);
    }
    return languages;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_distressdetector_WhisperEngine_nativeProcess(
        JNIEnv * env,
        jobject /* this */,
        jlong contextHandle,
        jfloatArray samples,
        jstring task,
        jstring language) {
    auto * context = reinterpret_cast<whisper_context *>(contextHandle);
    if (context == nullptr || samples == nullptr) {
        throwIllegalState(env, "Whisper is not ready to process audio.");
        return nullptr;
    }
    if (language == nullptr || task == nullptr) {
        throwIllegalState(env, "Whisper task or language setting is invalid.");
        return nullptr;
    }

    const jsize sampleCount = env->GetArrayLength(samples);
    if (sampleCount <= 0) {
        throwIllegalState(env, "The recording did not contain any audio samples.");
        return nullptr;
    }

    jboolean isCopy = JNI_FALSE;
    jfloat * audio = env->GetFloatArrayElements(samples, &isCopy);
    if (audio == nullptr) {
        return nullptr;
    }

    const char * languageCode = env->GetStringUTFChars(language, nullptr);
    const char * taskName = env->GetStringUTFChars(task, nullptr);
    if (languageCode == nullptr || taskName == nullptr) {
        if (languageCode != nullptr) env->ReleaseStringUTFChars(language, languageCode);
        if (taskName != nullptr) env->ReleaseStringUTFChars(task, taskName);
        env->ReleaseFloatArrayElements(samples, audio, JNI_ABORT);
        return nullptr;
    }
    const std::string selectedTask(taskName);
    if (selectedTask != "transcribe" && selectedTask != "translate" &&
        selectedTask != "detect-language" && selectedTask != "auto") {
        env->ReleaseStringUTFChars(language, languageCode);
        env->ReleaseStringUTFChars(task, taskName);
        env->ReleaseFloatArrayElements(samples, audio, JNI_ABORT);
        throwIllegalState(env, "Whisper task setting is unsupported.");
        return nullptr;
    }

    // Keep decoding defaults in whisper.cpp; only pass the user-selected task and language.
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = detectWhisperThreadCount();
    params.language = languageCode;
    params.translate = selectedTask == "translate";
    params.detect_language = selectedTask == "detect-language" || selectedTask == "auto";

    const int result = whisper_full(context, params, audio, sampleCount);
    env->ReleaseStringUTFChars(language, languageCode);
    env->ReleaseStringUTFChars(task, taskName);
    env->ReleaseFloatArrayElements(samples, audio, JNI_ABORT);
    if (result != 0) {
        throwIllegalState(env, "Whisper could not process this recording.");
        return nullptr;
    }

    const int languageId = whisper_full_lang_id(context);
    const char * detectedLanguage = languageId >= 0 ? whisper_lang_str(languageId) : "unknown";
    const char * detectedLanguageName = languageId >= 0 ? whisper_lang_str_full(languageId) : "unknown";
    std::string output = detectedLanguage == nullptr ? "unknown" : detectedLanguage;
    output += '\t';
    output += detectedLanguageName == nullptr ? "unknown" : detectedLanguageName;
    output += '\n';
    if (selectedTask == "detect-language" || selectedTask == "auto") {
        return env->NewStringUTF(output.c_str());
    }

    const int segmentCount = whisper_full_n_segments(context);
    for (int index = 0; index < segmentCount; ++index) {
        const char * segment = whisper_full_get_segment_text(context, index);
        if (segment != nullptr) {
            output += segment;
        }
    }
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_distressdetector_WhisperEngine_nativeFree(
        JNIEnv * /* env */,
        jobject /* this */,
        jlong contextHandle) {
    auto * context = reinterpret_cast<whisper_context *>(contextHandle);
    if (context != nullptr) {
        whisper_free(context);
    }
}
