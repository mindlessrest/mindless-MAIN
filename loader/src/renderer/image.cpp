#define STB_IMAGE_IMPLEMENTATION

// stb_image is a third-party header — suppress all its warnings.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wreserved-identifier"
#pragma clang diagnostic ignored "-Wreserved-macro-identifier"
#pragma clang diagnostic ignored "-Wzero-as-null-pointer-constant"
#pragma clang diagnostic ignored "-Wcast-qual"
#pragma clang diagnostic ignored "-Wunused-function"
#pragma clang diagnostic ignored "-Wdisabled-macro-expansion"
#pragma clang diagnostic ignored "-Wcast-align"
#pragma clang diagnostic ignored "-Wimplicit-fallthrough"
#pragma clang diagnostic ignored "-Wcomma"
#pragma clang diagnostic ignored "-Wmissing-prototypes"
#include "stb_image.h"
#pragma clang diagnostic pop

#include "image.hpp"
#include <Windows.h>
#include <vector>

namespace mindless
{

static Image upload_pixels(unsigned char* pixels, int w, int h, ID3D11Device* device)
{
    Image img;
    img.width  = w;
    img.height = h;

    D3D11_TEXTURE2D_DESC desc = {};
    desc.Width            = static_cast<UINT>(w);
    desc.Height           = static_cast<UINT>(h);
    desc.MipLevels        = 1;
    desc.ArraySize        = 1;
    desc.Format           = DXGI_FORMAT_R8G8B8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.Usage            = D3D11_USAGE_IMMUTABLE;
    desc.BindFlags        = D3D11_BIND_SHADER_RESOURCE;

    D3D11_SUBRESOURCE_DATA sd = {};
    sd.pSysMem     = pixels;
    sd.SysMemPitch = static_cast<UINT>(w) * 4;

    ID3D11Texture2D* tex = nullptr;
    if (FAILED(device->CreateTexture2D(&desc, &sd, &tex))) return img;

    D3D11_SHADER_RESOURCE_VIEW_DESC srvDesc = {};
    srvDesc.Format              = DXGI_FORMAT_R8G8B8A8_UNORM;
    srvDesc.ViewDimension       = D3D11_SRV_DIMENSION_TEXTURE2D;
    srvDesc.Texture2D.MipLevels = 1;

    HRESULT hr = device->CreateShaderResourceView(tex, &srvDesc, &img.srv);
    tex->Release();
    if (FAILED(hr)) img.srv = nullptr;
    return img;
}

Image load_image(const char* path, ID3D11Device* device)
{
    Image img;
    int channels = 0;
    unsigned char* pixels = stbi_load(path, &img.width, &img.height, &channels, 4);
    if (!pixels) return img;
    img = upload_pixels(pixels, img.width, img.height, device);
    stbi_image_free(pixels);
    return img;
}

Image load_image_from_memory(const void* data, size_t size, ID3D11Device* device)
{
    Image img;
    int channels = 0;
    unsigned char* pixels = stbi_load_from_memory(
        static_cast<const stbi_uc*>(data),
        static_cast<int>(size),
        &img.width, &img.height, &channels, 4);
    if (!pixels) return img;
    img = upload_pixels(pixels, img.width, img.height, device);
    stbi_image_free(pixels);
    return img;
}

Image load_image_from_hicon(HICON icon, ID3D11Device* device)
{
    Image img;
    if (!icon || !device) return img;

    ICONINFO ii = {};
    if (!GetIconInfo(icon, &ii)) return img;

    BITMAP bm = {};
    GetObject(ii.hbmColor ? ii.hbmColor : ii.hbmMask, sizeof(bm), &bm);

    int w = bm.bmWidth;
    int h = ii.hbmColor ? bm.bmHeight : bm.bmHeight / 2;
    if (w <= 0 || h <= 0)
    {
        if (ii.hbmColor) DeleteObject(ii.hbmColor);
        if (ii.hbmMask)  DeleteObject(ii.hbmMask);
        return img;
    }

    HDC screenDC = GetDC(nullptr);
    HDC memDC    = CreateCompatibleDC(screenDC);

    BITMAPINFO bi = {};
    bi.bmiHeader.biSize        = sizeof(BITMAPINFOHEADER);
    bi.bmiHeader.biWidth       = w;
    bi.bmiHeader.biHeight      = -h;
    bi.bmiHeader.biPlanes      = 1;
    bi.bmiHeader.biBitCount    = 32;
    bi.bmiHeader.biCompression = BI_RGB;

    void* bits = nullptr;
    HBITMAP dib = CreateDIBSection(screenDC, &bi, DIB_RGB_COLORS, &bits, nullptr, 0);
    if (!dib || !bits)
    {
        DeleteDC(memDC);
        ReleaseDC(nullptr, screenDC);
        if (ii.hbmColor) DeleteObject(ii.hbmColor);
        if (ii.hbmMask)  DeleteObject(ii.hbmMask);
        return img;
    }

    HBITMAP old = static_cast<HBITMAP>(SelectObject(memDC, dib));
    DrawIconEx(memDC, 0, 0, icon, w, h, 0, nullptr, DI_NORMAL);
    SelectObject(memDC, old);

    auto* src = static_cast<uint8_t*>(bits);
    std::vector<uint8_t> rgba(static_cast<size_t>(w * h * 4));
    for (int i = 0; i < w * h; ++i)
    {
        rgba[i * 4 + 0] = src[i * 4 + 2]; // R
        rgba[i * 4 + 1] = src[i * 4 + 1]; // G
        rgba[i * 4 + 2] = src[i * 4 + 0]; // B
        rgba[i * 4 + 3] = src[i * 4 + 3]; // A
    }

    img = upload_pixels(rgba.data(), w, h, device);

    DeleteObject(dib);
    DeleteDC(memDC);
    ReleaseDC(nullptr, screenDC);
    if (ii.hbmColor) DeleteObject(ii.hbmColor);
    if (ii.hbmMask)  DeleteObject(ii.hbmMask);
    return img;
}

} // namespace mindless
