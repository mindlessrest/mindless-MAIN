#pragma once
#include "core/color.hpp"
#include "core/math.hpp"
#include "renderer/font_atlas.hpp"
#include "renderer/image.hpp"
#include <d3d11.h>
#include <dxgi.h>
#include <cstdint>
#include <vector>
#include <string>

namespace mindless
{

// A single vertex pushed into the draw buffer.
struct Vertex
{
    float x, y;       // screen position
    float u, v;       // texture coordinates (0,0 if untextured)
    float r, g, b, a; // color
    float mode;       // 0=solid, 1=textured (glyph alpha)
};

// The renderer owns all D3D11 state.
// Each frame: begin_frame() → draw calls → end_frame() → present().
class Renderer
{
public:
    Renderer() = default;
    ~Renderer();

    Renderer(const Renderer&) = delete;
    Renderer& operator=(const Renderer&) = delete;

    bool init(HWND hwnd, int width, int height);
    void resize(int width, int height);
    void begin_frame();
    void end_frame();
    void present(bool vsync = true);

    // Primitives — all coordinates in screen pixels (top-left origin)
    void draw_rect(Rect r, Color c);
    void draw_rect_border(Rect r, Color c, float thickness = 1.0f);
    void draw_rounded_rect(Rect r, Color c, float radius);
    void draw_rounded_rect_border(Rect r, Color c, float radius, float thickness = 1.0f);
    void draw_text(const char* text, Vec2 pos, Color c, const FontAtlas& atlas);
    void draw_image(const Image& img, Rect dest, float alpha = 1.0f);

    // Clipping — push/pop a scissor rect
    void push_clip(Rect r);
    void pop_clip();

    int width()  const { return width_; }
    int height() const { return height_; }

    ID3D11Device* device() const { return device_; }

private:
    // D3D11 core
    ID3D11Device*           device_       = nullptr;
    ID3D11DeviceContext*    context_      = nullptr;
    IDXGISwapChain*         swapChain_    = nullptr;
    ID3D11RenderTargetView* rtv_          = nullptr;

    // Pipeline state
    ID3D11Buffer*           vertexBuf_    = nullptr;
    ID3D11Buffer*           constantBuf_  = nullptr;
    ID3D11VertexShader*     vs_           = nullptr;
    ID3D11PixelShader*      ps_           = nullptr;
    ID3D11InputLayout*      inputLayout_  = nullptr;
    ID3D11BlendState*       blendState_   = nullptr;
    ID3D11RasterizerState*  rastState_    = nullptr;
    ID3D11SamplerState*     sampler_      = nullptr;
    ID3D11SamplerState*     fontSampler_  = nullptr;

    // Draw buffer
    std::vector<Vertex>     vertices_;

    // Clip stack
    std::vector<Rect>       clipStack_;
    Rect                    currentClip_ = {};
    bool                    clipping_    = false;

    // Current font texture being drawn — flushes batch on switch
    ID3D11ShaderResourceView* currentSrv_ = nullptr;

    int width_  = 0;
    int height_ = 0;

    static constexpr int MaxVertices = 65536;

    bool create_device_and_swap_chain(HWND hwnd);
    bool create_render_target();
    bool create_pipeline();
    void flush();
    void apply_clip();
    void ensure_srv(ID3D11ShaderResourceView* srv);

    void push_quad(float x0, float y0, float x1, float y1,
                   float u0, float v0, float u1, float v1,
                   Color c, float mode);

    // Rounded rect helpers
    void push_rounded_rect_filled(Rect r, Color c, float radius);
    void push_rounded_rect_border(Rect r, Color c, float radius, float thickness);
};

} // namespace mindless

// Forward declaration so we can include this header without pulling in draw_list.hpp everywhere.
namespace mindless::ui { class DrawList; }

namespace mindless
{
    // Execute a complete DrawList through a Renderer.
    void execute_draw_list(Renderer& r, const ui::DrawList& list);
}
