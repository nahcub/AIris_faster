#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <unistd.h>
#include <android/log.h>
#include <chrono>
#include "llama.h"

#define LOG_TAG "LlamaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)


// Global model and context pointers
static llama_model* model = nullptr;
static llama_context* ctx = nullptr;

// Session management
static llama_sampler* session_sampler = nullptr;
static bool session_initialized = false;

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
    int n_threads = std::max(1, std::min(8, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2));
    LOGI("Using %d threads", n_threads);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    ctx_params.n_ctx = 2048;
    ctx_params.n_batch = 2048;

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
    llama_sampler_chain_add(session_sampler, llama_sampler_init_greedy());
    llama_sampler_chain_add(session_sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(session_sampler, llama_sampler_init_temp(0.8f));
    llama_sampler_chain_add(session_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    LOGI("Sampler initialized");

    session_initialized = true;
    LOGI("Session initialized successfully!");
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

    // Add system prompt for docent role
    std::string system_prompt = "You are a docent, explaining art. Answer briefly.\n\n";
    std::string full_prompt = system_prompt + std::string(user_prompt);

    LOGI("Generating with streaming (session-based), prompt: %s", full_prompt.c_str());

    auto start_time = std::chrono::high_resolution_clock::now();

    const llama_vocab* vocab = llama_model_get_vocab(model);

    // Tokenize prompt
    std::vector<llama_token> tokens;
    const char* prompt = full_prompt.c_str();

    // First call to get required token count
    int n_tokens = llama_tokenize(vocab, prompt, strlen(prompt), nullptr, 0, false, false);
    if (n_tokens <= 0) {
        LOGE("Failed to tokenize prompt or empty tokens: %d", n_tokens);
        env->ReleaseStringUTFChars(prompt_, user_prompt);
        return JNI_FALSE;
    }

    // Second call with allocated buffer
    tokens.resize(n_tokens);
    n_tokens = llama_tokenize(vocab, prompt, strlen(prompt), tokens.data(), tokens.size(), false, false);

    if (n_tokens <= 0) {
        LOGE("Failed to tokenize prompt: %d", n_tokens);
        env->ReleaseStringUTFChars(prompt_, user_prompt);
        return JNI_FALSE;
    }

    LOGI("Tokenized prompt: %d tokens", n_tokens);

    // Prepare batch and encode prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    LOGI("Decoding prompt batch with %d tokens...", batch.n_tokens);

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

    // Generate tokens and stream via callback
    int n_cur = 0;
    const int n_max_gen = 50;

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
