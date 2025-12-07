#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <unistd.h>
#include <android/log.h>
#include <chrono>
#include "llama.h"
#include "prompt_generate.h"

#define LOG_TAG "LlamaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)


// Global model and context pointers
static llama_model* model = nullptr;
static llama_context* ctx = nullptr;

// Session management
static llama_sampler* session_sampler = nullptr;
static bool session_initialized = false;

// Prompt caching
static int n_past_system = 0;
static bool system_prompt_cached = false;

// Load pre-trained Transformer model from file
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

// Initialize generation session with context and sampler
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_airis_NativeBridge_initSession(JNIEnv* env, jobject /* this */) {
    if (!model) {
        LOGE("Model not loaded");
        return JNI_FALSE;
    }

    if (session_initialized) {
        LOGI("Session already initialized");
        return JNI_TRUE;
    }

    LOGI("Initializing generation session...");

    // Configure threads: reserve 2 cores for system
    

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_threads = 6;
    ctx_params.n_threads_batch = 8;
    ctx_params.n_ctx = 1024;
    ctx_params.n_batch = 1024;

    LOGI("Creating context with %d threads, ctx_size: %d, batch_size: %d",
         ctx_params.n_threads, ctx_params.n_ctx, ctx_params.n_batch);

    ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create context");
        return JNI_FALSE;
    }

    LOGI("Context created successfully");

    // Initialize sampler chain
    auto smpl_params = llama_sampler_chain_default_params();
    session_sampler = llama_sampler_chain_init(smpl_params);
    if (!session_sampler) {
        LOGE("Failed to initialize sampler");
        llama_free(ctx);
        ctx = nullptr;
        return JNI_FALSE;
    }

    // Add sampler filters: greedy + min_p + temperature + distribution
    llama_sampler_chain_add(session_sampler, llama_sampler_init_top_p(0.8f, 1));
    llama_sampler_chain_add(session_sampler, llama_sampler_init_min_p(0.0f, 1));
    llama_sampler_chain_add(session_sampler, llama_sampler_init_temp(0.4f));
    llama_sampler_chain_add(session_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    LOGI("Sampler initialized");

    // Reset prompt caching state
    n_past_system = 0;
    system_prompt_cached = false;

    session_initialized = true;
    LOGI("Session initialized successfully!");
    return JNI_TRUE;
}

// Decode system prompt and cache it in KV Cache
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_airis_NativeBridge_decodeSystemPrompt(JNIEnv* env, jobject /* this */) {
    if (!model) {
        LOGE("Model not loaded");
        return JNI_FALSE;
    }

    if (!session_initialized || !ctx) {
        LOGE("Session not initialized. Call initSession() first!");
        return JNI_FALSE;
    }

    if (system_prompt_cached) {
        LOGI("System prompt already cached");
        return JNI_TRUE;
    }

    LOGI("Decoding system prompt for caching...");
    
    // 시간 측정 시작
    auto start_time = std::chrono::high_resolution_clock::now();

    const llama_vocab* vocab = llama_model_get_vocab(model);
    if (!vocab) {
        LOGE("Failed to get vocab from model");
        return JNI_FALSE;
    }

    // Build system prompt
    std::string system_prompt = buildSystemPrompt();
    LOGI("System prompt: %s", system_prompt.c_str());

    // Tokenize system prompt
    std::vector<llama_token> tokens;
    const char* prompt = system_prompt.c_str();

    // First call to get required token count
    int n_tokens = -llama_tokenize(vocab, prompt, strlen(prompt), nullptr, 0, true, true);
    if (n_tokens <= 0) {
        LOGE("Failed to tokenize system prompt or empty tokens: %d", n_tokens);
        return JNI_FALSE;
    }

    // Second call with allocated buffer
    tokens.resize(n_tokens);
    n_tokens = llama_tokenize(vocab, prompt, strlen(prompt), tokens.data(), tokens.size(), true, true);

    if (n_tokens <= 0) {
        LOGE("Failed to tokenize system prompt: %d", n_tokens);
        return JNI_FALSE;
    }

    LOGI("Tokenized system prompt: %d tokens", n_tokens);

    // Prepare batch and decode system prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    LOGI("Decoding system prompt batch with %d tokens...", batch.n_tokens);

    int decode_result = llama_decode(ctx, batch);
    if (decode_result != 0) {
        LOGE("Failed to decode system prompt, result: %d", decode_result);
        return JNI_FALSE;
    }

    // Store the number of tokens for n_past in future generations
    n_past_system = n_tokens;
    system_prompt_cached = true;

    // 시간 측정 종료 및 로그 출력
    auto end_time = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time);
    double seconds = duration.count() / 1000.0;

    LOGI("System prompt cached successfully: %d tokens", n_past_system);
    LOGI("System prompt decode stats - Time: %.2f sec, Tokens: %d, Speed: %.2f tok/sec", 
         seconds, n_tokens, n_tokens / seconds);

    return JNI_TRUE;
}

// Clean up session: free sampler and context
extern "C"
JNIEXPORT void JNICALL
Java_com_example_airis_NativeBridge_closeSession(JNIEnv* env, jobject /* this */) {
    LOGI("Closing generation session...");

    if (session_sampler) {
        llama_sampler_free(session_sampler);
        session_sampler = nullptr;
    }

    if (ctx) {
        llama_free(ctx);
        ctx = nullptr;
    }

    session_initialized = false;
    n_past_system = 0;
    system_prompt_cached = false;
    LOGI("Session closed");
}

// Session-based streaming text generation with real-time callback
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_airis_NativeBridge_generateStreaming(JNIEnv* env, jobject /* this */, jstring prompt_, jobject callback) {
    if (!model) {
        LOGE("Model not loaded");
        return JNI_FALSE;
    }

    // Check if session is initialized (required for streaming)
    if (!session_initialized || !ctx || !session_sampler) {
        LOGE("Session not initialized. Call initSession() first!");
        return JNI_FALSE;
    }

    const char* user_prompt = env->GetStringUTFChars(prompt_, nullptr);

    // Check if system prompt is cached
    if (!system_prompt_cached) {
        LOGE("System prompt not cached. Call decodeSystemPrompt() first!");
        env->ReleaseStringUTFChars(prompt_, user_prompt);
        return JNI_FALSE;
    }

    // Generate user prompt only (system prompt is already cached)
    std::string user_prompt_str = buildUserPrompt(std::string(user_prompt));

    LOGI("Generating with streaming (session-based, cached system prompt), user prompt: %s", user_prompt_str.c_str());

    auto start_time = std::chrono::high_resolution_clock::now();

    const llama_vocab* vocab = llama_model_get_vocab(model);
    
    if (!vocab) {
        LOGE("Failed to get vocab from model");
        env->ReleaseStringUTFChars(prompt_, user_prompt);
        return JNI_FALSE;
    }
    
    // Tokenize user prompt only
    std::vector<llama_token> tokens;
    const char* prompt = user_prompt_str.c_str();

    // First call to get required token count
    int n_tokens = -llama_tokenize(vocab, prompt, strlen(prompt), nullptr, 0, true, true);
    if (n_tokens <= 0) {
        LOGE("Failed to tokenize prompt or empty tokens: %d", n_tokens);
        env->ReleaseStringUTFChars(prompt_, user_prompt);
        return JNI_FALSE;
    }

    // Second call with allocated buffer
    tokens.resize(n_tokens);
    n_tokens = llama_tokenize(vocab, prompt, strlen(prompt), tokens.data(), tokens.size(), true, true);

    if (n_tokens <= 0) {
        LOGE("Failed to tokenize prompt: %d", n_tokens);
        env->ReleaseStringUTFChars(prompt_, user_prompt);
        return JNI_FALSE;
    }

    LOGI("Tokenized user prompt: %d tokens (system prompt cached: %d tokens)", n_tokens, n_past_system);

    // Prepare batch and encode user prompt
    // llama_batch_get_one will automatically set pos starting from n_past_system
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    LOGI("Decoding user prompt batch with %d tokens (starting from pos %d)...", batch.n_tokens, n_past_system);

    int decode_result = llama_decode(ctx, batch);
    if (decode_result != 0) {
        LOGE("Failed to decode prompt, result: %d", decode_result);
        env->ReleaseStringUTFChars(prompt_, user_prompt);
        return JNI_FALSE;
    }

    LOGI("Prompt decoded successfully");

    // Reset sampler state for new generation
    llama_sampler_reset(session_sampler);
    LOGI("Sampler reset, reusing existing sampler");

    // Prepare callback interface for streaming tokens
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID invokeMethod = env->GetMethodID(callbackClass, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");

    if (invokeMethod == nullptr) {
        LOGE("Failed to find invoke method");
        env->ReleaseStringUTFChars(prompt_, user_prompt);
        return JNI_FALSE;
    }

    // Stop sequences 정의
    const std::vector<std::string> stop_sequences = {
        "\n\n[QUESTION]",
        "\n\nQ:",
        "\nQ:",
        "[QUESTION]",
        "\n\n[ARTWORK INFO]"
    };

    // Generate tokens and stream via callback
    int n_cur = 0;
    const int n_max_gen = 1024;
    const size_t LOOKBACK_SIZE = 200; // 최근 200자만 확인
    
    // 생성된 텍스트를 누적하여 추적
    std::string accumulated_text = "";

    LOGI("Starting streaming generation loop, max tokens: %d", n_max_gen);

    while (n_cur < n_max_gen) {
        llama_token new_token_id = llama_sampler_sample(session_sampler, ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token_id)) {
            LOGI("EOG token detected, stopping generation");
            break;
        }

        llama_token eos_token = llama_vocab_eos(vocab);
        if (new_token_id == eos_token) {
            LOGI("EOS token detected, stopping generation");
            break;
        }

        // Convert token to string
        char piece[256];
        int n = llama_token_to_piece(vocab, new_token_id, piece, sizeof(piece), 0, true);
        if (n < 0) {
            LOGE("Failed to convert token to piece, token_id: %d", new_token_id);
            break;
        }

        std::string piece_str(piece, n);
        accumulated_text += piece_str;

        // 버퍼 크기 제한 (메모리 절약)
        if (accumulated_text.length() > LOOKBACK_SIZE) {
            accumulated_text = accumulated_text.substr(accumulated_text.length() - LOOKBACK_SIZE);
        }

        // Stop sequence 감지
        bool should_stop = false;
        for (const auto& stop_seq : stop_sequences) {
            if (accumulated_text.find(stop_seq) != std::string::npos) {
                LOGI("Stop sequence detected: %s", stop_seq.c_str());
                should_stop = true;
                break;
            }
        }
        
        // Stream token via callback to Kotlin/UI
        jstring jpiece = env->NewStringUTF(piece_str.c_str());
        env->CallObjectMethod(callback, invokeMethod, jpiece);
        env->DeleteLocalRef(jpiece);

        // Handle callback exceptions
        if (env->ExceptionCheck()) {
            LOGE("Exception occurred during callback");
            env->ExceptionDescribe();
            env->ExceptionClear();
            break;
        }

        // Stop sequence 감지 시 중단
        if (should_stop) {
            LOGI("Stopping generation due to stop sequence");
            break;
        }

        // Prepare next batch for autoregressive generation
        batch = llama_batch_get_one(&new_token_id, 1);
        decode_result = llama_decode(ctx, batch);
        if (decode_result != 0) {
            LOGE("Failed to decode during generation, result: %d, token: %d", decode_result, new_token_id);
            break;
        }

        n_cur++;
    }

    LOGI("Streaming generation completed, total tokens generated: %d", n_cur);

    // Log generation performance metrics
    auto end_time = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time);
    double seconds = duration.count() / 1000.0;
    double tokens_per_second = n_cur > 0 ? n_cur / seconds : 0.0;

    LOGI("Generation stats - Time: %.2f sec, Tokens: %d, Speed: %.2f tok/sec", seconds, n_cur, tokens_per_second);

    // Session remains active for next generation call
    env->ReleaseStringUTFChars(prompt_, user_prompt);
    return JNI_TRUE;
}
