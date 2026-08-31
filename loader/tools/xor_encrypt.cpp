#include <cstdint>
#include <cstdio>
#include <cstdlib>

static constexpr uint8_t XOR_KEY[] = {
    0x4D, 0x1A, 0xE7, 0x93, 0x5F, 0xC2, 0x38, 0xAB,
    0x6D, 0xF0, 0x14, 0x87, 0x2E, 0xB5, 0x71, 0xD9,
    0x03, 0x8C, 0x46, 0xFA, 0x65, 0x29, 0xDE, 0xB0,
    0x57, 0xC8, 0x1F, 0xA3, 0x74, 0xE1, 0x9B, 0x42,
};
static constexpr size_t XOR_KEY_LEN = sizeof(XOR_KEY);

int main(int argc, char** argv)
{
    if (argc != 3)
    {
        fprintf(stderr, "Usage: %s <input> <output>\n", argv[0]);
        return 1;
    }

    FILE* in = fopen(argv[1], "rb");
    if (!in) { perror("open input"); return 1; }

    fseek(in, 0, SEEK_END);
    long size = ftell(in);
    fseek(in, 0, SEEK_SET);

    auto* buf = (uint8_t*)malloc(size);
    if (!buf) { fclose(in); return 1; }
    fread(buf, 1, size, in);
    fclose(in);

    for (long i = 0; i < size; ++i)
        buf[i] ^= XOR_KEY[i % XOR_KEY_LEN];

    FILE* out = fopen(argv[2], "wb");
    if (!out) { free(buf); perror("open output"); return 1; }
    fwrite(buf, 1, size, out);
    fclose(out);
    free(buf);

    return 0;
}
