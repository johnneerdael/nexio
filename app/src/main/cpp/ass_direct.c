#include "ass_direct.h"

#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include <android/log.h>
#include <ass/ass.h>

#define LOG_TAG "assrender"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct AssDirectContext {
    int width;
    int height;
    float font_scale;

    ASS_Library *ass_library;
    ASS_Renderer *ass_renderer;
    ASS_Track *ass_track;
};

AssDirectContext *ass_direct_init(int width, int height, float font_scale) {
    if (width <= 0 || height <= 0 || width > INT_MAX / 4) {
        LOGE("Invalid render dimensions: %dx%d", width, height);
        return NULL;
    }

    AssDirectContext *ctx = calloc(1, sizeof(AssDirectContext));
    if (!ctx) return NULL;

    ctx->width = width;
    ctx->height = height;
    ctx->font_scale = font_scale;

    ctx->ass_library = ass_library_init();
    if (!ctx->ass_library) {
        LOGE("Failed to init libass library");
        free(ctx);
        return NULL;
    }
    ass_set_message_cb(ctx->ass_library,
        (void (*)(int, const char *, va_list, void *))NULL, NULL);
    ass_set_extract_fonts(ctx->ass_library, 1);

    ctx->ass_renderer = ass_renderer_init(ctx->ass_library);
    if (!ctx->ass_renderer) {
        LOGE("Failed to init libass renderer");
        ass_library_done(ctx->ass_library);
        free(ctx);
        return NULL;
    }

    ass_set_storage_size(ctx->ass_renderer, width, height);
    ass_set_frame_size(ctx->ass_renderer, width, height);
    ass_set_font_scale(ctx->ass_renderer, (double)font_scale);
    ass_set_fonts(ctx->ass_renderer, NULL, "sans-serif",
                  ASS_FONTPROVIDER_FONTCONFIG, NULL, 1);

    LOGI("ass_direct initialized: %dx%d, font_scale=%.2f (with system fonts)", width, height, font_scale);
    return ctx;
}

int ass_direct_get_width(AssDirectContext *ctx) {
    return ctx ? ctx->width : 0;
}

int ass_direct_get_height(AssDirectContext *ctx) {
    return ctx ? ctx->height : 0;
}

int ass_direct_load_header(AssDirectContext *ctx, const char *header_data, int header_size) {
    if (!ctx || !header_data || header_size <= 0) return -1;

    if (ctx->ass_track) {
        ass_free_track(ctx->ass_track);
        ctx->ass_track = NULL;
    }

    ctx->ass_track = ass_new_track(ctx->ass_library);
    if (!ctx->ass_track) {
        LOGE("Failed to create ASS track");
        return -1;
    }

    ass_process_codec_private(ctx->ass_track, (char *)header_data, header_size);

    LOGI("Loaded ASS header: %d bytes, %d styles",
         header_size, ctx->ass_track->n_styles);
    return 0;
}

void ass_direct_add_font(AssDirectContext *ctx, const char *name,
                         const char *data, int data_size) {
    if (!ctx || !data || data_size <= 0) return;
    ass_add_font(ctx->ass_library, name, (char *)data, data_size);

    ass_set_fonts(ctx->ass_renderer, NULL, "sans-serif",
                  ASS_FONTPROVIDER_AUTODETECT, NULL, 1);

    LOGI("Added font: %s (%d bytes)", name ? name : "unnamed", data_size);
}

void ass_direct_process_chunk(AssDirectContext *ctx, const char *data, int size,
                              int64_t start_ms, int64_t duration_ms) {
    if (!ctx || !ctx->ass_track || !data || size <= 0) return;
    ass_process_chunk(ctx->ass_track, (char *)data, size, start_ms, duration_ms);
}

void ass_direct_process_data(AssDirectContext *ctx, const char *data, int size) {
    if (!ctx || !ctx->ass_track || !data || size <= 0) return;
    ass_process_data(ctx->ass_track, (char *)data, size);
}

int ass_direct_render(AssDirectContext *ctx, int64_t time_ms, uint8_t *out_pixels,
                      int out_stride) {
    if (!ctx || !ctx->ass_track || !ctx->ass_renderer || !out_pixels)
        return 0;
    if (ctx->width <= 0 || ctx->height <= 0 || out_stride < ctx->width * 4)
        return 0;

    int changed = 0;
    ASS_Image *img = ass_render_frame(ctx->ass_renderer, ctx->ass_track,
                                      time_ms, &changed);

    for (int y = 0; y < ctx->height; y++) {
        memset(out_pixels + y * out_stride, 0, ctx->width * 4);
    }

    if (!img) return 0;

    int has_content = 0;
    while (img) {
        if (img->w == 0 || img->h == 0) {
            img = img->next;
            continue;
        }

        has_content = 1;
        uint8_t r = (img->color >> 24) & 0xFF;
        uint8_t g = (img->color >> 16) & 0xFF;
        uint8_t b = (img->color >> 8) & 0xFF;
        uint8_t a = 255 - (img->color & 0xFF);

        uint8_t *src = img->bitmap;
        for (int y = 0; y < img->h; y++) {
            int dst_y = img->dst_y + y;
            if (dst_y < 0 || dst_y >= ctx->height) {
                src += img->stride;
                continue;
            }
            for (int x = 0; x < img->w; x++) {
                int dst_x = img->dst_x + x;
                if (dst_x < 0 || dst_x >= ctx->width) {
                    continue;
                }
                uint8_t alpha = (uint8_t)(((uint32_t)src[x] * a) >> 8);
                if (alpha == 0) {
                    continue;
                }

                uint8_t *dst = out_pixels + dst_y * out_stride + dst_x * 4;
                uint8_t dst_a = dst[3];
                if (dst_a == 0) {
                    dst[0] = r;
                    dst[1] = g;
                    dst[2] = b;
                    dst[3] = alpha;
                } else {
                    uint32_t inv = 255 - alpha;
                    dst[0] = (uint8_t)((alpha * r + inv * dst[0]) / 255);
                    dst[1] = (uint8_t)((alpha * g + inv * dst[1]) / 255);
                    dst[2] = (uint8_t)((alpha * b + inv * dst[2]) / 255);
                    dst[3] = (uint8_t)(alpha + (inv * dst_a) / 255);
                }
            }
            src += img->stride;
        }
        img = img->next;
    }

    return has_content;
}

void ass_direct_flush(AssDirectContext *ctx) {
    if (!ctx || !ctx->ass_track) return;
    ass_flush_events(ctx->ass_track);
    LOGI("Flushed ASS events");
}

void ass_direct_destroy(AssDirectContext *ctx) {
    if (!ctx) return;

    if (ctx->ass_track)
        ass_free_track(ctx->ass_track);
    if (ctx->ass_renderer)
        ass_renderer_done(ctx->ass_renderer);
    if (ctx->ass_library)
        ass_library_done(ctx->ass_library);

    free(ctx);
    LOGI("ass_direct destroyed");
}
