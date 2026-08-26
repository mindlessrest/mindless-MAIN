# Reads the xxd .raw.c output and rewrites with stable symbol names.
# Expects: -DPAYLOAD_GEN_DIR=... -DPAYLOAD_NAME=forge|lunar

set(RAW "${PAYLOAD_GEN_DIR}/payload_${PAYLOAD_NAME}.raw.c")
set(OUT "${PAYLOAD_GEN_DIR}/payload_${PAYLOAD_NAME}.c")

file(READ "${RAW}" CONTENT)
string(REGEX REPLACE "unsigned char [^\[]*\\[" "const unsigned char raven_payload_${PAYLOAD_NAME}[" CONTENT "${CONTENT}")
string(REGEX REPLACE "unsigned int [^ ]* =" "const unsigned int raven_payload_${PAYLOAD_NAME}_len =" CONTENT "${CONTENT}")
file(WRITE "${OUT}" "${CONTENT}")
