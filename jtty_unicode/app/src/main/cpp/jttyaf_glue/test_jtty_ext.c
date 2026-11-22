// test_jtty_ext.c — host tests for the JTTY Unicode extension-frame layer.
//
// Golden vectors match the verified Windows-side implementation:
//   "中" (E4 B8 AD)      -> 1 frame, CRC C8
//   "A"  (41 20 20 pad)  -> 1 frame, CRC A3
//   "Hello"              -> 2 frames, CRC 24 on the tail frame
//  63 bytes              -> 16 frames, message CRC valid

#include "jtty_test_util.h"

#include "jtty_ext.h"

static uint64_t bits34(const char* bits)
{
    uint64_t value = 0;
    for (; *bits; ++bits) {
        value = (value << 1) | (uint64_t)(*bits == '1');
    }
    return value;
}

static void test_crc8(void)
{
    printf("CRC-8/ATM\n");
    const uint8_t check_value[] = "123456789";
    check(jtty_crc8(check_value, 9) == 0xF4u, "check value 0xF4");

    const uint8_t zhong[] = { 0xE4, 0xB8, 0xAD };
    check(jtty_crc8(zhong, 3) == 0xC8u, "E4 B8 AD -> C8");

    const uint8_t a_padded[] = { 0x41, 0x20, 0x20 };
    check(jtty_crc8(a_padded, 3) == 0xA3u, "41 20 20 -> A3");

    const uint8_t hello[] = { 0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x20, 0x20 };
    check(jtty_crc8(hello, 7) == 0x24u, "Hello padding -> 24");

    check(jtty_crc8(NULL, 3) == 0u, "NULL guard");
}

static void test_golden_frames(void)
{
    printf("Golden frames\n");
    uint64_t payloads[JTTY_EXT_MAX_FRAMES];

    const uint8_t zhong[] = { 0xE4, 0xB8, 0xAD };
    int n = jtty_pack_bytes(zhong, (int)sizeof zhong, payloads, JTTY_EXT_MAX_FRAMES);
    check(n == 1, "\"中\" packs to 1 frame");
    check(payloads[0] == bits34("1110010010111000101011011100100011"),
          "\"中\" payload bits");
    check(jtty_ext_is_ext(payloads[0]), "\"中\" has extension marker");
    check((payloads[0] & 1u) == 1u, "\"中\" is EOM");

    const uint8_t a[] = { 0x41 };
    n = jtty_pack_bytes(a, 1, payloads, JTTY_EXT_MAX_FRAMES);
    check(n == 1, "\"A\" packs to 1 frame");
    check(payloads[0] == bits34("0100000100100000001000001010001111"),
          "\"A\" payload bits (0x20 padding)");

    const uint8_t hello[] = { 'H', 'e', 'l', 'l', 'o' };
    n = jtty_pack_bytes(hello, (int)sizeof hello, payloads, JTTY_EXT_MAX_FRAMES);
    check(n == 2, "\"Hello\" packs to 2 frames");
    check(payloads[0] == bits34("0100100001100101011011000110110010"),
          "\"Hello\" first frame");
    check(payloads[1] == bits34("0110111100100000001000000010010011"),
          "\"Hello\" tail frame");
    check((payloads[0] & 1u) == 0u && (payloads[1] & 1u) == 1u,
          "EOM only on the tail frame");
    check(jtty_ext_message_valid(payloads, n), "\"Hello\" message CRC");
}

static void test_unpack(void)
{
    printf("Unpack\n");
    uint64_t payloads[JTTY_EXT_MAX_FRAMES];
    const uint8_t zhong[] = { 0xE4, 0xB8, 0xAD };
    (void)jtty_pack_bytes(zhong, (int)sizeof zhong, payloads, JTTY_EXT_MAX_FRAMES);

    uint8_t bytes[4];
    bool eom = false;
    jtty_ext_unpack(payloads[0], bytes, &eom);
    check(memcmp(bytes, zhong, 3) == 0, "first three bytes round-trip");
    check(bytes[3] == 0xC8u, "tail byte is the CRC-8");
    check(eom, "EOM survives unpack");

    check(jtty_ext_payload_valid(payloads[0]), "extension payload valid");
    check(!jtty_ext_payload_valid(0x0ull), "all-zero payload rejected");
    check(!jtty_ext_payload_valid(bits34("0000100110111101111000110101000001")),
          "native payload is not an extension payload");
}

static void test_limits(void)
{
    printf("Limits\n");
    uint64_t payloads[JTTY_EXT_MAX_FRAMES];
    uint8_t data[64];

    for (int i = 0; i < (int)sizeof data; ++i) {
        data[i] = (uint8_t)('A' + (i % 26));
    }

    int n = jtty_pack_bytes(data, 63, payloads, JTTY_EXT_MAX_FRAMES);
    check(n == 16, "63 bytes packs to 16 frames");
    check(jtty_ext_message_valid(payloads, n), "63-byte message CRC valid");

    uint8_t rebuilt[64];
    for (int i = 0; i < n; ++i) {
        jtty_ext_unpack(payloads[i], rebuilt + i * 4, NULL);
    }
    check(memcmp(rebuilt, data, 63) == 0, "63 bytes round-trip");
    check(rebuilt[63] == jtty_crc8(data, 63), "tail byte holds CRC-8");

    check(jtty_pack_bytes(data, 64, payloads, JTTY_EXT_MAX_FRAMES) == JTTY_ERR_BAD_ARGS,
          "64 bytes rejected");
    check(jtty_pack_bytes(data, 0, payloads, JTTY_EXT_MAX_FRAMES) == JTTY_ERR_BAD_ARGS,
          "empty input rejected");
    check(jtty_pack_bytes(NULL, 5, payloads, JTTY_EXT_MAX_FRAMES) == JTTY_ERR_BAD_ARGS,
          "NULL input rejected");
    check(jtty_pack_bytes(data, 5, NULL, JTTY_EXT_MAX_FRAMES) == JTTY_ERR_BAD_ARGS,
          "NULL output rejected");

    const uint8_t hello[] = { 'H', 'e', 'l', 'l', 'o' };
    check(jtty_pack_bytes(hello, (int)sizeof hello, payloads, 1) == JTTY_ERR_BAD_ARGS,
          "too-small max_frames rejected");

    (void)jtty_pack_bytes(hello, (int)sizeof hello, payloads, JTTY_EXT_MAX_FRAMES);
    payloads[1] ^= (1ull << 10);
    check(!jtty_ext_message_valid(payloads, 2), "corrupted message fails CRC-8");
}

static void tones_to_llr(const uint8_t tones[JTTY_FRAME_SYMBOLS],
                         float llr[JTTY_DATA_SYMBOLS][JTTY_NUM_TONES])
{
    for (int i = 0; i < JTTY_DATA_SYMBOLS; ++i) {
        for (int k = 0; k < JTTY_NUM_TONES; ++k) {
            llr[i][k] = 0.0f;
        }
        llr[i][tones[JTTY_SYNC_SYMBOLS + i]] = 1.0f;
    }
}

static void test_decoder(void)
{
    printf("Decoder\n");
    uint64_t payloads[JTTY_EXT_MAX_FRAMES];
    const uint8_t zhong[] = { 0xE4, 0xB8, 0xAD };
    (void)jtty_pack_bytes(zhong, (int)sizeof zhong, payloads, JTTY_EXT_MAX_FRAMES);

    uint8_t tones[JTTY_FRAME_SYMBOLS];
    float llr[JTTY_DATA_SYMBOLS][JTTY_NUM_TONES];
    jtty_encode_frame(payloads[0], tones);
    tones_to_llr(tones, llr);

    uint64_t decoded = 0;
    int rank = -1;
    check(jtty_decode_symbols(llr, &decoded, &rank), "extension frame decodes");
    check(decoded == payloads[0], "extension payload round-trips");

    uint64_t native_payloads[JTTY_MAX_FRAMES];
    int n = jtty_pack_text("CQ K1ABC CQ", JTTY_PROFILE_GENERIC, native_payloads,
                           JTTY_MAX_FRAMES);
    check(n == 1, "native reference message packs");
    jtty_encode_frame(native_payloads[0], tones);
    tones_to_llr(tones, llr);
    check(jtty_decode_symbols(llr, &decoded, &rank), "native frame still decodes");
    check(decoded == native_payloads[0], "native payload round-trips");
}

static void test_receiver(void)
{
    printf("Receiver\n");
    uint64_t payloads[JTTY_EXT_MAX_FRAMES];
    const uint8_t zhong[] = { 0xE4, 0xB8, 0xAD };
    (void)jtty_pack_bytes(zhong, (int)sizeof zhong, payloads, JTTY_EXT_MAX_FRAMES);

    uint8_t tones[JTTY_FRAME_SYMBOLS];
    jtty_encode_frame(payloads[0], tones);

    const int n = jtty_waveform_samples(1, JTTY_SAMPLE_RATE);
    float* wave = (float*)malloc(sizeof(float) * (size_t)n);
    check(wave != NULL, "waveform buffer");
    if (!wave) {
        return;
    }
    check(jtty_modulate(tones, 1, 1500.0f, JTTY_SAMPLE_RATE, 0.8f, wave, n) == n,
          "modulate one extension frame");

    jtty_rx_t* rx = jtty_rx_create(JTTY_SAMPLE_RATE);
    check(rx != NULL, "receiver created");
    if (!rx) {
        free(wave);
        return;
    }
    jtty_rx_set_search_band(rx, 300.0f, 2700.0f);
    jtty_rx_set_rx_frequency(rx, 1500.0f, 50.0f);
    jtty_rx_push(rx, wave, n);
    jtty_rx_flush(rx);

    jtty_decode_t d;
    bool found = false;
    while (jtty_rx_pop(rx, &d)) {
        if (d.ext && d.data[0] == 0xE4 && d.data[1] == 0xB8 && d.data[2] == 0xAD) {
            found = true;
            break;
        }
    }
    check(found, "receiver reports extension frame bytes");
    if (found) {
        check(d.eom, "receiver preserves EOM");
    }
    jtty_rx_destroy(rx);
    free(wave);
}

static void test_encode_bytes(void)
{
    printf("Encode bytes\n");
    const uint8_t zhong[] = { 0xE4, 0xB8, 0xAD };
    const int samples = jtty_waveform_samples(1, JTTY_SAMPLE_RATE);
    check(samples == JTTY_FRAME_SAMPLES, "one frame sample count");

    float* wave = (float*)malloc(sizeof(float) * (size_t)samples);
    check(wave != NULL, "encode buffer");
    if (!wave) {
        return;
    }
    int frames = 0;
    const int written = jtty_encode_bytes(zhong, (int)sizeof zhong, 1500.0f,
                                          JTTY_SAMPLE_RATE, 0.8f, wave, samples, &frames);
    check(written == samples, "encode_bytes writes one frame");
    check(frames == 1, "encode_bytes reports one frame");

    float peak = 0.0f;
    for (int i = 0; i < written; ++i) {
        const float v = fabsf(wave[i]);
        if (v > peak) peak = v;
    }
    check(peak > 0.5f, "waveform carries signal");
    free(wave);
}

int main(void)
{
    printf("test_jtty_ext: JTTY Unicode extension frames\n");
    test_crc8();
    test_golden_frames();
    test_unpack();
    test_limits();
    test_decoder();
    test_receiver();
    test_encode_bytes();
    if (g_failures) {
        printf("FAILED: %d check(s)\n", g_failures);
        return 1;
    }
    printf("all extension-frame checks passed\n");
    return 0;
}
