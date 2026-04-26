#version 330

uniform sampler2D tex;
uniform int useTexture;

in vec2 fUv;
in vec4 fColor;

out vec4 FragColor;

void main()
{
    if (useTexture != 0)
    {
        FragColor = texture(tex, fUv) * fColor;
    }
    else
    {
        FragColor = fColor;
    }
}
