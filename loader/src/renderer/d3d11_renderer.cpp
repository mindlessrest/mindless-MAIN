#include "d3d11_renderer.hpp"
#include "ui/draw_list.hpp"
#include <d3dcompiler.h>
#include <dxgi1_2.h>
#include <dcomp.h>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <cassert>

static const char* g_shaderSrc = R"HLSL(

cbuffer Constants : register(b0)
{
    float2 screenSize;
    float2 _pad;
};

struct VS_Input
{
    float2 pos   : POSITION;
    float2 uv    : TEXCOORD0;
    float4 color : COLOR;
    float  mode  : MODE;
    float4 shape : SHAPE;
    float2 param : PARAM;
};

struct VS_Output
{
    float4 pos   : SV_POSITION;
    float2 uv    : TEXCOORD0;
    float4 color : COLOR;
    float  mode  : MODE;
    float4 shape : SHAPE;
    float2 param : PARAM;
    float2 wpos  : TEXCOORD1;
};

VS_Output vs_main(VS_Input input)
{
    VS_Output o;
    float2 ndc = (input.pos / screenSize) * 2.0 - 1.0;
    ndc.y = -ndc.y;
    o.pos   = float4(ndc, 0.0, 1.0);
    o.uv    = input.uv;
    o.color = input.color;
    o.mode  = input.mode;
    o.shape = input.shape;
    o.param = input.param;
    o.wpos  = input.pos;
    return o;
}

Texture2D    gTexture : register(t0);
SamplerState gSampler : register(s0);
SamplerState gFontSampler : register(s1);

float sd_round_box(float2 p, float2 b, float r)
{
    r = min(r, min(b.x, b.y));
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float4 ps_main(VS_Output input) : SV_TARGET
{
    if (input.mode > 4.5)
    {
        float d = sd_round_box(input.wpos - input.shape.xy, input.shape.zw, input.param.x);
        float t = saturate(1.0 - max(d, 0.0) / max(input.param.y, 0.001));
        float a = t * t;
        return float4(input.color.rgb, input.color.a * a);
    }
    if (input.mode > 3.5)
    {
        float d = sd_round_box(input.wpos - input.shape.xy, input.shape.zw, input.param.x);
        float a = saturate(0.5 - d) * saturate(0.5 + d + input.param.y);
        return float4(input.color.rgb, input.color.a * a);
    }
    if (input.mode > 2.5)
    {
        float d = sd_round_box(input.wpos - input.shape.xy, input.shape.zw, input.param.x);
        return float4(input.color.rgb, input.color.a * saturate(0.5 - d));
    }
    if (input.mode > 1.5)
    {
        float4 texel = gTexture.Sample(gSampler, input.uv);
        float3 color = texel.a > 0.0001 ? texel.rgb / texel.a : 0.0;
        return float4(color, texel.a * input.color.a);
    }
    if (input.mode > 0.5)
    {
        float alpha = gTexture.Sample(gFontSampler, input.uv).r;
        return float4(input.color.rgb, input.color.a * alpha);
    }
    return input.color;
}

)HLSL";

namespace mindless
{

Renderer::~Renderer()
{
    if (compVisual_)   compVisual_->Release();
    if (compTarget_)   compTarget_->Release();
    if (compDevice_)   compDevice_->Release();
    if (fontSampler_)  fontSampler_->Release();
    if (sampler_)      sampler_->Release();
    if (rastState_)    rastState_->Release();
    if (blendState_)   blendState_->Release();
    if (inputLayout_)  inputLayout_->Release();
    if (ps_)           ps_->Release();
    if (vs_)           vs_->Release();
    if (constantBuf_)  constantBuf_->Release();
    if (vertexBuf_)    vertexBuf_->Release();
    if (rtv_)          rtv_->Release();
    if (swapChain_)    swapChain_->Release();
    if (context_)      context_->Release();
    if (device_)       device_->Release();
}

bool Renderer::init(HWND hwnd, int width, int height)
{
    width_  = width;
    height_ = height;
    vertices_.reserve(MaxVertices);

    if (!create_device_and_swap_chain(hwnd)) return false;
    if (!create_render_target())             return false;
    if (!create_pipeline())                  return false;
    return true;
}

bool Renderer::create_device_and_swap_chain(HWND hwnd)
{
    DXGI_SWAP_CHAIN_DESC1 sd1 = {};
    sd1.Width            = static_cast<UINT>(width_);
    sd1.Height           = static_cast<UINT>(height_);
    sd1.Format           = DXGI_FORMAT_B8G8R8A8_UNORM;
    sd1.SampleDesc.Count = 1;
    sd1.BufferUsage      = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    sd1.BufferCount      = 2;
    sd1.SwapEffect       = DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL;
    sd1.AlphaMode        = DXGI_ALPHA_MODE_PREMULTIPLIED;
    sd1.Flags            = 0;

    UINT flags = 0;
#ifdef _DEBUG
    flags |= D3D11_CREATE_DEVICE_DEBUG;
#endif

    D3D_FEATURE_LEVEL level;
    D3D_FEATURE_LEVEL levels[] = { D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1 };

    HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
                                    flags, levels, 2, D3D11_SDK_VERSION,
                                    &device_, &level, &context_);
    if (FAILED(hr)) return false;

    IDXGIDevice1*  dxgiDevice  = nullptr;
    IDXGIAdapter*  dxgiAdapter = nullptr;
    IDXGIFactory2* dxgiFactory = nullptr;

    if (FAILED(device_->QueryInterface(IID_PPV_ARGS(&dxgiDevice))) ||
        FAILED(dxgiDevice->GetAdapter(&dxgiAdapter)) ||
        FAILED(dxgiAdapter->GetParent(IID_PPV_ARGS(&dxgiFactory))))
    {
        if (dxgiFactory) dxgiFactory->Release();
        if (dxgiAdapter) dxgiAdapter->Release();
        if (dxgiDevice) dxgiDevice->Release();
        return false;
    }

    // A swap chain bound straight to the HWND cannot carry per-pixel alpha. Going through
    // DirectComposition instead is what lets the window be genuinely transparent outside the
    // panel, so the rounded corners are actually round and the glow has somewhere to fall off
    // into rather than being painted onto an opaque black square.
    hr = dxgiFactory->CreateSwapChainForComposition(device_, &sd1, nullptr, &swapChain_);

    dxgiFactory->Release();
    dxgiAdapter->Release();
    dxgiDevice->Release();

    if (FAILED(hr)) return false;

    IDCompositionDevice* comp = nullptr;
    IDCompositionTarget* target = nullptr;
    IDCompositionVisual* visual = nullptr;

    IDXGIDevice* dxgi = nullptr;
    if (FAILED(device_->QueryInterface(IID_PPV_ARGS(&dxgi)))) return false;
    hr = DCompositionCreateDevice(dxgi, IID_PPV_ARGS(&comp));
    if (dxgi) dxgi->Release();
    if (FAILED(hr)) return false;

    if (FAILED(comp->CreateTargetForHwnd(hwnd, TRUE, &target)) ||
        FAILED(comp->CreateVisual(&visual)))
    {
        if (target) target->Release();
        comp->Release();
        return false;
    }

    if (FAILED(visual->SetContent(swapChain_)) ||
        FAILED(target->SetRoot(visual)) ||
        FAILED(comp->Commit()))
    {
        visual->Release();
        target->Release();
        comp->Release();
        return false;
    }

    compDevice_ = comp;
    compTarget_ = target;
    compVisual_ = visual;
    return true;
}

bool Renderer::create_render_target()
{
    ID3D11Texture2D* backbuf = nullptr;
    if (!swapChain_ || !device_ || FAILED(swapChain_->GetBuffer(0, IID_PPV_ARGS(&backbuf))))
        return false;
    HRESULT hr = device_->CreateRenderTargetView(backbuf, nullptr, &rtv_);
    backbuf->Release();
    return SUCCEEDED(hr);
}

bool Renderer::create_pipeline()
{
    ID3DBlob* vsBlob = nullptr, *psBlob = nullptr, *errBlob = nullptr;

    HRESULT hr = D3DCompile(g_shaderSrc, strlen(g_shaderSrc), nullptr, nullptr, nullptr,
                             "vs_main", "vs_4_0", 0, 0, &vsBlob, &errBlob);
    if (FAILED(hr)) {
        if (errBlob) errBlob->Release();
        return false;
    }
    if (errBlob) { errBlob->Release(); errBlob = nullptr; }

    hr = D3DCompile(g_shaderSrc, strlen(g_shaderSrc), nullptr, nullptr, nullptr,
                    "ps_main", "ps_4_0", 0, 0, &psBlob, &errBlob);
    if (FAILED(hr)) {
        vsBlob->Release();
        if (errBlob) errBlob->Release();
        return false;
    }
    if (errBlob) { errBlob->Release(); errBlob = nullptr; }

    if (FAILED(device_->CreateVertexShader(vsBlob->GetBufferPointer(), vsBlob->GetBufferSize(), nullptr, &vs_)) ||
        FAILED(device_->CreatePixelShader(psBlob->GetBufferPointer(), psBlob->GetBufferSize(), nullptr, &ps_)))
    {
        vsBlob->Release();
        psBlob->Release();
        return false;
    }

    D3D11_INPUT_ELEMENT_DESC layout[] = {
        { "POSITION", 0, DXGI_FORMAT_R32G32_FLOAT,       0, offsetof(Vertex, x),    D3D11_INPUT_PER_VERTEX_DATA, 0 },
        { "TEXCOORD", 0, DXGI_FORMAT_R32G32_FLOAT,       0, offsetof(Vertex, u),    D3D11_INPUT_PER_VERTEX_DATA, 0 },
        { "COLOR",    0, DXGI_FORMAT_R32G32B32A32_FLOAT, 0, offsetof(Vertex, r),    D3D11_INPUT_PER_VERTEX_DATA, 0 },
        { "MODE",     0, DXGI_FORMAT_R32_FLOAT,           0, offsetof(Vertex, mode), D3D11_INPUT_PER_VERTEX_DATA, 0 },
        { "SHAPE",    0, DXGI_FORMAT_R32G32B32A32_FLOAT, 0, offsetof(Vertex, cx),   D3D11_INPUT_PER_VERTEX_DATA, 0 },
        { "PARAM",    0, DXGI_FORMAT_R32G32_FLOAT,       0, offsetof(Vertex, radius), D3D11_INPUT_PER_VERTEX_DATA, 0 },
    };
    hr = device_->CreateInputLayout(layout, 6, vsBlob->GetBufferPointer(), vsBlob->GetBufferSize(), &inputLayout_);

    vsBlob->Release();
    psBlob->Release();
    if (FAILED(hr)) return false;

    D3D11_BUFFER_DESC vbd = {};
    vbd.Usage          = D3D11_USAGE_DYNAMIC;
    vbd.ByteWidth      = sizeof(Vertex) * MaxVertices;
    vbd.BindFlags      = D3D11_BIND_VERTEX_BUFFER;
    vbd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    if (FAILED(device_->CreateBuffer(&vbd, nullptr, &vertexBuf_))) return false;

    D3D11_BUFFER_DESC cbd = {};
    cbd.Usage          = D3D11_USAGE_DYNAMIC;
    cbd.ByteWidth      = 16;
    cbd.BindFlags      = D3D11_BIND_CONSTANT_BUFFER;
    cbd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    if (FAILED(device_->CreateBuffer(&cbd, nullptr, &constantBuf_))) return false;

    // Standard straight-alpha blend.
    D3D11_BLEND_DESC bd = {};
    bd.RenderTarget[0].BlendEnable           = TRUE;
    bd.RenderTarget[0].SrcBlend              = D3D11_BLEND_SRC_ALPHA;
    bd.RenderTarget[0].DestBlend             = D3D11_BLEND_INV_SRC_ALPHA;
    bd.RenderTarget[0].BlendOp               = D3D11_BLEND_OP_ADD;
    bd.RenderTarget[0].SrcBlendAlpha         = D3D11_BLEND_ONE;
    bd.RenderTarget[0].DestBlendAlpha        = D3D11_BLEND_INV_SRC_ALPHA;
    bd.RenderTarget[0].BlendOpAlpha          = D3D11_BLEND_OP_ADD;
    bd.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
    if (FAILED(device_->CreateBlendState(&bd, &blendState_))) return false;

    D3D11_RASTERIZER_DESC rd = {};
    rd.FillMode        = D3D11_FILL_SOLID;
    rd.CullMode        = D3D11_CULL_NONE;
    rd.ScissorEnable   = TRUE;
    rd.DepthClipEnable = TRUE;
    if (FAILED(device_->CreateRasterizerState(&rd, &rastState_))) return false;

    D3D11_SAMPLER_DESC smp = {};
    smp.Filter         = D3D11_FILTER_MIN_MAG_MIP_LINEAR;
    smp.AddressU       = D3D11_TEXTURE_ADDRESS_CLAMP;
    smp.AddressV       = D3D11_TEXTURE_ADDRESS_CLAMP;
    smp.AddressW       = D3D11_TEXTURE_ADDRESS_CLAMP;
    smp.ComparisonFunc = D3D11_COMPARISON_NEVER;
    smp.MaxLOD         = D3D11_FLOAT32_MAX;
    if (FAILED(device_->CreateSamplerState(&smp, &sampler_))) return false;

    smp.Filter = D3D11_FILTER_MIN_MAG_MIP_POINT;
    if (FAILED(device_->CreateSamplerState(&smp, &fontSampler_))) return false;

    return true;
}

void Renderer::resize(int width, int height)
{
    if (width <= 0 || height <= 0 || !swapChain_ || !context_) return;
    if (width == width_ && height == height_) return;
    flush();
    context_->OMSetRenderTargets(0, nullptr, nullptr);
    if (rtv_) { rtv_->Release(); rtv_ = nullptr; }
    const HRESULT hr = swapChain_->ResizeBuffers(0, static_cast<UINT>(width), static_cast<UINT>(height),
                                                  DXGI_FORMAT_UNKNOWN, 0);
    if (FAILED(hr))
    {
        create_render_target();
        return;
    }
    if (create_render_target())
    {
        width_ = width;
        height_ = height;
    }
}

void Renderer::begin_frame()
{
    if (!context_ || !rtv_) return;
    D3D11_MAPPED_SUBRESOURCE mapped;
    if (SUCCEEDED(context_->Map(constantBuf_, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped)))
    {
        float data[4] = { (float)width_, (float)height_, 0, 0 };
        memcpy(mapped.pData, data, sizeof(data));
        context_->Unmap(constantBuf_, 0);
    }

    // Transparent: the margin around the panel has to stay clear for the glow to fade into.
    float clearColor[4] = { 0.0f, 0.0f, 0.0f, 0.0f };
    context_->ClearRenderTargetView(rtv_, clearColor);

    D3D11_VIEWPORT vp = { 0, 0, (float)width_, (float)height_, 0, 1 };
    context_->RSSetViewports(1, &vp);
    context_->OMSetRenderTargets(1, &rtv_, nullptr);
    context_->VSSetShader(vs_, nullptr, 0);
    context_->PSSetShader(ps_, nullptr, 0);
    context_->PSSetSamplers(0, 1, &sampler_);
    context_->PSSetSamplers(1, 1, &fontSampler_);
    context_->VSSetConstantBuffers(0, 1, &constantBuf_);
    context_->IASetInputLayout(inputLayout_);
    context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    context_->RSSetState(rastState_);

    float blendFactor[4] = {};
    context_->OMSetBlendState(blendState_, blendFactor, 0xFFFFFFFF);

    UINT stride = sizeof(Vertex), offset = 0;
    context_->IASetVertexBuffers(0, 1, &vertexBuf_, &stride, &offset);

    D3D11_RECT scissor = { 0, 0, width_, height_ };
    context_->RSSetScissorRects(1, &scissor);

    currentSrv_ = nullptr;
    vertices_.clear();
    clipStack_.clear();
    clipping_ = false;
}

void Renderer::end_frame()
{
    flush();
}

void Renderer::present(bool vsync)
{
    swapChain_->Present(vsync ? 1 : 0, 0);
}

void Renderer::push_clip(Rect r)
{
    flush();
    if (!clipStack_.empty())
    {
        const Rect& parent = clipStack_.back();
        const float left = std::max(r.x, parent.x);
        const float top = std::max(r.y, parent.y);
        const float right = std::min(r.right(), parent.right());
        const float bottom = std::min(r.bottom(), parent.bottom());
        r = { left, top, std::max(0.0f, right - left), std::max(0.0f, bottom - top) };
    }
    clipStack_.push_back(r);
    currentClip_ = r;
    clipping_ = true;
    apply_clip();
}

void Renderer::pop_clip()
{
    if (clipStack_.empty()) return;
    flush();
    clipStack_.pop_back();

    if (clipStack_.empty())
    {
        clipping_ = false;
        D3D11_RECT scissor = { 0, 0, width_, height_ };
        context_->RSSetScissorRects(1, &scissor);
    }
    else
    {
        currentClip_ = clipStack_.back();
        apply_clip();
    }
}

void Renderer::apply_clip()
{
    D3D11_RECT scissor = {
        static_cast<LONG>(std::floor(std::clamp(currentClip_.x, 0.0f, static_cast<float>(width_)))),
        static_cast<LONG>(std::floor(std::clamp(currentClip_.y, 0.0f, static_cast<float>(height_)))),
        static_cast<LONG>(std::ceil(std::clamp(currentClip_.right(), 0.0f, static_cast<float>(width_)))),
        static_cast<LONG>(std::ceil(std::clamp(currentClip_.bottom(), 0.0f, static_cast<float>(height_))))
    };
    context_->RSSetScissorRects(1, &scissor);
}

void Renderer::ensure_srv(ID3D11ShaderResourceView* srv)
{
    if (srv == currentSrv_) return;
    flush();
    currentSrv_ = srv;
    context_->PSSetShaderResources(0, 1, &srv);
}

void Renderer::flush()
{
    if (vertices_.empty()) return;

    D3D11_MAPPED_SUBRESOURCE mapped;
    if (FAILED(context_->Map(vertexBuf_, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped)))
    {
        vertices_.clear();
        return;
    }
    memcpy(mapped.pData, vertices_.data(), vertices_.size() * sizeof(Vertex));
    context_->Unmap(vertexBuf_, 0);
    context_->Draw(static_cast<UINT>(vertices_.size()), 0);
    vertices_.clear();
}

void Renderer::push_quad(float x0, float y0, float x1, float y1,
                          float u0, float v0, float u1, float v1,
                          Color c, float mode)
{
    if (static_cast<int>(vertices_.size()) + 6 > MaxVertices)
        flush();

    vertices_.push_back({ x0, y0, u0, v0, c.r, c.g, c.b, c.a, mode, 0, 0, 0, 0, 0, 0 });
    vertices_.push_back({ x1, y0, u1, v0, c.r, c.g, c.b, c.a, mode, 0, 0, 0, 0, 0, 0 });
    vertices_.push_back({ x1, y1, u1, v1, c.r, c.g, c.b, c.a, mode, 0, 0, 0, 0, 0, 0 });
    vertices_.push_back({ x0, y0, u0, v0, c.r, c.g, c.b, c.a, mode, 0, 0, 0, 0, 0, 0 });
    vertices_.push_back({ x1, y1, u1, v1, c.r, c.g, c.b, c.a, mode, 0, 0, 0, 0, 0, 0 });
    vertices_.push_back({ x0, y1, u0, v1, c.r, c.g, c.b, c.a, mode, 0, 0, 0, 0, 0, 0 });
}

void Renderer::push_sdf_quad(Rect shape, Rect cover, Color c,
                             float radius, float mode, float param)
{
    push_sdf_quad_gradient(shape, cover, c, c, radius, mode, param);
}

void Renderer::push_sdf_quad_gradient(Rect shape, Rect cover, Color left, Color right,
                                      float radius, float mode, float param)
{
    if (shape.w <= 0.0f || shape.h <= 0.0f) return;
    if (left.a <= 0.0f && right.a <= 0.0f)  return;
    if (cover.w <= 0.0f || cover.h <= 0.0f) return;

    ensure_srv(nullptr);
    if (static_cast<int>(vertices_.size()) + 6 > MaxVertices) flush();

    float cx = shape.x + shape.w * 0.5f;
    float cy = shape.y + shape.h * 0.5f;
    float hx = shape.w * 0.5f;
    float hy = shape.h * 0.5f;
    radius = std::min(radius, std::min(hx, hy));

    float x0 = cover.x, y0 = cover.y, x1 = cover.right(), y1 = cover.bottom();

    auto at = [&](float x, float y, Color c) {
        return Vertex{ x, y, 0, 0, c.r, c.g, c.b, c.a, mode, cx, cy, hx, hy, radius, param };
    };

    vertices_.push_back(at(x0, y0, left));
    vertices_.push_back(at(x1, y0, right));
    vertices_.push_back(at(x1, y1, right));
    vertices_.push_back(at(x0, y0, left));
    vertices_.push_back(at(x1, y1, right));
    vertices_.push_back(at(x0, y1, left));
}

void Renderer::draw_rect(Rect r, Color c)
{
    ensure_srv(nullptr);
    push_quad(r.x, r.y, r.right(), r.bottom(), 0, 0, 0, 0, c, 0.0f);
}

void Renderer::draw_rect_border(Rect r, Color c, float t)
{
    draw_rect({ r.x,           r.y,            r.w, t }, c);
    draw_rect({ r.x,           r.bottom() - t, r.w, t }, c);
    draw_rect({ r.x,           r.y + t,        t,   r.h - t * 2 }, c);
    draw_rect({ r.right() - t, r.y + t,        t,   r.h - t * 2 }, c);
}

void Renderer::draw_rounded_rect(Rect r, Color c, float radius)
{
    push_sdf_quad(r, r.inset(-2.0f), c, radius, 3.0f, 0.0f);
}

void Renderer::draw_rounded_rect_gradient(Rect shape, Rect cover, Color left, Color right,
                                          float radius)
{
    push_sdf_quad_gradient(shape, cover, left, right, radius, 3.0f, 0.0f);
}

void Renderer::draw_rounded_rect_border(Rect r, Color c, float radius, float thickness)
{
    push_sdf_quad(r, r.inset(-2.0f), c, radius, 4.0f, thickness);
}

void Renderer::draw_rounded_rect_glow(Rect r, Color c, float radius, float spread)
{
    push_sdf_quad(r, r.inset(-spread - 2.0f), c, radius, 5.0f, spread);
}

void Renderer::draw_text(const char* text, Vec2 pos, Color c, const FontAtlas& atlas)
{
    ensure_srv(atlas.srv());

    // pos.y is the top of the text line (above ascender).
    // The baseline sits at pos.y + ascender.
    float x        = std::round(pos.x);
    float baseline = std::round(pos.y + atlas.ascender());

    uint32_t previous = 0;
    while (*text)
    {
        const uint32_t cp = decode_utf8(text);
        const Glyph* g = atlas.glyph(cp);
        if (!g) g = atlas.glyph(0xFFFD);
        if (!g) continue;

        x += atlas.kerning(previous, g->index);

        // bearing.x: pixels to the right of the cursor to the glyph left edge.
        // bearing.y: pixels above the baseline to the glyph top edge.
        float gx = x + g->bearing.x;
        float gy = baseline - g->bearing.y;

        push_quad(
            gx,            gy,
            gx + g->width, gy + g->height,
            g->uv.x,             g->uv.y,
            g->uv.x + g->uv.w,  g->uv.y + g->uv.h,
            c, 1.0f);

        x += g->advance;
        previous = g->index;
    }
}

void Renderer::draw_image(const Image& img, Rect dest, float alpha)
{
    if (!img.valid()) return;
    ensure_srv(img.srv);
    Color c = { 1.0f, 1.0f, 1.0f, alpha };
    // mode=1 in the shader reads .r channel for glyphs, but for RGBA images
    // we need a different mode. We reuse mode=2 — add a branch in the shader.
    push_quad(dest.x, dest.y, dest.right(), dest.bottom(),
              0, 0, 1, 1, c, 2.0f);
}

} // namespace mindless

// --- DrawList execution ---

#include "ui/draw_list.hpp"

namespace mindless
{

void execute_draw_list(Renderer& r, const ui::DrawList& list)
{
    for (const auto& cmd : list.cmds())
    {
        switch (cmd.type)
        {
        case ui::DrawCmdType::FillRect:
            r.draw_rect(cmd.rect, cmd.color);
            break;
        case ui::DrawCmdType::FillRoundedRect:
            r.draw_rounded_rect(cmd.rect, cmd.color, cmd.radius);
            break;
        case ui::DrawCmdType::StrokeRoundedRect:
            r.draw_rounded_rect_border(cmd.rect, cmd.color, cmd.radius, cmd.thick);
            break;
        case ui::DrawCmdType::GlowRoundedRect:
            r.draw_rounded_rect_glow(cmd.rect, cmd.color, cmd.radius, cmd.thick);
            break;
        case ui::DrawCmdType::GradientRoundedRect:
            r.draw_rounded_rect_gradient(cmd.rect, cmd.rect2, cmd.color, cmd.color2, cmd.radius);
            break;
        case ui::DrawCmdType::Text:
            if (cmd.atlas)
                r.draw_text(cmd.text.c_str(), { cmd.rect.x, cmd.rect.y }, cmd.color, *cmd.atlas);
            break;
        case ui::DrawCmdType::Image:
            if (cmd.image)
                r.draw_image(*cmd.image, cmd.rect, cmd.imageAlpha);
            break;
        case ui::DrawCmdType::PushClip:
            r.push_clip(cmd.rect);
            break;
        case ui::DrawCmdType::PopClip:
            r.pop_clip();
            break;
        }
    }
}

} // namespace mindless
