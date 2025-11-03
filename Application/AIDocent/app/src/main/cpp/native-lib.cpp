#include <jni.h>
#include <string>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 전역 포인터 (모델/컨텍스트 보관)
static llama_model* model = nullptr;
static llama_context* ctx = nullptr;

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_airis_NativeBridge_loadModel(JNIEnv* env, jobject /* this */, jstring path_) {
    const char* path = env->GetStringUTFChars(path_, nullptr);
    
    LOGI("Loading model from: %s", path);

    llama_model_params model_params = llama_model_default_params();
    model = llama_load_model_from_file(path, model_params);

    env->ReleaseStringUTFChars(path_, path);

    if (model == nullptr) {
        LOGE("Failed to load model from: %s", path);
        return JNI_FALSE;
    }

    LOGI("Model loaded successfully!");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_airis_NativeBridge_generate(JNIEnv* env, jobject /* this */, jstring prompt_) {
    if (!model) {
        LOGE("Model not loaded");
        return env->NewStringUTF("Model not loaded.");
    }

    const char* prompt = env->GetStringUTFChars(prompt_, nullptr);
    LOGI("Generating with prompt: %s", prompt);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_threads = 4; // 안정성을 위해 줄임
    
    // 기존 컨텍스트가 있으면 해제
    if (ctx) {
        llama_free(ctx);
        ctx = nullptr;
    }
    
    ctx = llama_new_context_with_model(model, ctx_params);

    std::string result;

    if (!ctx) {
        LOGE("Failed to create context");
        result = "❌ Failed to create context.";
    } else {
        LOGI("Context created successfully");
        result = "✅ Model loaded successfully!\nPrompt: ";
        result += prompt;
        result += "\n(Context created OK)";
    }

    env->ReleaseStringUTFChars(prompt_, prompt);
    return env->NewStringUTF(result.c_str());
}
