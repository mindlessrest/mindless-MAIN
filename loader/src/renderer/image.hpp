#pragma once
#include <d3d11.h>
#include <cstdint>

namespace mindless
{

// A loaded image on the GPU.
// Create via load_png(); destroy by calling release().
struct Image
{
    ID3D11ShaderResourceView* srv    = nullptr;
    int                       width  = 0;
    int                       height = 0;

    bool valid() const { return srv != nullptr; }

    void release()
    {
        if (srv) { srv->Release(); srv = nullptr; }
        width = height = 0;
    }
};

// Load a PNG/JPG/etc. from disk and upload to D3D11 as an RGBA texture.
// Returns an invalid Image on failure.
Image load_image(const char* path, ID3D11Device* device);
Image load_image_from_memory(const void* data, size_t size, ID3D11Device* device);
Image load_image_from_hicon(HICON icon, ID3D11Device* device);

} // namespace mindless
