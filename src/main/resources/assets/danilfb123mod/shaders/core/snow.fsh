#version 150

uniform sampler2D Sampler0;
uniform vec4 Consts;
uniform vec3 SunDir;
uniform vec4 Params;     // x = ambient, y = интенсивность солнца
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in vec3 vRel;
in vec2 vUv;
in float vFade;

out vec4 fragColor;

float heightAt(vec2 uv) {
    vec4 s = texture(Sampler0, uv);
    return Consts.x * (1.0 - s.r) + Consts.y * s.g;
}

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    ivec2 size = textureSize(Sampler0, 0);
    vec2 ts = 1.0 / vec2(size);
    float w = Consts.z / float(size.x); // размер текселя в блоках

    float hl = heightAt(vUv - vec2(ts.x, 0.0));
    float hr = heightAt(vUv + vec2(ts.x, 0.0));
    float hd = heightAt(vUv - vec2(0.0, ts.y));
    float hu = heightAt(vUv + vec2(0.0, ts.y));
    vec3 n = normalize(vec3((hl - hr) / (2.0 * w), 1.0, (hd - hu) / (2.0 * w)));
    n = normalize(mix(vec3(0.0, 1.0, 0.0), n, vFade));

    float comp = texture(Sampler0, vUv).r * vFade;

    float diff = max(dot(n, normalize(SunDir)), 0.0);
    vec3 base = vec3(0.93, 0.95, 0.98);
    vec3 amb = vec3(0.50, 0.60, 0.78) * Params.x;
    vec3 sun = vec3(1.0, 0.96, 0.88) * Params.y * diff;
    vec3 lit = base * (amb + sun);

    // в глубоком следе снег темнее и холоднее (имитация подповерхностного рассеяния и затенения)
    lit *= 1.0 - 0.40 * comp;
    lit = mix(lit, lit * vec3(0.62, 0.78, 1.15), comp * 0.8);

    // зернистость и блёстки кристаллов
    vec2 g = floor(vUv * float(size.x) * 3.0);
    float r = hash(g);
    lit *= 0.97 + 0.06 * hash(floor(vUv * float(size.x) * 5.0));
    float sparkle = step(0.9975, r) * (0.6 + 0.4 * sin(r * 600.0 + dot(vRel, vec3(0.7)) * 3.0));
    lit += vec3(sparkle) * Params.y * (1.0 - comp);

    float dist = length(vRel);
    float f = clamp((dist - FogStart) / max(FogEnd - FogStart, 0.001), 0.0, 1.0);
    fragColor = vec4(mix(lit, FogColor.rgb, f), 1.0);
}
