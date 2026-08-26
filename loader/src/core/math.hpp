#pragma once
#include <algorithm>
#include <cmath>

namespace mindless
{

struct Vec2
{
    float x = 0, y = 0;

    Vec2 operator+(const Vec2& o) const { return { x + o.x, y + o.y }; }
    Vec2 operator-(const Vec2& o) const { return { x - o.x, y - o.y }; }
    Vec2 operator*(float s)       const { return { x * s,   y * s   }; }
    Vec2& operator+=(const Vec2& o) { x += o.x; y += o.y; return *this; }
};

struct Rect
{
    float x = 0, y = 0, w = 0, h = 0;

    float right()  const { return x + w; }
    float bottom() const { return y + h; }

    bool contains(Vec2 p) const
    {
        return p.x >= x && p.x < x + w && p.y >= y && p.y < y + h;
    }

    Rect inset(float amount) const
    {
        return { x + amount, y + amount, w - amount * 2, h - amount * 2 };
    }

    Rect inset(float horizontal, float vertical) const
    {
        return { x + horizontal, y + vertical, w - horizontal * 2, h - vertical * 2 };
    }

    Rect translated(float dx, float dy) const
    {
        return { x + dx, y + dy, w, h };
    }

    Rect intersect(const Rect& o) const
    {
        float x1 = std::max(x, o.x);
        float y1 = std::max(y, o.y);
        float x2 = std::min(right(), o.right());
        float y2 = std::min(bottom(), o.bottom());
        return { x1, y1, std::max(0.0f, x2 - x1), std::max(0.0f, y2 - y1) };
    }
};

inline float clamp(float v, float lo, float hi)
{
    return std::max(lo, std::min(hi, v));
}

inline float lerp(float a, float b, float t)
{
    return a + (b - a) * t;
}

} // namespace mindless
