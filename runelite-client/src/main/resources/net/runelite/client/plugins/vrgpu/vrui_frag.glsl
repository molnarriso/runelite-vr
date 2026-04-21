#version 330

uniform sampler2D tex;
uniform vec4 alphaOverlay;

in vec2 TexCoord;

out vec4 FragColor;

vec4 alphaBlend(vec4 src, vec4 dst)
{
	return vec4(src.rgb + dst.rgb * (1.0f - src.a), src.a + dst.a * (1.0f - src.a));
}

void main()
{
	vec4 c = texture(tex, TexCoord);
	FragColor = alphaBlend(c, alphaOverlay);
}
