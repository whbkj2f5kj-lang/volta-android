// Platzhalter für ffmpeg (Video des CarPlay-Dongles): kein Decoder. GPL-3.0-or-later
#pragma once
#include <stdint.h>
struct AVCodec {};
struct AVCodecContext { int flags = 0, flags2 = 0, thread_count = 0, thread_type = 0; };
struct AVFrame { int format = 0, width = 0, height = 0, color_range = 0; uint8_t *data[8] = {}; int linesize[8] = {}; };
struct AVPacket { uint8_t *data = nullptr; int size = 0; };
enum { AV_CODEC_ID_H264 = 27, AV_CODEC_FLAG_LOW_DELAY = 1 << 19, AV_CODEC_FLAG2_FAST = 1, FF_THREAD_SLICE = 2, FF_THREAD_FRAME = 1,
       AV_INPUT_BUFFER_PADDING_SIZE = 64, AV_PIX_FMT_YUV420P = 0, AV_PIX_FMT_YUVJ420P = 12, AVCOL_RANGE_JPEG = 2 };
inline const AVCodec *avcodec_find_decoder(int) { return nullptr; }
inline AVCodecContext *avcodec_alloc_context3(const AVCodec *) { return nullptr; }
inline int avcodec_open2(AVCodecContext *, const AVCodec *, void *) { return -1; }
inline void avcodec_free_context(AVCodecContext **c) { if (c) *c = nullptr; }
inline AVFrame *av_frame_alloc() { return nullptr; }
inline void av_frame_free(AVFrame **f) { if (f) *f = nullptr; }
inline void av_frame_unref(AVFrame *) {}
inline AVPacket *av_packet_alloc() { return nullptr; }
inline void av_packet_free(AVPacket **p) { if (p) *p = nullptr; }
inline int avcodec_send_packet(AVCodecContext *, const AVPacket *) { return -1; }
inline int avcodec_receive_frame(AVCodecContext *, AVFrame *) { return -1; }
