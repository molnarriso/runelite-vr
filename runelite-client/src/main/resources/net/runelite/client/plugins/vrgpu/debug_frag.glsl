/*
 * Simple debug geometry shader — outputs per-vertex colour unchanged.
 */
#version 330

in vec4 fColor;
out vec4 FragColor;

void main()
{
    FragColor = fColor;
}
