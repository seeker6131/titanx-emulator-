
#include <jni.h>
#include <string>
#include <cstdint>

static std::string cpuArchitecture() {
#if defined(__aarch64__)
    return "ARM64 / AArch64";
#elif defined(__arm__)
    return "ARM32";
#elif defined(__x86_64__)
    return "x86-64";
#elif defined(__i386__)
    return "x86";
#else
    return "Unknown";
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_titanx_emulator_NativeBridge_nativeInfo(
    JNIEnv* env,
    jclass
) {
    std::string result =
        "TitanX Native Core v0.6\n"
        "Architecture: " + cpuArchitecture() +
        "\nPointer size: " +
        std::to_string(sizeof(void*) * 8) +
        " bits\n"
        "C++ engine: ACTIVE\n"
        "JNI bridge: ACTIVE";

    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_titanx_emulator_NativeBridge_nativeSelfTest(
    JNIEnv*,
    jclass
) {
    volatile int a = 19;
    volatile int b = 23;

    return a + b;
}
