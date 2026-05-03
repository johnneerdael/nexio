package com.nexio.tv.integrations.hyperhdr.capture

internal object NvDownscaleShaders {

    /** Same vertex shader as PqDownscaleShaders — fullscreen quad with vec4 aTex. */
    val VERTEX = """#version 300 es
in vec4 aPos;
in vec4 aTex;
out vec2 vTex;
void main() { gl_Position = aPos; vTex = aTex.xy; }
"""

    /**
     * Y-plane fragment shader — BT.709 limited-range Y at 8-bit.
     *
     * SDR sources arrive as already-display-encoded (post-OETF) RGB in roughly [0, 1].
     * For an NV12 SDR send we don't apply any further transfer function. We just convert
     * BT.709 RGB → luma in 16..235 range, output as a single-channel unsigned byte.
     */
    val Y_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
in vec2 vTex;
out vec4 oColor;

void main() {
    vec3 rgb = texture(uTex, vTex).rgb;
    // BT.709 limited-range luma: 16..235 in 8-bit → /255 → 0.0627..0.9216 normalized.
    float y = (0.2126 * rgb.r + 0.7152 * rgb.g + 0.0722 * rgb.b) * (219.0 / 255.0)
            + (16.0 / 255.0);
    oColor = vec4(clamp(y, 0.0, 1.0), 0.0, 0.0, 1.0);
}
"""

    /**
     * UV-plane fragment shader — interleaved BT.709 limited-range Cb,Cr at 8-bit.
     */
    val UV_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
in vec2 vTex;
out vec4 oColor;

void main() {
    vec3 rgb = texture(uTex, vTex).rgb;
    // BT.709 limited-range Cb/Cr: 16..240 in 8-bit, centered at 128.
    float cb = (-0.1146 * rgb.r - 0.3854 * rgb.g + 0.5    * rgb.b) * (224.0 / 255.0)
             + (128.0 / 255.0);
    float cr = ( 0.5    * rgb.r - 0.4542 * rgb.g - 0.0458 * rgb.b) * (224.0 / 255.0)
             + (128.0 / 255.0);
    oColor = vec4(clamp(cb, 0.0, 1.0), clamp(cr, 0.0, 1.0), 0.0, 1.0);
}
"""
}
