#version 150

in vec3 Position;

uniform sampler2D Sampler0;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

uniform float Cell;      // размер ячейки сетки меша, блоков
uniform vec3 GridOffset; // (угол сетки - камера) по X/Z, и (земля - камера) по Y
uniform vec2 UvOrigin;   // UV центра сетки в карте деформаций
uniform vec4 Consts;     // x = толщина снега, y = макс. валик, z = размер окна карты (блоки), w = конец затухания

out vec3 vRel;
out vec2 vUv;
out float vFade;

void main() {
    vec2 rel = GridOffset.xz + Position.xz * Cell;
    vec2 uv = UvOrigin + Position.xz * Cell / Consts.z;

    // за пределами окна карты данные чужие (карта зациклена), там снег нетронутый
    float fade = 1.0 - smoothstep(Consts.w - 5.0, Consts.w, max(abs(rel.x), abs(rel.y)));
    vec4 s = textureLod(Sampler0, uv, 0.0);
    float h = Consts.x * (1.0 - s.r * fade) + Consts.y * s.g * fade;

    vec3 pos = vec3(rel.x, GridOffset.y + h, rel.y);
    vRel = pos;
    vUv = uv;
    vFade = fade;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
}
