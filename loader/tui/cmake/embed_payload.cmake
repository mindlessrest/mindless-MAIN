# Reads INPUT_FILE, writes a C file with the binary data as an array
file(READ "${INPUT_FILE}" content HEX)
string(LENGTH "${content}" hex_len)
math(EXPR byte_count "${hex_len} / 2")

# Convert hex pairs to 0xNN format
string(REGEX REPLACE "([0-9a-f][0-9a-f])" "0x\\1," c_array "${content}")
# Wrap lines
string(REGEX REPLACE "([^\n]{120})" "\\1\n" c_array "${c_array}")

file(WRITE "${OUTPUT_FILE}"
"#include <stddef.h>\n"
"const unsigned char raven_payload[] = {\n${c_array}\n};\n"
"const size_t raven_payload_len = ${byte_count};\n"
)
