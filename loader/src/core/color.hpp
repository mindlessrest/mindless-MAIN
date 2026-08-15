#pragma once
#include <cstdint>

namespace mindless
{

// Colors are stored as 0xRRGGBB or 0xAARRGGBB packed integers.
// The Color struct normalizes to float RGBA for the GPU.
struct Color
{
    float r, g, b, a;

    Color() : r(0), g(0), b(0), a(1) {}
    Color(float red, float green, float blue, float alpha = 1.0f)
        : r(red), g(green), b(blue), a(alpha) {}

    // Construct from 0xRRGGBB — alpha defaults to 1.0
    Color(uint32_t rgb)
    {
        r = ((rgb >> 16) & 0xFF) / 255.0f;
        g = ((rgb >>  8) & 0xFF) / 255.0f;
        b = ((rgb >>  0) & 0xFF) / 255.0f;
        a = 1.0f;
    }

    Color with_alpha(float alpha) const { return { r, g, b, alpha }; }

    // Lighten by factor [0,1]
    Color lightened(float t) const
    {
        return { r + (1.0f - r) * t, g + (1.0f - g) * t, b + (1.0f - b) * t, a };
    }

    // Darken by factor [0,1]
    Color darkened(float t) const
    {
        return { r * (1.0f - t), g * (1.0f - t), b * (1.0f - t), a };
    }

    Color lerp(const Color& other, float t) const
    {
        return {
            r + (other.r - r) * t,
            g + (other.g - g) * t,
            b + (other.b - b) * t,
            a + (other.a - a) * t
        };
    }
};

} // namespace mindless
