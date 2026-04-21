#version 330

uniform mat4 worldProj;

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aTexCoord;

out vec2 TexCoord;

void main()
{
	gl_Position = worldProj * vec4(aPos, 1.0);
	TexCoord = aTexCoord;
}
