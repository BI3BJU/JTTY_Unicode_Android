// jtty_ext.h — JTTY Unicode extension frames (34-bit layer).
//
// Extension frames reuse the JTTY physical layer unchanged: the 34-bit payload
// keeps bit 34 as EOM, sets the otherwise reserved bit 33 to 1, and carries
// four bytes of UTF-8 in bits 1..32 (most significant bit first). A message is
// 1..16 frames; the final frame carries three content bytes plus one CRC-8/ATM
// byte over every preceding byte, including 0x20 padding.
//
// This header is standalone so the codec stays plain C11 with no Android
// dependency, matching jtty_lib/jtty.h.

#ifndef JTTYAF_JTTY_EXT_H
#define JTTYAF_JTTY_EXT_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define JTTY_EXT_BYTES_PER_FRAME 4
#define JTTY_EXT_MAX_FRAMES      16
#define JTTY_EXT_MAX_BYTES       (JTTY_EXT_MAX_FRAMES * JTTY_EXT_BYTES_PER_FRAME - 1)
#define JTTY_EXT_PAYLOAD_FLAG    0x2u  // payload34 bit 1 == protocol bit 33

// CRC-8/ATM: polynomial 0x07, init 0, no reflection, no final XOR.
uint8_t jtty_crc8(const uint8_t* data, int len);

// Pack UTF-8 bytes into 34-bit extension payloads (bit 33 set, bit 34 EOM on
// the final frame only). Returns the number of frames written, or a negative
// JTTY_ERR_* code (JTTY_ERR_BAD_ARGS for NULL/short buffers, empty input,
// more than JTTY_EXT_MAX_BYTES, or max_frames too small).
int jtty_pack_bytes(const uint8_t* data, int len, uint64_t* payloads, int max_frames);

// True when bit 33 of the payload marks an extension frame.
bool jtty_ext_is_ext(uint64_t payload34);

// Structural validity: in range and bit 33 set. No grammar is applied.
bool jtty_ext_payload_valid(uint64_t payload34);

// Split one extension payload into its four bytes and EOM flag.
void jtty_ext_unpack(uint64_t payload34, uint8_t out[4], bool* eom);

// True when the assembled frames form a complete message whose final byte is
// the CRC-8 of the preceding bytes (padding included).
bool jtty_ext_message_valid(const uint64_t* payloads, int n_frames);

// Convenience: UTF-8 bytes -> extension frames -> GFSK audio, mirroring
// jtty_encode_text. Returns the number of samples written, or a JTTY_ERR_*.
int jtty_encode_bytes(const uint8_t* data, int len, float f0_hz, int sample_rate,
                      float amplitude, float* out, int max_out, int* n_frames_out);

#ifdef __cplusplus
}
#endif

#endif // JTTYAF_JTTY_EXT_H
