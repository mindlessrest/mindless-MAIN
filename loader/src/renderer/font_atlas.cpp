#include "font_atlas.hpp"
#include <ft2build.h>
#include FT_FREETYPE_H
#include <vector>
#include <cstring>
#include <algorithm>

namespace mindless
{

uint32_t decode_utf8(const char*& text)
{
    const auto* bytes = reinterpret_cast<const unsigned char*>(text);
    if (!*bytes) return 0;
    uint32_t codepoint = 0;
    size_t length = 0;
    if (bytes[0] < 0x80) { codepoint = bytes[0]; length = 1; }
    else if ((bytes[0] & 0xE0) == 0xC0) { codepoint = bytes[0] & 0x1F; length = 2; }
    else if ((bytes[0] & 0xF0) == 0xE0) { codepoint = bytes[0] & 0x0F; length = 3; }
    else if ((bytes[0] & 0xF8) == 0xF0) { codepoint = bytes[0] & 0x07; length = 4; }
    else { ++text; return 0xFFFD; }
    for (size_t i = 1; i < length; ++i)
    {
        if ((bytes[i] & 0xC0) != 0x80) { ++text; return 0xFFFD; }
        codepoint = (codepoint << 6) | (bytes[i] & 0x3F);
    }
    const bool invalid = (length == 2 && codepoint < 0x80) ||
                         (length == 3 && codepoint < 0x800) ||
                         (length == 4 && codepoint < 0x10000) ||
                         codepoint > 0x10FFFF ||
                         (codepoint >= 0xD800 && codepoint <= 0xDFFF);
    if (invalid) { ++text; return 0xFFFD; }
    text += length;
    return codepoint;
}

FontAtlas::~FontAtlas()
{
    destroy_ft();
    if (srv_) { srv_->Release(); srv_ = nullptr; }
}

void FontAtlas::destroy_ft()
{
    if (face_) { FT_Done_Face(face_);    face_ = nullptr; }
    if (ft_)   { FT_Done_FreeType(ft_);  ft_   = nullptr; }
}

bool FontAtlas::load(const char* path, float pixelHeight, ID3D11Device* device)
{
    destroy_ft();
    if (srv_) { srv_->Release(); srv_ = nullptr; }
    glyphs_.clear();
    kerning_.clear();
    fontSize_ = pixelHeight;
    if (!device || FT_Init_FreeType(&ft_) != 0) return false;
    if (FT_New_Face(ft_, path, 0, &face_) != 0) { destroy_ft(); return false; }
    const bool loaded = build(pixelHeight, device);
    if (!loaded) destroy_ft();
    return loaded;
}

bool FontAtlas::load_from_memory(const void* data, size_t size,
                                  float pixelHeight, ID3D11Device* device)
{
    destroy_ft();
    if (srv_) { srv_->Release(); srv_ = nullptr; }
    glyphs_.clear();
    kerning_.clear();
    fontSize_ = pixelHeight;
    if (!data || size == 0 || !device || FT_Init_FreeType(&ft_) != 0) return false;
    if (FT_New_Memory_Face(ft_,
            static_cast<const FT_Byte*>(data),
            static_cast<FT_Long>(size), 0, &face_) != 0)
    { destroy_ft(); return false; }
    const bool loaded = build(pixelHeight, device);
    if (!loaded) destroy_ft();
    return loaded;
}

bool FontAtlas::build(float pixelHeight, ID3D11Device* device)
{
    if (FT_Set_Pixel_Sizes(face_, 0, static_cast<FT_UInt>(pixelHeight)) != 0) return false;

    struct TempGlyph
    {
        uint32_t             codepoint;
        int                  w, h;
        int                  bearingX, bearingY;
        int                  advance;
        uint32_t             index;
        std::vector<uint8_t> bitmap;
    };

    std::vector<uint32_t> codepoints;
    for (uint32_t cp = 32; cp <= 126; ++cp)
        codepoints.push_back(cp);
    for (uint32_t cp = 160; cp <= 383; ++cp)
        codepoints.push_back(cp);
    codepoints.push_back(0xFFFD);

    std::vector<TempGlyph> temp;
    temp.reserve(codepoints.size());

    for (uint32_t cp : codepoints)
    {
        FT_UInt idx = FT_Get_Char_Index(face_, cp);
        if (FT_Load_Glyph(face_, idx, FT_LOAD_TARGET_LIGHT) != 0)           continue;
        if (FT_Render_Glyph(face_->glyph, FT_RENDER_MODE_LIGHT) != 0) continue;

        FT_GlyphSlot slot = face_->glyph;
        int w = static_cast<int>(slot->bitmap.width);
        int h = static_cast<int>(slot->bitmap.rows);

        TempGlyph tg;
        tg.codepoint = cp;
        tg.w         = w;
        tg.h         = h;
        tg.bearingX  = slot->bitmap_left;
        tg.bearingY  = slot->bitmap_top;
        tg.advance   = static_cast<int>(slot->advance.x >> 6);
        tg.index     = idx;

        tg.bitmap.resize(static_cast<size_t>(w) * static_cast<size_t>(h));
        if (w > 0 && h > 0)
        {
            int pitch = slot->bitmap.pitch;
            for (int r = 0; r < h; ++r)
            {
                const uint8_t* source = pitch >= 0
                    ? slot->bitmap.buffer + r * pitch
                    : slot->bitmap.buffer + (h - 1 - r) * -pitch;
                std::memcpy(
                    tg.bitmap.data() + r * w,
                    source,
                    static_cast<size_t>(w)
                );
            }
        }

        temp.push_back(std::move(tg));
    }

    // --- Pack glyphs into a 1024-wide atlas with 1px padding ---
    const int pad  = 1;
    const int maxW = 1024;
    int curX = pad, curY = pad, rowH = 0;

    // First pass: compute needed height.
    for (auto& tg : temp)
    {
        if (curX + tg.w + pad > maxW) { curX = pad; curY += rowH + pad; rowH = 0; }
        curX += tg.w + pad;
        rowH = std::max(rowH, tg.h);
    }
    int rawH = curY + rowH + pad;
    int powH = 1;
    while (powH < rawH) powH <<= 1;

    atlasW_ = maxW;
    atlasH_ = powH;

    std::vector<uint8_t> pixels(static_cast<size_t>(atlasW_) * static_cast<size_t>(atlasH_), 0);

    // Second pass: place glyphs.
    curX = pad; curY = pad; rowH = 0;
    for (auto& tg : temp)
    {
        if (curX + tg.w + pad > maxW) { curX = pad; curY += rowH + pad; rowH = 0; }

        for (int row = 0; row < tg.h; ++row)
        {
            if (curY + row >= atlasH_) break;
            uint8_t*       dst = pixels.data() + (curY + row) * atlasW_ + curX;
            const uint8_t* src = tg.bitmap.data() + row * tg.w;
            std::memcpy(dst, src, static_cast<size_t>(tg.w));
        }

        Glyph g;
        g.uv      = { static_cast<float>(curX) / static_cast<float>(atlasW_),
                      static_cast<float>(curY) / static_cast<float>(atlasH_),
                      static_cast<float>(tg.w) / static_cast<float>(atlasW_),
                      static_cast<float>(tg.h) / static_cast<float>(atlasH_) };
        g.bearing = { static_cast<float>(tg.bearingX),
                      static_cast<float>(tg.bearingY) };
        g.advance = static_cast<float>(tg.advance);
        g.width   = static_cast<float>(tg.w);
        g.height  = static_cast<float>(tg.h);
        g.index   = tg.index;

        glyphs_[tg.codepoint] = g;

        curX += tg.w + pad;
        rowH = std::max(rowH, tg.h);
    }

    if (FT_HAS_KERNING(face_))
    {
        for (const auto& left : temp)
            for (const auto& right : temp)
            {
                FT_Vector delta = {};
                if (FT_Get_Kerning(face_, left.index, right.index, FT_KERNING_DEFAULT, &delta) == 0 && delta.x != 0)
                    kerning_[(static_cast<uint64_t>(left.index) << 32) | right.index] = static_cast<float>(delta.x) / 64.0f;
            }
    }

    // --- Metrics ---
    // FreeType metrics are in 26.6 fixed-point, shift right by 6 for pixels.
    ascender_  = static_cast<float>(face_->size->metrics.ascender  >> 6);
    descender_ = static_cast<float>(face_->size->metrics.descender >> 6); // negative
    lineHeight_= static_cast<float>(face_->size->metrics.height    >> 6);

    // Cap height = bearing.y of the 'H' glyph (most reliable proxy).
    capHeight_ = ascender_; // fallback
    if (const Glyph* H = glyph('H'))
        capHeight_ = H->bearing.y;

    // --- Upload R8 atlas to D3D11 ---
    D3D11_TEXTURE2D_DESC texDesc = {};
    texDesc.Width            = static_cast<UINT>(atlasW_);
    texDesc.Height           = static_cast<UINT>(atlasH_);
    texDesc.MipLevels        = 1;
    texDesc.ArraySize        = 1;
    texDesc.Format           = DXGI_FORMAT_R8_UNORM;
    texDesc.SampleDesc.Count = 1;
    texDesc.Usage            = D3D11_USAGE_IMMUTABLE;
    texDesc.BindFlags        = D3D11_BIND_SHADER_RESOURCE;

    D3D11_SUBRESOURCE_DATA initData = {};
    initData.pSysMem     = pixels.data();
    initData.SysMemPitch = static_cast<UINT>(atlasW_);

    ID3D11Texture2D* tex = nullptr;
    if (FAILED(device->CreateTexture2D(&texDesc, &initData, &tex))) return false;

    D3D11_SHADER_RESOURCE_VIEW_DESC srvDesc = {};
    srvDesc.Format              = DXGI_FORMAT_R8_UNORM;
    srvDesc.ViewDimension       = D3D11_SRV_DIMENSION_TEXTURE2D;
    srvDesc.Texture2D.MipLevels = 1;

    HRESULT hr = device->CreateShaderResourceView(tex, &srvDesc, &srv_);
    tex->Release();
    if (FAILED(hr)) return false;

    destroy_ft();
    return true;
}

const Glyph* FontAtlas::glyph(uint32_t codepoint) const
{
    auto it = glyphs_.find(codepoint);
    return (it != glyphs_.end()) ? &it->second : nullptr;
}

float FontAtlas::measure_text_width(const char* text) const
{
    float w = 0;
    uint32_t previous = 0;
    while (*text)
    {
        const uint32_t codepoint = decode_utf8(text);
        const Glyph* g = glyph(codepoint);
        if (!g) g = glyph(0xFFFD);
        if (g)
        {
            w += kerning(previous, g->index);
            w += g->advance;
            previous = g->index;
        }
    }
    return w;
}

float FontAtlas::kerning(uint32_t leftIndex, uint32_t rightIndex) const
{
    if (!leftIndex || !rightIndex) return 0.0f;
    const auto it = kerning_.find((static_cast<uint64_t>(leftIndex) << 32) | rightIndex);
    return it == kerning_.end() ? 0.0f : it->second;
}

} // namespace mindless
