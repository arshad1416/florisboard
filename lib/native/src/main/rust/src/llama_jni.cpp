#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "llama.h"

#define UNUSED(x) (void)(x)
#define LOG_TAG "llama_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

typedef struct {
    struct llama_model *model;
    struct llama_context *ctx;
    const struct llama_vocab *vocab;
    struct llama_sampler *sampler;
    int n_threads;
} llama_inference_t;

JNIEXPORT jlong JNICALL
Java_org_florisboard_libnative_LlamaInference_nativeCreate(
    JNIEnv *env, jobject thiz, jstring model_path, jint n_threads)
{
    UNUSED(thiz);

    const char *path = env->GetStringUTFChars(model_path, NULL);
    if (!path) { LOGE("GetStringUTFChars returned NULL"); return 0; }

    LOGI("Starting nativeCreate: path=%s, threads=%d", path, (int)n_threads);

    llama_backend_init();
    LOGI("llama_backend_init done");

    struct llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.use_mmap = 1;
    mparams.use_mlock = 0;

    LOGI("Calling llama_model_load_from_file...");
    struct llama_model *model = llama_model_load_from_file(path, mparams);
    LOGI("llama_model_load_from_file returned: %s", model ? "OK" : "NULL");

    env->ReleaseStringUTFChars(model_path, path);

    if (!model) {
        LOGE("Failed to load model from %s", path);
        return 0;
    }

    // Use smaller context to reduce memory pressure
    struct llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx    = 512;
    cparams.n_batch  = 256;
    cparams.n_ubatch = 256;
    cparams.n_threads       = (int32_t)n_threads;
    cparams.n_threads_batch = (int32_t)n_threads;

    LOGI("Calling llama_init_from_model (n_ctx=%d)...", (int)cparams.n_ctx);
    struct llama_context *ctx = llama_init_from_model(model, cparams);
    LOGI("llama_init_from_model returned: %s", ctx ? "OK" : "NULL");

    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        return 0;
    }

    LOGI("Setting up sampler chain...");
    struct llama_sampler *smpl = llama_sampler_chain_init(
        llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.3f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_inference_t *inf = (llama_inference_t *)malloc(sizeof(llama_inference_t));
    if (!inf) {
        LOGE("malloc failed");
        llama_sampler_free(smpl);
        llama_free(ctx);
        llama_model_free(model);
        return 0;
    }

    inf->model     = model;
    inf->ctx       = ctx;
    inf->vocab     = llama_model_get_vocab(model);
    inf->sampler   = smpl;
    inf->n_threads = (int)n_threads;

    LOGI("nativeCreate succeeded, ptr=%p", (void*)inf);
    return (jlong)(intptr_t)inf;
}

JNIEXPORT void JNICALL
Java_org_florisboard_libnative_LlamaInference_nativeDestroy(
    JNIEnv *env, jobject thiz, jlong ptr)
{
    UNUSED(env); UNUSED(thiz);

    if (ptr == 0) return;

    llama_inference_t *inf = (llama_inference_t *)(intptr_t)ptr;
    llama_sampler_free(inf->sampler);
    llama_free(inf->ctx);
    llama_model_free(inf->model);
    free(inf);
}

static int build_chatml_prompt(
    const char *raw_text,
    const char *context_text,
    const char *corrections,
    char *buf,
    int buf_size)
{
    char system_msg[2048];
    int sys_len = snprintf(system_msg, sizeof(system_msg),
        "You are a text corrector. Fix spelling, grammar, capitalization, and punctuation. "
        "Output ONLY the corrected text — no explanations, no markdown, no quotes.\n"
        "Examples:\n"
        "Input: teh cat sat on mat\n"
        "Output: The cat sat on the mat.\n"
        "Input: i went to the store\n"
        "Output: I went to the store.\n"
        "Input: how r u doing\n"
        "Output: How are you doing?");
    if (sys_len < 0 || sys_len >= (int)sizeof(system_msg)) sys_len = (int)sizeof(system_msg) - 1;

    char user_msg[4096];
    int user_len = 0;

    int has_ctx = context_text && context_text[0];
    int has_cor = corrections && corrections[0];

    if (has_ctx) {
        if (has_cor) {
            user_len = snprintf(user_msg, sizeof(user_msg),
                "Context: \"%s\". Keep: %s.\nInput: %s",
                context_text, corrections, raw_text);
        } else {
            user_len = snprintf(user_msg, sizeof(user_msg),
                "Context: \"%s\".\nInput: %s",
                context_text, raw_text);
        }
    } else {
        if (has_cor) {
            user_len = snprintf(user_msg, sizeof(user_msg),
                "Keep: %s.\nInput: %s",
                corrections, raw_text);
        } else {
            user_len = snprintf(user_msg, sizeof(user_msg),
                "Input: %s", raw_text);
        }
    }
    if (user_len < 0 || user_len >= (int)sizeof(user_msg)) user_len = (int)sizeof(user_msg) - 1;

    struct llama_chat_message messages[] = {
        { "system", system_msg },
        { "user",   user_msg   },
    };

    // NULL = use the model's built-in chat template from GGUF metadata.
    // This is more reliable than guessing a template key name.
    int result = llama_chat_apply_template(
        NULL, messages, 2, 1, buf, buf_size);

    LOGI("chat template applied: result=%d", result);
    if (result > 0 && result < buf_size) {
        LOGI("formatted prompt: [%.*s]", result, buf);
    }

    return result;
}

JNIEXPORT jstring JNICALL
Java_org_florisboard_libnative_LlamaInference_nativePolish(
    JNIEnv *env, jobject thiz,
    jlong ptr,
    jstring raw_text_j,
    jstring context_text_j,
    jstring corrections_j)
{
    UNUSED(thiz);

    if (ptr == 0) return NULL;

    llama_inference_t *inf = (llama_inference_t *)(intptr_t)ptr;

    const char *raw_text = env->GetStringUTFChars(raw_text_j, NULL);
    const char *context_text = env->GetStringUTFChars(context_text_j, NULL);
    const char *corrections = env->GetStringUTFChars(corrections_j, NULL);

    char prompt[4096];
    int prompt_len = build_chatml_prompt(
        raw_text, context_text, corrections, prompt, sizeof(prompt));

    env->ReleaseStringUTFChars(raw_text_j, raw_text);
    env->ReleaseStringUTFChars(context_text_j, context_text);
    env->ReleaseStringUTFChars(corrections_j, corrections);

    if (prompt_len < 0) {
        // Buffer too small — retry with exact size needed
        int needed = -prompt_len;
        LOGI("prompt buffer too small (4096), retrying with %d bytes", needed);
        char *big_prompt = (char *)malloc((size_t)needed);
        if (!big_prompt) return NULL;
        // Re-get Java strings (they were released above)
        raw_text = env->GetStringUTFChars(raw_text_j, NULL);
        context_text = env->GetStringUTFChars(context_text_j, NULL);
        corrections = env->GetStringUTFChars(corrections_j, NULL);
        prompt_len = build_chatml_prompt(
            raw_text, context_text, corrections, big_prompt, needed);
        env->ReleaseStringUTFChars(raw_text_j, raw_text);
        env->ReleaseStringUTFChars(context_text_j, context_text);
        env->ReleaseStringUTFChars(corrections_j, corrections);
        if (prompt_len < 0) { free(big_prompt); return NULL; }

        // Process with big_prompt (inline the rest of the function)
        // Clear KV cache to prevent context overflow across calls
        llama_kv_cache_clear(inf->ctx);
        LOGI("KV cache cleared before big-prompt inference");

        int n_tokens_max_b = prompt_len / 2 + 256;
        llama_token *tokens_b = (llama_token *)malloc((size_t)n_tokens_max_b * sizeof(llama_token));
        if (!tokens_b) { free(big_prompt); return NULL; }

        int n_tokens_b = llama_tokenize(inf->vocab, big_prompt, prompt_len, tokens_b, n_tokens_max_b, 0, 0);
        if (n_tokens_b < 0) {
            int needed_t = -n_tokens_b;
            llama_token *new_t = (llama_token *)realloc(tokens_b, (size_t)needed_t * sizeof(llama_token));
            if (!new_t) { free(tokens_b); free(big_prompt); return NULL; }
            tokens_b = new_t;
            n_tokens_b = llama_tokenize(inf->vocab, big_prompt, prompt_len, tokens_b, needed_t, 0, 0);
            if (n_tokens_b < 0) { free(tokens_b); free(big_prompt); return NULL; }
        }

        int n_batch_b = (int)llama_n_batch(inf->ctx);
        int n_processed_b = 0;
        while (n_processed_b < n_tokens_b) {
            int chunk = n_tokens_b - n_processed_b;
            if (chunk > n_batch_b) chunk = n_batch_b;
            struct llama_batch batch = llama_batch_get_one(tokens_b + n_processed_b, chunk);
            int ret = llama_decode(inf->ctx, batch);
            if (ret != 0) { free(tokens_b); free(big_prompt); return NULL; }
            n_processed_b += chunk;
        }

        // Generate response
        char *result_b = (char *)malloc(4096);
        if (!result_b) { free(tokens_b); free(big_prompt); return NULL; }
        int result_len_b = 0;
        llama_token eos_tok_b = llama_vocab_eos(inf->vocab);
        int n_gen_b = 0;
        const int max_new_b = 512;

        while (n_gen_b < max_new_b) {
            llama_token tok = llama_sampler_sample(inf->sampler, inf->ctx, -1);
            if (tok == eos_tok_b) break;
            char piece[256];
            int n_chars = llama_token_to_piece(inf->vocab, tok, piece, (int)sizeof(piece) - 1, 0, 0);
            if (n_chars < 0) break;
            piece[n_chars] = '\0';
            int rem = 4095 - result_len_b;
            if (rem <= 0) break;
            int copy = n_chars; if (copy > rem) copy = rem;
            memcpy(result_b + result_len_b, piece, (size_t)copy);
            result_len_b += copy;
            result_b[result_len_b] = '\0';
            struct llama_batch next = llama_batch_get_one(&tok, 1);
            if (llama_decode(inf->ctx, next) != 0) break;
            n_gen_b++;
        }

        free(tokens_b); free(big_prompt);

        if (result_len_b == 0) { free(result_b); return NULL; }
        // Trim ChatML markers and whitespace
        const char *im_end_b = strstr(result_b, "<|im_end|>");
        if (im_end_b) { result_len_b = (int)(im_end_b - result_b); result_b[result_len_b] = '\0'; }
        const char *im_start_b = strstr(result_b, "<|im_start|>");
        if (im_start_b) { int nl = (int)(im_start_b - result_b); if (nl < result_len_b) { result_len_b = nl; result_b[result_len_b] = '\0'; } }
        while (result_len_b > 0 && (result_b[result_len_b-1] == ' ' || result_b[result_len_b-1] == '\n')) result_b[--result_len_b] = '\0';
        int s_b = 0;
        while (s_b < result_len_b && (result_b[s_b] == ' ' || result_b[s_b] == '\n')) s_b++;
        jstring ret_b = env->NewStringUTF(result_b + s_b);
        free(result_b);
        return ret_b;
    }

    // Clear KV cache before processing new prompt to prevent
    // context overflow from previous polish/proofread calls.
    llama_kv_cache_clear(inf->ctx);

    // Tokenize the prompt
    int n_tokens_max = prompt_len / 2 + 256;
    llama_token *tokens = (llama_token *)malloc((size_t)n_tokens_max * sizeof(llama_token));
    if (!tokens) return NULL;

    int n_tokens = llama_tokenize(
        inf->vocab, prompt, prompt_len, tokens, n_tokens_max, 0, 0);

    if (n_tokens < 0) {
        int needed = -n_tokens;
        llama_token *new_tokens = (llama_token *)realloc(tokens, (size_t)needed * sizeof(llama_token));
        if (!new_tokens) { free(tokens); return NULL; }
        tokens = new_tokens;
        n_tokens = llama_tokenize(
            inf->vocab, prompt, prompt_len, tokens, needed, 0, 0);
        if (n_tokens < 0) { free(tokens); return NULL; }
    }

    // Process prompt in batches (pos auto-tracked by llama_decode when batch.pos is NULL)
    int n_batch = (int)llama_n_batch(inf->ctx);
    int n_processed = 0;

    while (n_processed < n_tokens) {
        int chunk = n_tokens - n_processed;
        if (chunk > n_batch) chunk = n_batch;

        struct llama_batch batch = llama_batch_get_one(
            tokens + n_processed, chunk);

        int ret = llama_decode(inf->ctx, batch);
        if (ret != 0) {
            free(tokens);
            return NULL;
        }

        n_processed += chunk;
    }

    // Generate response (max 512 new tokens)
    char result[2048];
    int result_len = 0;

    llama_token new_token_id;
    int n_generated = 0;
    const int max_new = 512;
    llama_token eos_token = llama_vocab_eos(inf->vocab);

    LOGI("Starting generation, eos_token=%d", (int)eos_token);

    while (n_generated < max_new) {
        new_token_id = llama_sampler_sample(inf->sampler, inf->ctx, -1);

        LOGI("sampled token %d: id=%d", n_generated, (int)new_token_id);

        if (new_token_id == eos_token) {
            LOGI("hit EOS, stopping generation");
            break;
        }

        char piece[256];
        int n_chars = llama_token_to_piece(
            inf->vocab, new_token_id, piece, (int)sizeof(piece) - 1, 0, 0);
        if (n_chars < 0) {
            LOGI("token_to_piece failed: %d", n_chars);
            break;
        }

        piece[n_chars] = '\0';
        LOGI("token piece: [%s]", piece);

        int remaining = (int)sizeof(result) - result_len - 1;
        if (remaining <= 0) break;

        int copy_len = n_chars;
        if (copy_len > remaining) copy_len = remaining;
        memcpy(result + result_len, piece, (size_t)copy_len);
        result_len += copy_len;
        result[result_len] = '\0';

        struct llama_batch next_batch = llama_batch_get_one(&new_token_id, 1);
        int ret = llama_decode(inf->ctx, next_batch);
        if (ret != 0) {
            LOGI("decode failed at gen step %d: ret=%d", n_generated, ret);
            break;
        }

        n_generated++;
    }

    LOGI("Generation done: n_generated=%d, result_len=%d, result=[%s]",
         n_generated, result_len, result_len > 0 ? result : "(empty)");

    free(tokens);

    if (result_len == 0) {
        LOGI("result_len is 0, returning NULL");
        return NULL;
    }

    // Truncate at ChatML end-of-turn marker (model may hallucinate past it)
    const char *im_end = strstr(result, "<|im_end|>");
    if (im_end) {
        result_len = (int)(im_end - result);
        result[result_len] = '\0';
    }
    // Also truncate at any im_start (model started hallucinating a new turn)
    const char *im_start = strstr(result, "<|im_start|>");
    if (im_start) {
        int new_len = (int)(im_start - result);
        if (new_len < result_len) {
            result_len = new_len;
            result[result_len] = '\0';
        }
    }

    // Trim leading/trailing whitespace
    while (result_len > 0 && (result[result_len-1] == ' ' || result[result_len-1] == '\n')) {
        result[--result_len] = '\0';
    }
    int start = 0;
    while (start < result_len && (result[start] == ' ' || result[start] == '\n')) {
        start++;
    }

    LOGI("Returning result (len=%d): [%s]", result_len, result_len > 0 ? result + start : "(empty)");

    return env->NewStringUTF(result + start);
}

} // extern "C"
