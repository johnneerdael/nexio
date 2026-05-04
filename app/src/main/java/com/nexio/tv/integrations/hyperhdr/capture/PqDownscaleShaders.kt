package com.nexio.tv.integrations.hyperhdr.capture

internal object PqDownscaleShaders {

    /**
     * Vertex shader for all passes — fullscreen quad.
     *
     * Uses homogeneous (vec4) attributes to match GlUtil.getNormalizedCoordinateBounds() and
     * GlUtil.getTextureCoordinateBounds() which both return 4-component float arrays.
     * vTex is derived from the first two components of aTex.
     */
    val VERTEX = """#version 300 es
in vec4 aPos;
in vec4 aTex;
out vec2 vTex;
void main() { gl_Position = aPos; vTex = aTex.xy; }
"""

    /**
     * Y-plane fragment shader. Samples the input texture (linear-light HDR fp16 per Media3's
     * default HDR pipeline), applies BT.2020 RGB → BT.2020 Y, applies ST.2084 OETF (PQ encode)
     * to produce a normalized 10-bit Y in `[0, 1]`, packs to P010 byte layout (10 bits in the
     * high bits, 6 zero pad bits in the low bits of a 16-bit unsigned integer).
     *
     * If Task 8 found that input is already PQ-encoded `[0, 1]` floats (not linear), remove the
     * `pq_encode(...)` call and use the sampled value directly.
     */
    val Y_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
in vec2 vTex;
out uint oY;

float pq_encode(float l) {
    // SMPTE ST.2084 OETF. l is normalized luminance in [0, 1] (1.0 = 10000 nits).
    float Lp = pow(max(l, 0.0), 0.1593017578125);
    float num = 0.8359375 + 18.8515625 * Lp;
    float den = 1.0 + 18.6875 * Lp;
    return pow(num / den, 78.84375);
}

void main() {
    vec3 rgb = texture(uTex, vTex).rgb;
    // BT.2020 luminance coefficients
    float l_lin = 0.2627 * rgb.r + 0.6780 * rgb.g + 0.0593 * rgb.b;
    float pq = pq_encode(l_lin);
    // Quantize to 10 bits with limited-range scaling: 64..940
    float y10 = pq * 876.0 + 64.0;
    uint y10u = uint(clamp(y10, 0.0, 1023.0));
    // Pack into the high 10 bits of a 16-bit word (P010 layout)
    oY = y10u << 6u;
}
"""

    /** UV-plane fragment shader. Same input; emits Cb and Cr packed into RG16UI. */
    val UV_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
in vec2 vTex;
out uvec2 oUV;

float pq_encode(float l) {
    float Lp = pow(max(l, 0.0), 0.1593017578125);
    float num = 0.8359375 + 18.8515625 * Lp;
    float den = 1.0 + 18.6875 * Lp;
    return pow(num / den, 78.84375);
}

void main() {
    vec3 rgb = texture(uTex, vTex).rgb;
    // BT.2020 RGB → CbCr (limited range, 10-bit centered at 512)
    float r_pq = pq_encode(rgb.r);
    float g_pq = pq_encode(rgb.g);
    float b_pq = pq_encode(rgb.b);
    float u10 = (-0.13963 * r_pq - 0.36037 * g_pq + 0.5 * b_pq) * 896.0 + 512.0;
    float v10 = ( 0.5 * r_pq - 0.45979 * g_pq - 0.04021 * b_pq) * 896.0 + 512.0;
    uint u10u = uint(clamp(u10, 0.0, 1023.0));
    uint v10u = uint(clamp(v10, 0.0, 1023.0));
    oUV = uvec2(u10u << 6u, v10u << 6u);
}
"""
}
