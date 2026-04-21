#version 330

uniform sampler2D tex;
uniform vec4 alphaOverlay;
uniform int useTexture;
uniform vec4 solidColor;

in vec2 TexCoord;

out vec4 FragColor;

vec4 alphaBlend(vec4 src, vec4 dst)
{
	return vec4(src.rgb + dst.rgb * (1.0f - src.a), src.a + dst.a * (1.0f - src.a));
}

void main()
{
	if (useTexture == 0)
	{
		FragColor = solidColor;
		return;
	}

	vec4 c = texture(tex, TexCoord);
	FragColor = alphaBlend(c, alphaOverlay);
}
