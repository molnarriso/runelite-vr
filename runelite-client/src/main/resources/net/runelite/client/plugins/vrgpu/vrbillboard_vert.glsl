#version 330

uniform mat4 worldProj;

layout(location = 0) in vec3 vPosition;
layout(location = 1) in vec2 vUv;
layout(location = 2) in vec4 vColor;

out vec2 fUv;
out vec4 fColor;

void main()
{
    gl_Position = worldProj * vec4(vPosition, 1.0);
    fUv = vUv;
    fColor = vColor;
}
