#include <cstddef>
#include <cstdint>
#include <cerrno>
#include <dlfcn.h>
#include <android/log.h>

// This private liblog entry point is not in the NDK link interface. The AOSP
// event-list implementation also references it for SECURITY buffers. Preserve
// the real platform implementation when exported; report unsupported otherwise.
// Ordinary EVENTS writes (used by Unicode validation) still use liblog directly.
using Writer = int (*)(int32_t, const void*, size_t);
static Writer resolve(const char* name) {
    // Keep the system library handle alive for the lifetime of the pointers.
    static void* library = dlopen("liblog.so", RTLD_NOW | RTLD_LOCAL);
    return library ? reinterpret_cast<Writer>(dlsym(library, name)) : nullptr;
}
extern "C" int __android_log_security_bwrite(int32_t tag, const void* payload, size_t length) {
    static Writer writer = resolve("__android_log_security_bwrite");
    return writer ? writer(tag, payload, length) : -ENOSYS;
}
extern "C" int __android_log_stats_bwrite(int32_t tag, const void* payload, size_t length) {
    static Writer writer = resolve("__android_log_stats_bwrite");
    return writer ? writer(tag, payload, length) : -ENOSYS;
}
extern "C" int __android_log_bwrite(int32_t tag, const void* payload, size_t length) {
    static Writer writer = resolve("__android_log_bwrite");
    if (writer) return writer(tag, payload, length);
    __android_log_print(ANDROID_LOG_ERROR, "ForgeTools", "Binary error event %d could not be written: platform API unavailable", tag);
    return -ENOSYS;
}
