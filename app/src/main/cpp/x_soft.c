#include <math.h>
#include <stdlib.h>
#include <string.h>
#include "stdsdk.h"
#include "X.H"

#define XSOFT_MAX_TEXTURES 2048
#define XSOFT_MAX_PRIM_VERTICES 4
#define XSOFT_MAX_CLIPPED_VERTICES 16
#define XSOFT_DEPTH_CLEAR 1.0e30f
#define XSOFT_ENABLE_DIAGNOSTICS 0

#if XSOFT_ENABLE_DIAGNOSTICS
#define XSOFT_DIAG(code) do { code; } while (0)
#else
#define XSOFT_DIAG(code) do { } while (0)
#endif

typedef struct
{
    int used;
    int format;
    int width;
    int height;
    int clamp_x;
    int clamp_y;
    uint32_t *pixels;
} SoftTexture;

typedef struct
{
    float clip_x;
    float clip_y;
    float clip_w;
    float sx;
    float sy;
    float depth;
    float inv_w;
    float color[4];
    float tex1[2];
    float tex2[2];
} SoftVertex;

typedef struct
{
    int opened;
    int width;
    int height;
    uint32_t *color;
    float *depth;
    float viewport_x0;
    float viewport_y0;
    float viewport_x1;
    float viewport_y1;
    int color_mask;
    int depth_mask;
    int depth_test;
    float alpha_limit;
    int blend_src;
    int blend_dst;
    int combine_rgb;
    int combine_alpha;
    int dual_mode;
    int tex1;
    int tex2;
    float env[4];
    int fog_type;
    float fog_min;
    float fog_max;
    float fog_color[4];
    float zdecal;
    int geometry_flags;
    int prim_type;
    int prim_count;
    SoftVertex prim[XSOFT_MAX_PRIM_VERTICES];
    int frame_id;
    int submitted_vertices;
    int submitted_tris;
    int rasterized_tris;
    int shaded_pixels;
    int color_writes;
    float last_tri_bbox[4];
} SoftRenderer;

static SoftRenderer g_soft;
static SoftTexture g_textures[XSOFT_MAX_TEXTURES];
static int g_debug_state_logs;
static int g_debug_texture_logs;

xt_data g_data;
xt_pos g_pos;

extern void android_port_present_framebuffer(const uint32_t *pixels, int width, int height);
extern void android_port_set_render_active(int active);

static float clampf(float value, float min_value, float max_value)
{
    if (value < min_value) {
        return min_value;
    }
    if (value > max_value) {
        return max_value;
    }
    return value;
}

static int clampi(int value, int min_value, int max_value)
{
    if (value < min_value) {
        return min_value;
    }
    if (value > max_value) {
        return max_value;
    }
    return value;
}

static uint32_t pack_color(const float *rgba)
{
    int r = (int)(clampf(rgba[0], 0.0f, 1.0f) * 255.0f + 0.5f);
    int g = (int)(clampf(rgba[1], 0.0f, 1.0f) * 255.0f + 0.5f);
    int b = (int)(clampf(rgba[2], 0.0f, 1.0f) * 255.0f + 0.5f);
    int a = (int)(clampf(rgba[3], 0.0f, 1.0f) * 255.0f + 0.5f);

    return ((uint32_t)a << 24) |
        ((uint32_t)r << 16) |
        ((uint32_t)g << 8) |
        (uint32_t)b;
}

static void unpack_color(uint32_t argb, float *rgba)
{
    rgba[0] = (float)((argb >> 16) & 255) / 255.0f;
    rgba[1] = (float)((argb >> 8) & 255) / 255.0f;
    rgba[2] = (float)(argb & 255) / 255.0f;
    rgba[3] = (float)((argb >> 24) & 255) / 255.0f;
}

static void copy_color(float *dst, const float *src)
{
    dst[0] = src[0];
    dst[1] = src[1];
    dst[2] = src[2];
    dst[3] = src[3];
}

static void fill_color(float *dst, float r, float g, float b, float a)
{
    dst[0] = r;
    dst[1] = g;
    dst[2] = b;
    dst[3] = a;
}

static void update_raster_vertex(SoftVertex *vertex)
{
    float width;
    float height;
    float w;
    float ndc_x;
    float ndc_y;

    w = vertex->clip_w;
    if (fabsf(w) < 1.0e-6f) {
        w = (w < 0.0f) ? -1.0e-6f : 1.0e-6f;
    }

    ndc_x = vertex->clip_x / w;
    ndc_y = vertex->clip_y / w;
    width = g_soft.viewport_x1 - g_soft.viewport_x0 + 1.0f;
    height = g_soft.viewport_y1 - g_soft.viewport_y0 + 1.0f;

    vertex->sx = g_soft.viewport_x0 + (ndc_x + 1.0f) * 0.5f * width;
    vertex->sy = g_soft.viewport_y0 + (1.0f - (ndc_y + 1.0f) * 0.5f) * height;
    vertex->depth = fabsf(w);
    vertex->inv_w = 1.0f / w;
}

static float clip_plane_distance(const SoftVertex *vertex, int plane)
{
    switch (plane) {
    case 0:
        return vertex->clip_x + vertex->clip_w;
    case 1:
        return vertex->clip_w - vertex->clip_x;
    case 2:
        return vertex->clip_y + vertex->clip_w;
    case 3:
        return vertex->clip_w - vertex->clip_y;
    case 4:
        return vertex->clip_w - 1.0e-5f;
    default:
        return 1.0f;
    }
}

static SoftVertex lerp_vertex(const SoftVertex *a, const SoftVertex *b, float t)
{
    SoftVertex out;
    int i;

    memset(&out, 0, sizeof(out));
    out.clip_x = a->clip_x + (b->clip_x - a->clip_x) * t;
    out.clip_y = a->clip_y + (b->clip_y - a->clip_y) * t;
    out.clip_w = a->clip_w + (b->clip_w - a->clip_w) * t;

    for (i = 0; i < 4; i++) {
        out.color[i] = a->color[i] + (b->color[i] - a->color[i]) * t;
    }
    for (i = 0; i < 2; i++) {
        out.tex1[i] = a->tex1[i] + (b->tex1[i] - a->tex1[i]) * t;
        out.tex2[i] = a->tex2[i] + (b->tex2[i] - a->tex2[i]) * t;
    }

    update_raster_vertex(&out);
    return out;
}

static SoftTexture *xsoft_texture(int handle)
{
    if (handle <= 0 || handle >= XSOFT_MAX_TEXTURES || !g_textures[handle].used) {
        return NULL;
    }
    return &g_textures[handle];
}

static float wrap_coord(float value)
{
    value -= floorf(value);
    if (value < 0.0f) {
        value += 1.0f;
    }
    return value;
}

static void sample_texture(int handle, float u, float v, float *rgba)
{
    SoftTexture *texture = xsoft_texture(handle);
    int tx;
    int ty;
    uint32_t pixel;

    if (!texture || !texture->pixels || texture->width <= 0 || texture->height <= 0) {
        fill_color(rgba, 1.0f, 1.0f, 1.0f, 1.0f);
        return;
    }

    if (texture->clamp_x) {
        u = clampf(u, 0.0f, 1.0f);
    } else {
        u = wrap_coord(u);
    }

    if (texture->clamp_y) {
        v = clampf(v, 0.0f, 1.0f);
    } else {
        v = wrap_coord(v);
    }

    tx = clampi((int)(u * (float)(texture->width - 1) + 0.5f), 0, texture->width - 1);
    ty = clampi((int)(v * (float)(texture->height - 1) + 0.5f), 0, texture->height - 1);
    pixel = texture->pixels[ty * texture->width + tx];
    unpack_color(pixel, rgba);
}

static void evaluate_combine_mode(int mode, const float *vertex, const float *tex1, const float *tex2, float *out)
{
    float factor;

    (void)tex2;

    switch (mode) {
    case X_WHITE:
        fill_color(out, 1.0f, 1.0f, 1.0f, 1.0f);
        break;
    case X_COLOR:
        copy_color(out, vertex);
        break;
    case X_TEXTURE:
        copy_color(out, tex1);
        break;
    case X_ADD:
        out[0] = clampf(vertex[0] + tex1[0], 0.0f, 1.0f);
        out[1] = clampf(vertex[1] + tex1[1], 0.0f, 1.0f);
        out[2] = clampf(vertex[2] + tex1[2], 0.0f, 1.0f);
        out[3] = clampf(vertex[3] + tex1[3], 0.0f, 1.0f);
        break;
    case X_MUL:
        out[0] = vertex[0] * tex1[0];
        out[1] = vertex[1] * tex1[1];
        out[2] = vertex[2] * tex1[2];
        out[3] = vertex[3] * tex1[3];
        break;
    case X_MULADD:
        out[0] = clampf(vertex[0] * tex1[0] + vertex[0], 0.0f, 1.0f);
        out[1] = clampf(vertex[1] * tex1[1] + vertex[1], 0.0f, 1.0f);
        out[2] = clampf(vertex[2] * tex1[2] + vertex[2], 0.0f, 1.0f);
        out[3] = clampf(vertex[3] * tex1[3] + vertex[3], 0.0f, 1.0f);
        break;
    case X_DECAL:
    case X_TEXTUREBLEND:
        factor = tex1[3];
        out[0] = vertex[0] * (1.0f - factor) + tex1[0] * factor;
        out[1] = vertex[1] * (1.0f - factor) + tex1[1] * factor;
        out[2] = vertex[2] * (1.0f - factor) + tex1[2] * factor;
        out[3] = vertex[3];
        break;
    case X_TEXTURE_IA:
        out[0] = tex1[0];
        out[1] = tex1[1];
        out[2] = tex1[2];
        out[3] = vertex[3];
        break;
    case X_MUL_TA:
        out[0] = vertex[0] * tex1[0];
        out[1] = vertex[1] * tex1[1];
        out[2] = vertex[2] * tex1[2];
        out[3] = tex1[3];
        break;
    case X_MUL_IA:
        out[0] = vertex[0] * tex1[0];
        out[1] = vertex[1] * tex1[1];
        out[2] = vertex[2] * tex1[2];
        out[3] = vertex[3];
        break;
    case X_TEXTUREENVA:
        factor = tex1[3];
        out[0] = g_soft.env[0] * (1.0f - factor) + vertex[0] * factor;
        out[1] = g_soft.env[1] * (1.0f - factor) + vertex[1] * factor;
        out[2] = g_soft.env[2] * (1.0f - factor) + vertex[2] * factor;
        out[3] = vertex[3];
        break;
    case X_TEXTUREENVC:
        factor = (tex1[0] + tex1[1] + tex1[2]) * (1.0f / 3.0f);
        out[0] = g_soft.env[0] * (1.0f - factor) + vertex[0] * factor;
        out[1] = g_soft.env[1] * (1.0f - factor) + vertex[1] * factor;
        out[2] = g_soft.env[2] * (1.0f - factor) + vertex[2] * factor;
        out[3] = vertex[3];
        break;
    case X_TEXTUREENVCR:
        factor = tex1[0];
        out[0] = vertex[0] * (1.0f - factor) + g_soft.env[0] * factor;
        out[1] = vertex[1] * (1.0f - factor) + g_soft.env[1] * factor;
        out[2] = vertex[2] * (1.0f - factor) + g_soft.env[2] * factor;
        out[3] = vertex[3];
        break;
    case X_SUB:
        out[0] = clampf(vertex[0] - tex1[0], 0.0f, 1.0f);
        out[1] = clampf(vertex[1] - tex1[1], 0.0f, 1.0f);
        out[2] = clampf(vertex[2] - tex1[2], 0.0f, 1.0f);
        out[3] = vertex[3];
        break;
    default:
        if (g_soft.tex1 > 0) {
            copy_color(out, tex1);
        } else {
            copy_color(out, vertex);
        }
        break;
    }
}

static void resolve_dual_texture(const float *tex1, const float *tex2, float *out)
{
    if (g_soft.tex2 <= 0 || g_soft.dual_mode == 0) {
        copy_color(out, tex1);
        return;
    }

    evaluate_combine_mode(g_soft.dual_mode, tex1, tex2, tex2, out);
}

static void blend_factor(int mode, const float *self, const float *other, float *factor)
{
    switch (mode) {
    case X_ZERO:
        fill_color(factor, 0.0f, 0.0f, 0.0f, 0.0f);
        break;
    case X_ONE:
        fill_color(factor, 1.0f, 1.0f, 1.0f, 1.0f);
        break;
    case X_OTHER:
        copy_color(factor, other);
        break;
    case X_ALPHA:
        fill_color(factor, self[3], self[3], self[3], self[3]);
        break;
    case X_OTHERALPHA:
        fill_color(factor, other[3], other[3], other[3], other[3]);
        break;
    case X_INVOTHER:
        factor[0] = 1.0f - other[0];
        factor[1] = 1.0f - other[1];
        factor[2] = 1.0f - other[2];
        factor[3] = 1.0f - other[3];
        break;
    case X_INVALPHA:
        fill_color(
            factor,
            1.0f - self[3],
            1.0f - self[3],
            1.0f - self[3],
            1.0f - self[3]
        );
        break;
    case X_INVOTHERALPHA:
        fill_color(
            factor,
            1.0f - other[3],
            1.0f - other[3],
            1.0f - other[3],
            1.0f - other[3]
        );
        break;
    default:
        fill_color(factor, 1.0f, 1.0f, 1.0f, 1.0f);
        break;
    }
}

static void apply_fog(float depth, float *rgba)
{
    float factor;

    if (g_soft.fog_type == X_DISABLE || g_soft.fog_max <= g_soft.fog_min) {
        return;
    }

    factor = clampf((depth - g_soft.fog_min) / (g_soft.fog_max - g_soft.fog_min), 0.0f, 1.0f);

    if (g_soft.fog_type == X_LINEARADD) {
        rgba[0] = clampf(rgba[0] + g_soft.fog_color[0] * factor, 0.0f, 1.0f);
        rgba[1] = clampf(rgba[1] + g_soft.fog_color[1] * factor, 0.0f, 1.0f);
        rgba[2] = clampf(rgba[2] + g_soft.fog_color[2] * factor, 0.0f, 1.0f);
    } else {
        rgba[0] = rgba[0] * (1.0f - factor) + g_soft.fog_color[0] * factor;
        rgba[1] = rgba[1] * (1.0f - factor) + g_soft.fog_color[1] * factor;
        rgba[2] = rgba[2] * (1.0f - factor) + g_soft.fog_color[2] * factor;
    }
}

static float edge_function(float ax, float ay, float bx, float by, float cx, float cy)
{
    return (cx - ax) * (by - ay) - (cy - ay) * (bx - ax);
}

static void shade_fragment(
    const SoftVertex *v0,
    const SoftVertex *v1,
    const SoftVertex *v2,
    float b0,
    float b1,
    float b2,
    float *out_rgba,
    float *out_depth
)
{
    float inv_w;
    float p0;
    float p1;
    float p2;
    float vertex[4];
    float tex1[4];
    float tex2[4];
    float texture[4];
    float rgb_mode[4];
    float alpha_mode[4];

    inv_w = b0 * v0->inv_w + b1 * v1->inv_w + b2 * v2->inv_w;
    if (fabsf(inv_w) < 1.0e-6f) {
        *out_depth = XSOFT_DEPTH_CLEAR;
        fill_color(out_rgba, 0.0f, 0.0f, 0.0f, 0.0f);
        return;
    }

    p0 = (b0 * v0->inv_w) / inv_w;
    p1 = (b1 * v1->inv_w) / inv_w;
    p2 = (b2 * v2->inv_w) / inv_w;

    vertex[0] = p0 * v0->color[0] + p1 * v1->color[0] + p2 * v2->color[0];
    vertex[1] = p0 * v0->color[1] + p1 * v1->color[1] + p2 * v2->color[1];
    vertex[2] = p0 * v0->color[2] + p1 * v1->color[2] + p2 * v2->color[2];
    vertex[3] = p0 * v0->color[3] + p1 * v1->color[3] + p2 * v2->color[3];

    sample_texture(
        g_soft.tex1,
        p0 * v0->tex1[0] + p1 * v1->tex1[0] + p2 * v2->tex1[0],
        p0 * v0->tex1[1] + p1 * v1->tex1[1] + p2 * v2->tex1[1],
        tex1
    );
    sample_texture(
        g_soft.tex2,
        p0 * v0->tex2[0] + p1 * v1->tex2[0] + p2 * v2->tex2[0],
        p0 * v0->tex2[1] + p1 * v1->tex2[1] + p2 * v2->tex2[1],
        tex2
    );

    resolve_dual_texture(tex1, tex2, texture);

    evaluate_combine_mode(g_soft.combine_rgb, vertex, texture, tex2, rgb_mode);
    evaluate_combine_mode(g_soft.combine_alpha, vertex, texture, tex2, alpha_mode);

    out_rgba[0] = rgb_mode[0];
    out_rgba[1] = rgb_mode[1];
    out_rgba[2] = rgb_mode[2];
    out_rgba[3] = alpha_mode[3];

    *out_depth = (p0 * v0->depth + p1 * v1->depth + p2 * v2->depth) /
        (g_soft.zdecal > 0.0f ? g_soft.zdecal : 1.0f);

    apply_fog(*out_depth, out_rgba);
}

static void draw_triangle(const SoftVertex *v0, const SoftVertex *v1, const SoftVertex *v2)
{
    float area;
    int min_x;
    int max_x;
    int min_y;
    int max_y;
    int x;
    int y;

    if (!g_soft.color || g_soft.width <= 0 || g_soft.height <= 0) {
        return;
    }

    area = edge_function(v0->sx, v0->sy, v1->sx, v1->sy, v2->sx, v2->sy);
    if (fabsf(area) < 1.0e-6f) {
        return;
    }

    XSOFT_DIAG(g_soft.rasterized_tris++);
    XSOFT_DIAG(g_soft.last_tri_bbox[0] = fminf(v0->sx, fminf(v1->sx, v2->sx)));
    XSOFT_DIAG(g_soft.last_tri_bbox[1] = fminf(v0->sy, fminf(v1->sy, v2->sy)));
    XSOFT_DIAG(g_soft.last_tri_bbox[2] = fmaxf(v0->sx, fmaxf(v1->sx, v2->sx)));
    XSOFT_DIAG(g_soft.last_tri_bbox[3] = fmaxf(v0->sy, fmaxf(v1->sy, v2->sy)));

    min_x = clampi((int)floorf(fminf(v0->sx, fminf(v1->sx, v2->sx))), 0, g_soft.width - 1);
    max_x = clampi((int)ceilf(fmaxf(v0->sx, fmaxf(v1->sx, v2->sx))), 0, g_soft.width - 1);
    min_y = clampi((int)floorf(fminf(v0->sy, fminf(v1->sy, v2->sy))), 0, g_soft.height - 1);
    max_y = clampi((int)ceilf(fmaxf(v0->sy, fmaxf(v1->sy, v2->sy))), 0, g_soft.height - 1);

    for (y = min_y; y <= max_y; y++) {
        for (x = min_x; x <= max_x; x++) {
            float px = (float)x + 0.5f;
            float py = (float)y + 0.5f;
            float w0 = edge_function(v1->sx, v1->sy, v2->sx, v2->sy, px, py);
            float w1 = edge_function(v2->sx, v2->sy, v0->sx, v0->sy, px, py);
            float w2 = edge_function(v0->sx, v0->sy, v1->sx, v1->sy, px, py);
            float bary0;
            float bary1;
            float bary2;
            float src[4];
            float dst[4];
            float src_factor[4];
            float dst_factor[4];
            float out[4];
            float depth;
            int index;

            if ((area > 0.0f && (w0 < 0.0f || w1 < 0.0f || w2 < 0.0f)) ||
                (area < 0.0f && (w0 > 0.0f || w1 > 0.0f || w2 > 0.0f))) {
                continue;
            }

            bary0 = w0 / area;
            bary1 = w1 / area;
            bary2 = w2 / area;

            shade_fragment(v0, v1, v2, bary0, bary1, bary2, src, &depth);

            if (g_soft.alpha_limit < 1.0f && src[3] < g_soft.alpha_limit) {
                continue;
            }

            index = y * g_soft.width + x;
            if (g_soft.depth_test && depth > g_soft.depth[index]) {
                continue;
            }

            XSOFT_DIAG(g_soft.shaded_pixels++);

            unpack_color(g_soft.color[index], dst);
            blend_factor(g_soft.blend_src, src, dst, src_factor);
            blend_factor(g_soft.blend_dst, dst, src, dst_factor);

            out[0] = clampf(src[0] * src_factor[0] + dst[0] * dst_factor[0], 0.0f, 1.0f);
            out[1] = clampf(src[1] * src_factor[1] + dst[1] * dst_factor[1], 0.0f, 1.0f);
            out[2] = clampf(src[2] * src_factor[2] + dst[2] * dst_factor[2], 0.0f, 1.0f);
            out[3] = clampf(src[3] * src_factor[3] + dst[3] * dst_factor[3], 0.0f, 1.0f);

            if (g_soft.color_mask) {
                g_soft.color[index] = pack_color(out);
                XSOFT_DIAG(g_soft.color_writes++);
            }
            if (g_soft.depth_mask) {
                g_soft.depth[index] = depth;
            }
        }
    }
}

static SoftVertex make_vertex(const xt_pos *pos, const xt_data *data)
{
    SoftVertex vertex;

    memset(&vertex, 0, sizeof(vertex));
    vertex.clip_x = pos ? pos->x : 0.0f;
    vertex.clip_y = pos ? pos->y : 0.0f;
    vertex.clip_w = pos ? pos->z : 1.0f;
    vertex.color[0] = data ? data->r : 1.0f;
    vertex.color[1] = data ? data->g : 1.0f;
    vertex.color[2] = data ? data->b : 1.0f;
    vertex.color[3] = data ? data->a : 1.0f;
    vertex.tex1[0] = data ? data->t1s : 0.0f;
    vertex.tex1[1] = data ? data->t1t : 0.0f;
    vertex.tex2[0] = data ? data->t2s : 0.0f;
    vertex.tex2[1] = data ? data->t2t : 0.0f;
    update_raster_vertex(&vertex);

    return vertex;
}

static int clip_polygon_against_plane(
    const SoftVertex *input,
    int input_count,
    int plane,
    SoftVertex *output
)
{
    int output_count;
    int i;

    if (input_count <= 0) {
        return 0;
    }

    output_count = 0;

    for (i = 0; i < input_count; i++) {
        const SoftVertex *current = &input[i];
        const SoftVertex *previous = &input[(i + input_count - 1) % input_count];
        float current_distance = clip_plane_distance(current, plane);
        float previous_distance = clip_plane_distance(previous, plane);
        int current_inside = current_distance >= 0.0f;
        int previous_inside = previous_distance >= 0.0f;

        if (previous_inside != current_inside) {
            float denom = previous_distance - current_distance;
            float t = 0.0f;
            if (fabsf(denom) > 1.0e-6f) {
                t = previous_distance / denom;
            }
            if (output_count < XSOFT_MAX_CLIPPED_VERTICES) {
                output[output_count++] = lerp_vertex(previous, current, t);
            }
        }

        if (current_inside && output_count < XSOFT_MAX_CLIPPED_VERTICES) {
            output[output_count++] = *current;
        }
    }

    return output_count;
}

static void draw_polygon_clipped(const SoftVertex *vertices, int vertex_count)
{
    SoftVertex buffers[2][XSOFT_MAX_CLIPPED_VERTICES];
    const SoftVertex *src;
    SoftVertex *dst;
    int src_index;
    int count;
    int plane;
    int i;

    if (vertex_count < 3 || vertex_count > XSOFT_MAX_PRIM_VERTICES) {
        return;
    }

    memcpy(buffers[0], vertices, (size_t)vertex_count * sizeof(vertices[0]));
    src_index = 0;
    count = vertex_count;

    for (plane = 0; plane < 5 && count >= 3; plane++) {
        src = buffers[src_index];
        dst = buffers[src_index ^ 1];
        count = clip_polygon_against_plane(src, count, plane, dst);
        src_index ^= 1;
    }

    if (count < 3) {
        return;
    }

    src = buffers[src_index];
    XSOFT_DIAG(g_soft.submitted_tris += count - 2);
    for (i = 1; i < count - 1; i++) {
        draw_triangle(&src[0], &src[i], &src[i + 1]);
    }
}

static void submit_vertex(const SoftVertex *vertex)
{
    if (g_soft.prim_count >= XSOFT_MAX_PRIM_VERTICES) {
        g_soft.prim_count = 0;
    }

    g_soft.prim[g_soft.prim_count++] = *vertex;
    XSOFT_DIAG(g_soft.submitted_vertices++);

    if (g_soft.prim_type == X_TRIANGLES && g_soft.prim_count == 3) {
        draw_polygon_clipped(g_soft.prim, 3);
        g_soft.prim_count = 0;
    } else if (g_soft.prim_type == X_QUADS && g_soft.prim_count == 4) {
        draw_polygon_clipped(g_soft.prim, 4);
        g_soft.prim_count = 0;
    }
}

static void free_textures(void)
{
    int i;

    for (i = 1; i < XSOFT_MAX_TEXTURES; i++) {
        free(g_textures[i].pixels);
        memset(&g_textures[i], 0, sizeof(g_textures[i]));
    }
}

void x_init(void)
{
    memset(&g_soft, 0, sizeof(g_soft));
    memset(&g_data, 0, sizeof(g_data));
    memset(&g_pos, 0, sizeof(g_pos));
    g_data.r = 1.0f;
    g_data.g = 1.0f;
    g_data.b = 1.0f;
    g_data.a = 1.0f;
    g_pos.z = 1.0f;
    g_soft.alpha_limit = 1.0f;
    g_soft.blend_src = X_ONE;
    g_soft.blend_dst = X_ZERO;
    g_soft.combine_rgb = X_COLOR;
    g_soft.combine_alpha = X_COLOR;
    g_soft.zdecal = 1.0f;
    fill_color(g_soft.env, 1.0f, 1.0f, 1.0f, 1.0f);
    g_soft.fog_type = X_DISABLE;
    g_debug_state_logs = 0;
    g_debug_texture_logs = 0;
}

void x_deinit(void)
{
    free(g_soft.color);
    free(g_soft.depth);
    g_soft.color = NULL;
    g_soft.depth = NULL;
    g_soft.width = 0;
    g_soft.height = 0;
    g_soft.opened = 0;
    free_textures();
    android_port_set_render_active(0);
}

int x_query(void *hdc, void *hwnd)
{
    (void)hdc;
    (void)hwnd;
    return 0;
}

int x_open(void *hdc, void *hwnd, int width, int height, int buffers, int vsync)
{
    size_t pixel_count;

    (void)hdc;
    (void)hwnd;
    (void)buffers;
    (void)vsync;

    free(g_soft.color);
    free(g_soft.depth);
    g_soft.color = NULL;
    g_soft.depth = NULL;

    if (width <= 0 || height <= 0) {
        return -1;
    }

    pixel_count = (size_t)width * (size_t)height;
    g_soft.color = (uint32_t *)calloc(pixel_count, sizeof(uint32_t));
    g_soft.depth = (float *)malloc(pixel_count * sizeof(float));
    if (!g_soft.color || !g_soft.depth) {
        free(g_soft.color);
        free(g_soft.depth);
        g_soft.color = NULL;
        g_soft.depth = NULL;
        return -1;
    }

    g_soft.width = width;
    g_soft.height = height;
    g_soft.viewport_x0 = 0.0f;
    g_soft.viewport_y0 = 0.0f;
    g_soft.viewport_x1 = (float)(width - 1);
    g_soft.viewport_y1 = (float)(height - 1);
    g_soft.color_mask = 1;
    g_soft.depth_mask = 1;
    g_soft.depth_test = 1;
    g_soft.opened = 1;
    x_clear(1, 1, 0.0f, 0.0f, 0.0f);
    android_port_set_render_active(1);
    return 0;
}

void x_select(int which)
{
    (void)which;
}

void x_resize(int width, int height)
{
    if (width == g_soft.width && height == g_soft.height) {
        return;
    }
    x_open(NULL, NULL, width, height, 1, 0);
}

void x_close(int which)
{
    (void)which;
    x_deinit();
}

void x_fullscreen(int fullscreen)
{
    android_port_set_render_active(fullscreen ? 1 : 0);
}

void x_clear(int writecolor, int writedepth, float cr, float cg, float cb)
{
    size_t i;
    size_t pixel_count;
    float clear_color[4];
    uint32_t packed;

    if (!g_soft.color || !g_soft.depth) {
        return;
    }

    pixel_count = (size_t)g_soft.width * (size_t)g_soft.height;
    fill_color(clear_color, cr, cg, cb, 1.0f);
    packed = pack_color(clear_color);

    for (i = 0; i < pixel_count; i++) {
        if (writecolor) {
            g_soft.color[i] = packed;
        }
        if (writedepth) {
            g_soft.depth[i] = XSOFT_DEPTH_CLEAR;
        }
    }
}

int x_readfb(int fb, int x, int y, int xs, int ys, char *buffer, int bufrowlen)
{
    int row;
    int col;

    if (!(fb & X_FB_RGBA8888) || !buffer || !g_soft.color) {
        return 1;
    }

    for (row = 0; row < ys; row++) {
        unsigned char *dst = (unsigned char *)buffer + row * bufrowlen;
        int sy = y + row;

        if (sy < 0 || sy >= g_soft.height) {
            memset(dst, 0, (size_t)xs * 4u);
            continue;
        }

        for (col = 0; col < xs; col++) {
            int sx = x + col;
            uint32_t pixel = 0;

            if (sx >= 0 && sx < g_soft.width) {
                pixel = g_soft.color[sy * g_soft.width + sx];
            }

            dst[col * 4 + 0] = (unsigned char)((pixel >> 16) & 255);
            dst[col * 4 + 1] = (unsigned char)((pixel >> 8) & 255);
            dst[col * 4 + 2] = (unsigned char)(pixel & 255);
            dst[col * 4 + 3] = (unsigned char)((pixel >> 24) & 255);
        }
    }

    return 0;
}

int x_writefb(int fb, int x, int y, int xs, int ys, char *buffer, int bufrowlen)
{
    (void)fb;
    (void)x;
    (void)y;
    (void)xs;
    (void)ys;
    (void)buffer;
    (void)bufrowlen;
    return 1;
}

void x_finish(void)
{
    if (!g_soft.color || !g_soft.opened) {
        return;
    }

#if XSOFT_ENABLE_DIAGNOSTICS
    if ((g_soft.frame_id < 10 || (g_soft.frame_id % 120) == 0) &&
        (g_soft.submitted_vertices || g_soft.submitted_tris || g_soft.rasterized_tris)) {
        print(
            "xsoft frame %i: vx=%i tris=%i rast=%i px=%i writes=%i bbox=(%.1f,%.1f)-(%.1f,%.1f)\n",
            g_soft.frame_id,
            g_soft.submitted_vertices,
            g_soft.submitted_tris,
            g_soft.rasterized_tris,
            g_soft.shaded_pixels,
            g_soft.color_writes,
            g_soft.last_tri_bbox[0],
            g_soft.last_tri_bbox[1],
            g_soft.last_tri_bbox[2],
            g_soft.last_tri_bbox[3]
        );
    }
#endif
    android_port_present_framebuffer(g_soft.color, g_soft.width, g_soft.height);
    XSOFT_DIAG(g_soft.frame_id++);
    XSOFT_DIAG(g_soft.submitted_vertices = 0);
    XSOFT_DIAG(g_soft.submitted_tris = 0);
    XSOFT_DIAG(g_soft.rasterized_tris = 0);
    XSOFT_DIAG(g_soft.shaded_pixels = 0);
    XSOFT_DIAG(g_soft.color_writes = 0);
    XSOFT_DIAG(g_soft.last_tri_bbox[0] = 0.0f);
    XSOFT_DIAG(g_soft.last_tri_bbox[1] = 0.0f);
    XSOFT_DIAG(g_soft.last_tri_bbox[2] = 0.0f);
    XSOFT_DIAG(g_soft.last_tri_bbox[3] = 0.0f);
}

void x_getstats(xt_stats *s, int ssize)
{
    if (!s || ssize <= 0) {
        return;
    }
    memset(s, 0, (size_t)ssize);
}

void x_reset(void)
{
    g_soft.color_mask = 1;
    g_soft.depth_mask = 1;
    g_soft.depth_test = 1;
    g_soft.alpha_limit = 1.0f;
    g_soft.blend_src = X_ONE;
    g_soft.blend_dst = X_ZERO;
    g_soft.combine_rgb = X_COLOR;
    g_soft.combine_alpha = X_COLOR;
    g_soft.dual_mode = 0;
    g_soft.tex1 = 0;
    g_soft.tex2 = 0;
    g_soft.prim_type = 0;
    g_soft.prim_count = 0;
    g_soft.zdecal = 1.0f;
    g_soft.fog_type = X_DISABLE;
    fill_color(g_soft.env, 1.0f, 1.0f, 1.0f, 1.0f);
}

void x_forcegeometry(int forceon, int forceoff)
{
    g_soft.geometry_flags |= forceon;
    g_soft.geometry_flags &= ~forceoff;
}

void x_geometry(int flags)
{
    g_soft.geometry_flags = flags;
}

int x_mask(int colormask, int depthmask, int depthtest)
{
    g_soft.color_mask = (colormask != X_DISABLE);
    g_soft.depth_mask = (depthmask != X_DISABLE);
    g_soft.depth_test = (depthtest != X_DISABLE);
    return 0;
}

int x_dither(int type)
{
    (void)type;
    return 0;
}

int x_fog(int type, float min, float max, float r, float g, float b)
{
    g_soft.fog_type = type;
    g_soft.fog_min = min;
    g_soft.fog_max = max;
    g_soft.fog_color[0] = r;
    g_soft.fog_color[1] = g;
    g_soft.fog_color[2] = b;
    g_soft.fog_color[3] = 1.0f;
    return 0;
}

int x_zrange(float znear, float zfar)
{
    (void)znear;
    (void)zfar;
    return 0;
}

int x_zdecal(float factor)
{
    g_soft.zdecal = factor > 0.0f ? factor : 1.0f;
    return 0;
}

int x_blend(int src, int dst)
{
    g_soft.blend_src = src;
    g_soft.blend_dst = dst;
    if (XSOFT_ENABLE_DIAGNOSTICS && g_debug_state_logs < 24) {
        print("xsoft blend %d,%d\n", src, dst);
        g_debug_state_logs++;
    }
    return 0;
}

int x_alphatest(float limit)
{
    g_soft.alpha_limit = limit;
    return 0;
}

int x_combine(int colortext1)
{
    g_soft.combine_rgb = colortext1;
    g_soft.combine_alpha = colortext1;
    return 0;
}

int x_combine2(int colortext1, int text1text2, int sametex)
{
    g_soft.combine_rgb = colortext1;
    g_soft.combine_alpha = colortext1;
    g_soft.dual_mode = text1text2;
    (void)sametex;
    return 0;
}

int x_envcolor(float r, float g, float b, float a)
{
    fill_color(g_soft.env, r, g, b, a);
    return 0;
}

int x_procombine(int rgb, int alpha)
{
    g_soft.combine_rgb = rgb;
    g_soft.combine_alpha = alpha;
    if (XSOFT_ENABLE_DIAGNOSTICS && g_debug_state_logs < 24) {
        print("xsoft combine rgb=%d alpha=%d tex=%d\n", rgb, alpha, g_soft.tex1);
        g_debug_state_logs++;
    }
    return 0;
}

int x_procombine2(int rgb, int alpha, int text1text2, int sametex)
{
    g_soft.combine_rgb = rgb;
    g_soft.combine_alpha = alpha;
    g_soft.dual_mode = text1text2;
    (void)sametex;
    if (XSOFT_ENABLE_DIAGNOSTICS && g_debug_state_logs < 24) {
        print("xsoft combine2 rgb=%d alpha=%d tex=%d/%d mode=%d\n", rgb, alpha, g_soft.tex1, g_soft.tex2, text1text2);
        g_debug_state_logs++;
    }
    return 0;
}

int x_texture(int text1handle)
{
    g_soft.tex1 = text1handle;
    g_soft.tex2 = 0;
    if (XSOFT_ENABLE_DIAGNOSTICS && g_debug_state_logs < 24) {
        print("xsoft texture %d\n", text1handle);
        g_debug_state_logs++;
    }
    return 0;
}

int x_texture2(int text1handle, int text2handle)
{
    g_soft.tex1 = text1handle;
    g_soft.tex2 = text2handle;
    if (XSOFT_ENABLE_DIAGNOSTICS && g_debug_state_logs < 24) {
        print("xsoft texture2 %d,%d\n", text1handle, text2handle);
        g_debug_state_logs++;
    }
    return 0;
}

int x_createtexture(int format, int width, int height)
{
    int handle;

    for (handle = 1; handle < XSOFT_MAX_TEXTURES; handle++) {
        if (!g_textures[handle].used) {
            g_textures[handle].used = 1;
            g_textures[handle].format = format;
            g_textures[handle].width = width;
            g_textures[handle].height = height;
            g_textures[handle].clamp_x = (format & X_CLAMP) && !(format & X_CLAMPNOX);
            g_textures[handle].clamp_y = (format & X_CLAMP) && !(format & X_CLAMPNOY);
            g_textures[handle].pixels = (uint32_t *)calloc((size_t)width * (size_t)height, sizeof(uint32_t));
            if (!g_textures[handle].pixels) {
                memset(&g_textures[handle], 0, sizeof(g_textures[handle]));
                return -1;
            }
            return handle;
        }
    }

    return -1;
}

int x_loadtexturelevel(int handle, int level, char *data)
{
    SoftTexture *texture = xsoft_texture(handle);
    size_t pixel_count;
    size_t i;
    const unsigned char *src = (const unsigned char *)data;

    if (!texture || !data || level != 0) {
        return 1;
    }

    pixel_count = (size_t)texture->width * (size_t)texture->height;
    for (i = 0; i < pixel_count; i++) {
        unsigned char r = src[i * 4 + 0];
        unsigned char g = src[i * 4 + 1];
        unsigned char b = src[i * 4 + 2];
        unsigned char a = src[i * 4 + 3];
        texture->pixels[i] = ((uint32_t)a << 24) |
            ((uint32_t)r << 16) |
            ((uint32_t)g << 8) |
            (uint32_t)b;
    }

    if (XSOFT_ENABLE_DIAGNOSTICS && g_debug_texture_logs < 16) {
        print(
            "xsoft texload h=%d size=%dx%d first=%02X%02X%02X%02X\n",
            handle,
            texture->width,
            texture->height,
            src[0],
            src[1],
            src[2],
            src[3]
        );
        g_debug_texture_logs++;
    }

    return 0;
}

void x_freetexture(int handle)
{
    SoftTexture *texture = xsoft_texture(handle);

    if (!texture) {
        return;
    }

    free(texture->pixels);
    memset(texture, 0, sizeof(*texture));
}

void x_cleartexmem(void)
{
    free_textures();
}

int x_gettextureinfo(int handle, int *format, int *memformat, int *width, int *height)
{
    SoftTexture *texture = xsoft_texture(handle);

    if (!texture) {
        return 1;
    }

    if (format) {
        *format = texture->format;
    }
    if (memformat) {
        *memformat = X_RGBA8888;
    }
    if (width) {
        *width = texture->width;
    }
    if (height) {
        *height = texture->height;
    }

    return 0;
}

uchar *x_opentexturedata(int handle)
{
    SoftTexture *texture = xsoft_texture(handle);
    return texture ? (uchar *)texture->pixels : NULL;
}

void x_closetexturedata(int handle)
{
    (void)handle;
}

void x_viewport(float x0, float y0, float x1, float y1)
{
    g_soft.viewport_x0 = x0;
    g_soft.viewport_y0 = y0;
    g_soft.viewport_x1 = x1;
    g_soft.viewport_y1 = y1;
}

void x_frustum(float xmin, float xmax, float ymin, float ymax, float znear, float zfar)
{
    (void)xmin;
    (void)xmax;
    (void)ymin;
    (void)ymax;
    (void)znear;
    (void)zfar;
}

void x_ortho(float xmin, float ymin, float xmax, float ymax, float znear, float zfar)
{
    (void)xmin;
    (void)ymin;
    (void)xmax;
    (void)ymax;
    (void)znear;
    (void)zfar;
}

void x_projmatrix(xt_matrix *matrix)
{
    (void)matrix;
}

void x_projection(float fov, float znear, float zfar)
{
    (void)fov;
    (void)znear;
    (void)zfar;
}

void x_cameramatrix(xt_matrix *matrix)
{
    (void)matrix;
}

void x_matrix(xt_matrix *matrix)
{
    (void)matrix;
}

void x_getmatrix(xt_matrix *matrix)
{
    if (matrix) {
        memset(matrix, 0, sizeof(*matrix));
    }
}

void x_begin(int type)
{
    g_soft.prim_type = type;
    g_soft.prim_count = 0;
}

void x_end(void)
{
    g_soft.prim_count = 0;
}

void x_flush(void)
{
}

void x_vx(xt_pos *pos, xt_data *data)
{
    SoftVertex vertex = make_vertex(pos, data);
    submit_vertex(&vertex);
}

void x_vxa(int arrayindex, xt_data *data)
{
    (void)arrayindex;
    (void)data;
}

void x_vxarray(xt_pos *pos, int size, char *mask)
{
    (void)pos;
    (void)size;
    (void)mask;
}
