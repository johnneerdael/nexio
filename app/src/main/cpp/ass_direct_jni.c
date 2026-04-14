#include <jni.h>
#include <stdio.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <string.h>
#include <limits.h>

#include "ass_direct.h"

#define LOG_TAG "assrender_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jlong JNICALL
Java_com_nexio_tv_ui_screens_player_ass_AssSsaNativeBridge_nativeInit(
        JNIEnv *env, jobject thiz,
        jint width, jint height, jfloat font_scale) {
    (void)env; (void)thiz;
    AssDirectContext *ctx = ass_direct_init(width, height, font_scale);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT jint JNICALL
Java_com_nexio_tv_ui_screens_player_ass_AssSsaNativeBridge_nativeLoadHeader(
        JNIEnv *env, jobject thiz,
        jlong handle, jbyteArray header_data) {
    (void)thiz;
    AssDirectContext *ctx = (AssDirectContext *)(intptr_t)handle;
    if (!ctx || !header_data) return -1;

    jsize size = (*env)->GetArrayLength(env, header_data);
    jbyte *data = (*env)->GetByteArrayElements(env, header_data, NULL);
    if (!data) return -1;

    int result = ass_direct_load_header(ctx, (const char *)data, size);
    (*env)->ReleaseByteArrayElements(env, header_data, data, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL
Java_com_nexio_tv_ui_screens_player_ass_AssSsaNativeBridge_nativeAddFont(
        JNIEnv *env, jobject thiz,
        jlong handle, jstring name, jbyteArray font_data) {
    (void)thiz;
    AssDirectContext *ctx = (AssDirectContext *)(intptr_t)handle;
    if (!ctx || !font_data) return;

    const char *cname = name ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;
    jsize size = (*env)->GetArrayLength(env, font_data);
    jbyte *data = (*env)->GetByteArrayElements(env, font_data, NULL);
    if (!data) {
        if (cname) (*env)->ReleaseStringUTFChars(env, name, cname);
        return;
    }

    ass_direct_add_font(ctx, cname, (const char *)data, size);

    (*env)->ReleaseByteArrayElements(env, font_data, data, JNI_ABORT);
    if (cname) (*env)->ReleaseStringUTFChars(env, name, cname);
}

JNIEXPORT void JNICALL
Java_com_nexio_tv_ui_screens_player_ass_AssSsaNativeBridge_nativeProcessChunk(
        JNIEnv *env, jobject thiz,
        jlong handle, jbyteArray data, jlong start_ms, jlong duration_ms) {
    (void)thiz;
    AssDirectContext *ctx = (AssDirectContext *)(intptr_t)handle;
    if (!ctx || !data) return;

    jsize size = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return;

    ass_direct_process_chunk(ctx, (const char *)bytes, size, start_ms, duration_ms);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_nexio_tv_ui_screens_player_ass_AssSsaNativeBridge_nativeProcessData(
        JNIEnv *env, jobject thiz,
        jlong handle, jbyteArray data) {
    (void)thiz;
    AssDirectContext *ctx = (AssDirectContext *)(intptr_t)handle;
    if (!ctx || !data) return;

    jsize size = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return;

    ass_direct_process_data(ctx, (const char *)bytes, size);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

JNIEXPORT jboolean JNICALL
Java_com_nexio_tv_ui_screens_player_ass_AssSsaNativeBridge_nativeRender(
        JNIEnv *env, jobject thiz,
        jlong handle, jlong time_ms, jobject bitmap) {
    (void)thiz;
    AssDirectContext *ctx = (AssDirectContext *)(intptr_t)handle;
    if (!ctx || !bitmap) return JNI_FALSE;

    AndroidBitmapInfo info;
    int info_ret = AndroidBitmap_getInfo(env, bitmap, &info);
    if (info_ret != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to read bitmap info: %d", info_ret);
        return JNI_FALSE;
    }

    int width = ass_direct_get_width(ctx);
    int height = ass_direct_get_height(ctx);
    if (width <= 0 || height <= 0) {
        LOGE("Invalid native ASS render context dimensions: %dx%d", width, height);
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Unsupported bitmap format: %u", info.format);
        return JNI_FALSE;
    }
    if (info.width != (uint32_t)width || info.height != (uint32_t)height) {
        LOGE("Bitmap size mismatch: bitmap=%ux%u context=%dx%d",
             info.width, info.height, width, height);
        return JNI_FALSE;
    }
    if (info.stride > INT_MAX || info.stride < (uint32_t)(width * 4)) {
        LOGE("Invalid bitmap stride: %u for width=%d", info.stride, width);
        return JNI_FALSE;
    }

    void *pixels = NULL;
    int ret = AndroidBitmap_lockPixels(env, bitmap, &pixels);
    if (ret != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock bitmap pixels: %d", ret);
        return JNI_FALSE;
    }

    int has_content = ass_direct_render(ctx, time_ms, (uint8_t *)pixels, (int)info.stride);

    AndroidBitmap_unlockPixels(env, bitmap);
    return has_content ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nexio_tv_ui_screens_player_ass_AssSsaNativeBridge_nativeFlush(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    AssDirectContext *ctx = (AssDirectContext *)(intptr_t)handle;
    ass_direct_flush(ctx);
}

JNIEXPORT void JNICALL
Java_com_nexio_tv_ui_screens_player_ass_AssSsaNativeBridge_nativeDestroy(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    AssDirectContext *ctx = (AssDirectContext *)(intptr_t)handle;
    ass_direct_destroy(ctx);
}
