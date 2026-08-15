#version 120

uniform sampler2D textureIn;
uniform vec3 color1;
uniform vec3 color2;
uniform float timeAngle;
uniform float spatialScale;
uniform float direction;
uniform float axis;
uniform float opacity;
uniform float strength;

void main() {
    vec4 textureColor = texture2D(textureIn, gl_TexCoord[0].st);
    float coordinate = axis < 0.5 ? gl_FragCoord.y : gl_FragCoord.x;
    float wave = (sin(timeAngle + coordinate * spatialScale * direction) + 1.0) * 0.5;
    vec3 gradientColor = mix(color1, color2, wave);
    vec3 finalColor = mix(textureColor.rgb, gradientColor, strength);
    gl_FragColor = vec4(finalColor, textureColor.a * opacity);
}
