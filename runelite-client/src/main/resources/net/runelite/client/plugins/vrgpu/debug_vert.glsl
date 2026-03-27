/*
 * Simple debug geometry shader — position + per-vertex colour, no lighting.
 * Vertex format: vec3 position (OSRS world coords) + vec4 colour (RGBA).
 * Uses the same worldProj uniform as the main scene shader.
 */
#version 330

uniform mat4 worldProj;

layout(location = 0) in vec3 vPosition;
layout(location = 1) in vec4 vColor;

out vec4 fColor;

void main()
{
    gl_Position = worldProj * vec4(vPosition, 1.0);
    fColor = vColor;
}
