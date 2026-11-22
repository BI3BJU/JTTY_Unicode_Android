// jtty_ext.c — JTTY Unicode extension frames (34-bit layer).
//
// Wire layout, protocol bit numbering (bit 1 is transmitted first):
//   bits 1..32  four content/padding/CRC bytes, most significant bit first
//   bit  33     '1'  extension marker
//   bit  34     '1'  end of message (final frame only)
//
// A message is 1..16 frames of four bytes. The final frame holds three content
// bytes and the CRC-8/ATM of all preceding bytes (including 0x20 padding), so
// the maximum content length is 15*4 + 3 = 63 bytes.

#include "jtty_ext.h"

#include <string.h>

#include "jtty.h"

#define EXT_MAX_MESSAGE (JTTY_EXT_MAX_FRAMES * JTTY_EXT_BYTES_PER_FRAME)

uint8_t jtty_crc8(const uint8_t* data, int len)
{
    uint8_t crc = 0u;
    if (!data || len <= 0) {
        return 0u;
    }
    for (int i = 0; i < len; ++i) {
        crc ^= data[i];
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 0x80u) ? (uint8_t)((crc << 1) ^ 0x07u) : (uint8_t)(crc << 1);
        }
    }
    return crc;
}

int jtty_pack_bytes(const uint8_t* data, int len, uint64_t* payloads, int max_frames)
{
    if (!data || !payloads || len < 1 || len > JTTY_EXT_MAX_BYTES) {
        return JTTY_ERR_BAD_ARGS;
    }

    const int n_frames =
        (len + 1 + JTTY_EXT_BYTES_PER_FRAME - 1) / JTTY_EXT_BYTES_PER_FRAME;
    if (n_frames < 1 || n_frames > JTTY_EXT_MAX_FRAMES || max_frames < n_frames) {
        return JTTY_ERR_BAD_ARGS;
    }

    uint8_t buffer[EXT_MAX_MESSAGE];
    memset(buffer, 0x20, sizeof buffer);
    memcpy(buffer, data, (size_t)len);

    const int total = n_frames * JTTY_EXT_BYTES_PER_FRAME;
    buffer[total - 1] = jtty_crc8(buffer, total - 1);

    for (int i = 0; i < n_frames; ++i) {
        const uint8_t* p = buffer + i * JTTY_EXT_BYTES_PER_FRAME;
        const uint32_t word = ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
                              ((uint32_t)p[2] << 8) | (uint32_t)p[3];
        payloads[i] = ((uint64_t)word << 2) | JTTY_EXT_PAYLOAD_FLAG |
                      (i == n_frames - 1 ? 1u : 0u);
    }
    return n_frames;
}

bool jtty_ext_is_ext(uint64_t payload34)
{
    return ((payload34 >> 1) & 1u) == 1u;
}

bool jtty_ext_payload_valid(uint64_t payload34)
{
    return payload34 < (1ull << 34) && jtty_ext_is_ext(payload34);
}

void jtty_ext_unpack(uint64_t payload34, uint8_t out[4], bool* eom)
{
    const uint32_t word = (uint32_t)((payload34 >> 2) & 0xFFFFFFFFu);
    out[0] = (uint8_t)(word >> 24);
    out[1] = (uint8_t)(word >> 16);
    out[2] = (uint8_t)(word >> 8);
    out[3] = (uint8_t)word;
    if (eom) {
        *eom = (payload34 & 1u) != 0u;
    }
}

bool jtty_ext_message_valid(const uint64_t* payloads, int n_frames)
{
    uint8_t buffer[EXT_MAX_MESSAGE];
    if (!payloads || n_frames < 1 || n_frames > JTTY_EXT_MAX_FRAMES) {
        return false;
    }
    for (int i = 0; i < n_frames; ++i) {
        if (!jtty_ext_payload_valid(payloads[i])) {
            return false;
        }
        jtty_ext_unpack(payloads[i], buffer + i * JTTY_EXT_BYTES_PER_FRAME, NULL);
    }
    const int total = n_frames * JTTY_EXT_BYTES_PER_FRAME;
    return jtty_crc8(buffer, total - 1) == buffer[total - 1];
}

int jtty_encode_bytes(const uint8_t* data, int len, float f0_hz, int sample_rate,
                      float amplitude, float* out, int max_out, int* n_frames_out)
{
    uint64_t payloads[JTTY_EXT_MAX_FRAMES];
    const int frames = jtty_pack_bytes(data, len, payloads, JTTY_EXT_MAX_FRAMES);
    if (frames < 0) {
        return frames;
    }
    const int samples = jtty_waveform_samples(frames, sample_rate);
    if (samples <= 0 || !out || max_out < samples) {
        return JTTY_ERR_BAD_ARGS;
    }

    uint8_t tones[JTTY_FRAME_SYMBOLS * JTTY_EXT_MAX_FRAMES];
    for (int i = 0; i < frames; ++i) {
        jtty_encode_frame(payloads[i], tones + i * JTTY_FRAME_SYMBOLS);
    }
    const int written = jtty_modulate(tones, frames, f0_hz, sample_rate, amplitude,
                                      out, max_out);
    if (written < 0) {
        return written;
    }
    if (n_frames_out) {
        *n_frames_out = frames;
    }
    return written;
}
