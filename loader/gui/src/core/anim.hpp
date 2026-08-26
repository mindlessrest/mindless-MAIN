#pragma once
#include <cmath>

namespace mindless
{

// Easing functions — all take t in [0,1] and return [0,1].
inline float ease_out_cubic(float t)
{
    float f = 1.0f - t;
    return 1.0f - f * f * f;
}

inline float ease_in_out_cubic(float t)
{
    return t < 0.5f
        ? 4.0f * t * t * t
        : 1.0f - std::pow(-2.0f * t + 2.0f, 3.0f) / 2.0f;
}

inline float ease_out_quad(float t)
{
    return 1.0f - (1.0f - t) * (1.0f - t);
}

inline float ease_out_quart(float t)
{
    float f = 1.0f - t;
    return 1.0f - f * f * f * f;
}

inline float ease_in_quart(float t)
{
    return t * t * t * t;
}

// Smoothly drives a float toward a target each frame.
// Call advance(dt) every frame; read value() for the current animated value.
struct Tween
{
    float current  = 0.0f;
    float target   = 0.0f;
    float speed    = 8.0f;   // units per second (exponential decay)

    void set(float v)    { target = v; }
    void snap(float v)   { current = target = v; }

    void advance(float dt)
    {
        float diff = target - current;
        current += diff * std::min(1.0f, speed * dt);
    }

    float value() const { return current; }
};

// Drives a float from 0→1 over a given duration.
// Call tick(dt) each frame; read t() for progress [0,1].
struct Timer
{
    float elapsed  = 0.0f;
    float duration = 1.0f;
    bool  done     = false;

    void reset(float dur = -1.0f)
    {
        if (dur > 0) duration = dur;
        elapsed = 0;
        done    = false;
    }

    void tick(float dt)
    {
        if (done) return;
        elapsed += dt;
        if (elapsed >= duration) {
            elapsed = duration;
            done    = true;
        }
    }

    float t()      const { return elapsed / duration; }
    float eased()  const { return ease_out_cubic(t()); }
};

} // namespace mindless
