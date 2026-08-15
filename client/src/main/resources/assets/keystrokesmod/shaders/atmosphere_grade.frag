#version 120

uniform sampler2D textureIn;
uniform vec2 texelSize;
uniform vec3 tintColor;
uniform float tintStrength;
uniform float exposure;
uniform float contrast;
uniform float saturation;
uniform float shadowDepth;
uniform float bloomStrength;
uniform float bloomThreshold;
uniform float vignette;

vec3 brightSample(vec2 uv) {
    vec3 color = texture2D(textureIn, uv).rgb;
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
    return color * smoothstep(bloomThreshold, 1.0, luminance);
}

void main() {
    vec2 uv = gl_TexCoord[0].st;
    vec3 source = texture2D(textureIn, uv).rgb;
    vec2 spread = texelSize * 3.0;
    vec3 bloom = brightSample(uv + vec2(spread.x, 0.0));
    bloom += brightSample(uv - vec2(spread.x, 0.0));
    bloom += brightSample(uv + vec2(0.0, spread.y));
    bloom += brightSample(uv - vec2(0.0, spread.y));
    bloom += brightSample(uv + spread);
    bloom += brightSample(uv - spread);
    bloom += brightSample(uv + vec2(spread.x, -spread.y));
    bloom += brightSample(uv + vec2(-spread.x, spread.y));
    bloom *= 0.125 * bloomStrength;

    vec3 color = source * exp2(exposure);
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
    float shadowMask = 1.0 - smoothstep(0.05, 0.52, luminance);
    color *= 1.0 - shadowMask * shadowDepth;
    color = (color - 0.5) * contrast + 0.5;
    luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = mix(vec3(luminance), color, saturation);
    color *= mix(vec3(1.0), tintColor * 1.45, tintStrength);
    color += bloom;

    float edge = 1.0 - smoothstep(0.22, 0.82, length(uv - 0.5));
    color *= mix(1.0 - vignette, 1.0, edge);
    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
