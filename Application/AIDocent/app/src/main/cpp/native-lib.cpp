#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <unistd.h>
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
    model = llama_model_load_from_file(path, model_params);

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

    // 기존 컨텍스트가 있으면 해제
    if (ctx) {
        llama_free(ctx);
        ctx = nullptr;
    }

    // 컨텍스트 생성
    // CPU 코어 수 자동 감지 (최소 1, 최대 8, 전체 코어 - 1)
    int n_threads = std::max(1, std::min(8, (int) sysconf(_SC_NPROCESSORS_ONLN) - 1));
    LOGI("Detected CPU cores: %ld, using %d threads", sysconf(_SC_NPROCESSORS_ONLN), n_threads);
    
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads; // 배치 처리 스레드도 설정
    ctx_params.n_ctx = 1024; // 컨텍스트 크기
    LOGI("Creating context with %d threads (batch: %d), ctx_size: %d", 
         ctx_params.n_threads, ctx_params.n_threads_batch, ctx_params.n_ctx);
    
    ctx = llama_init_from_model(model, ctx_params);

    if (!ctx) {
        LOGE("Failed to create context");
        env->ReleaseStringUTFChars(prompt_, prompt);
        return env->NewStringUTF("❌ Failed to create context.");
    }

    LOGI("Context created successfully");

    // Vocab 가져오기
    const llama_vocab* vocab = llama_model_get_vocab(model);

    // 프롬프트 토큰화
    std::vector<llama_token> tokens;
    
    // 토큰화 (처음 호출로 필요한 크기 확인)
    int n_tokens = llama_tokenize(vocab, prompt, strlen(prompt), nullptr, 0, false, false);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, prompt, strlen(prompt), tokens.data(), tokens.size(), false, false);
    } else {
        tokens.resize(n_tokens);
        n_tokens = llama_tokenize(vocab, prompt, strlen(prompt), tokens.data(), tokens.size(), false, false);
    }

    if (n_tokens < 0 || n_tokens == 0) {
        LOGE("Failed to tokenize prompt or empty tokens: %d", n_tokens);
        llama_free(ctx);
        ctx = nullptr;
        env->ReleaseStringUTFChars(prompt_, prompt);
        return env->NewStringUTF("❌ Failed to tokenize prompt.");
    }

    LOGI("Tokenized prompt: %d tokens", n_tokens);

    // 배치 준비
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    
    LOGI("Decoding prompt batch with %d tokens...", batch.n_tokens);
    // 프롬프트 평가
    int decode_result = llama_decode(ctx, batch);
    if (decode_result != 0) {
        LOGE("Failed to decode prompt, result: %d", decode_result);
        llama_free(ctx);
        ctx = nullptr;
        env->ReleaseStringUTFChars(prompt_, prompt);
        return env->NewStringUTF("❌ Failed to decode prompt.");
    }
    
    LOGI("Prompt decoded successfully");

    // Sampler 초기화
    LOGI("Initializing sampler...");
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!sampler) {
        LOGE("Failed to initialize sampler");
        llama_free(ctx);
        ctx = nullptr;
        env->ReleaseStringUTFChars(prompt_, prompt);
        return env->NewStringUTF("❌ Failed to initialize sampler.");
    }
    
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.8f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    LOGI("Sampler initialized");

    // 텍스트 생성
    std::string result;
    result += "Prompt: ";
    result += prompt;
    result += "\n\nResponse: ";

    int n_cur = 0;
    const int n_max_gen = 50; // 최대 생성 토큰 수 (짧은 답변을 위해 줄임)

    LOGI("Starting generation loop, max tokens: %d", n_max_gen);

    while (n_cur < n_max_gen) {
        LOGI("Generating token %d/%d", n_cur + 1, n_max_gen);
        
        // 다음 토큰 샘플링
        llama_token new_token_id = llama_sampler_sample(sampler, ctx, -1);
        
        LOGI("Sampled token: %d", new_token_id);

        // EOG(End of Generation) 체크
        if (llama_vocab_is_eog(vocab, new_token_id)) {
            LOGI("EOG token detected, stopping generation");
            break;
        }

        // EOS 체크
        llama_token eos_token = llama_vocab_eos(vocab);
        if (new_token_id == eos_token) {
            LOGI("EOS token detected, stopping generation");
            break;
        }

        // 토큰을 문자열로 변환
        char piece[256];
        int n = llama_token_to_piece(vocab, new_token_id, piece, sizeof(piece), 0, true);
        if (n < 0) {
            LOGE("Failed to convert token to piece, token_id: %d", new_token_id);
            break;
        }
        
        std::string piece_str(piece, n);
        result += piece_str;
        
        LOGI("Added piece: '%.50s' (length: %d)", piece_str.c_str(), n);

        // 다음 배치 준비 (새로운 배치 생성)
        batch = llama_batch_get_one(&new_token_id, 1);

        LOGI("Decoding next batch for token %d...", new_token_id);
        // 디코딩
        int decode_result = llama_decode(ctx, batch);
        if (decode_result != 0) {
            LOGE("Failed to decode during generation, result: %d, token: %d", decode_result, new_token_id);
            result += "\n\n[Generation stopped due to decode error]";
            break;
        }

        n_cur++;
        LOGI("Successfully generated token %d", n_cur);
    }

    LOGI("Generation completed, total tokens generated: %d", n_cur);
    LOGI("Result string length: %zu characters", result.length());
    LOGI("Result preview (first 100 chars): %.100s", result.c_str());

    // 정리
    llama_sampler_free(sampler);
    llama_free(ctx);
    ctx = nullptr;
    
    LOGI("Cleaned up resources");

    // 결과 문자열 생성
    jstring result_string = env->NewStringUTF(result.c_str());
    if (result_string == nullptr) {
        LOGE("Failed to create Java string from result");
        env->ReleaseStringUTFChars(prompt_, prompt);
        return env->NewStringUTF("❌ Failed to convert result to string.");
    }
    
    LOGI("Created Java string successfully, about to return");
    
    env->ReleaseStringUTFChars(prompt_, prompt);
    return result_string;
}
