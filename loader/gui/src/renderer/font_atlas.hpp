#pragma once
#include "core/math.hpp"
#include <d3d11.h>
#include <string>
#include <unordered_map>
#include <vector>
#include <cstdint>

struct FT_LibraryRec_;
struct FT_FaceRec_;

namespace mindless
{

struct Glyph
{
    Rect    uv;       // normalised UV rect in the atlas texture
    Vec2    bearing;  // offset from cursor to glyph top-left (bearing.y = above baseline)
    float   advance;  // horizontal advance in pixels
    float   width;
    float   height;
};

// Glyph atlas for a single font + size. Upload once, reuse forever.
class FontAtlas
{
public:
    FontAtlas() = default;
    ~FontAtlas();

    FontAtlas(const FontAtlas&) = delete;
    FontAtlas& operator=(const FontAtlas&) = delete;

    // Load font from file path.
    bool load(const char* path, float pixelHeight, ID3D11Device* device);

    // Load font from a memory buffer (e.g. embedded resource data).
    bool load_from_memory(const void* data, size_t size,
                          float pixelHeight, ID3D11Device* device);

    const Glyph* glyph(uint32_t codepoint) const;

    // Width of a UTF-8 string in pixels.
    float measure_text_width(const char* text) const;

    // ascender: distance from baseline to top of tallest glyph (positive, in px)
    float ascender()   const { return ascender_; }

    // descender: distance from baseline to bottom of descenders (negative, in px)
    float descender()  const { return descender_; }

    // capHeight: height of capital letters above baseline (the 'H' bearing).
    // Use this — not lineHeight — when you need the visual height of rendered text.
    float capHeight()  const { return capHeight_; }

    // lineHeight: full typographic line advance (ascender + descender + leading).
    // Use this only for multi-line spacing, not for single-line vertical centering.
    float lineHeight() const { return lineHeight_; }

    float fontSize()   const { return fontSize_; }

    ID3D11ShaderResourceView* srv() const { return srv_; }
    int atlas_width()  const { return atlasW_; }
    int atlas_height() const { return atlasH_; }

private:
    std::unordered_map<uint32_t, Glyph> glyphs_;
    ID3D11ShaderResourceView* srv_ = nullptr;

    float ascender_   = 0;
    float descender_  = 0;
    float capHeight_  = 0;
    float lineHeight_ = 0;
    float fontSize_   = 0;
    int   atlasW_     = 0;
    int   atlasH_     = 0;

    FT_LibraryRec_* ft_   = nullptr;
    FT_FaceRec_*    face_  = nullptr;
    void destroy_ft();
    bool build(float pixelHeight, ID3D11Device* device);
};

} // namespace mindless
