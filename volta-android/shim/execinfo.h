// execinfo gibt es im Android-NDK erst ab API 33: dort leere Platzhalter. GPL-3.0-or-later
#pragma once
#if defined(__ANDROID__)
static inline int backtrace(void **, int) { return 0; }
static inline void backtrace_symbols_fd(void *const *, int, int) {}
#else
#include_next <execinfo.h>
#endif
