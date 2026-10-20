
#include <jni.h>
#include <string>
#include <fstream>
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

static std::string inspectElf(const std::string& path) {
    std::ifstream file(path, std::ios::binary);

    if (!file) {
        return "Cannot open file";
    }

    unsigned char h[20] = {};
    file.read(reinterpret_cast<char*>(h), sizeof(h));

    if (file.gcount() != sizeof(h)) {
        return "File too small for ELF header";
    }

    if (h[0] != 0x7f ||
        h[1] != 'E' ||
        h[2] != 'L' ||
        h[3] != 'F') {
        return "Not an ELF binary";
    }

    int bits;
    if (h[4] == 1) bits = 32;
    else if (h[4] == 2) bits = 64;
    else return "Invalid ELF class";

    if (h[5] != 1 && h[5] != 2) {
        return "Invalid ELF byte order";
    }

    unsigned int machine;

    if (h[5] == 1) {
        machine = static_cast<unsigned int>(h[18])
            | (static_cast<unsigned int>(h[19]) << 8);
    } else {
        machine = (static_cast<unsigned int>(h[18]) << 8)
            | static_cast<unsigned int>(h[19]);
    }

    std::string arch;

    switch (machine) {
        case 183: arch = "ARM64"; break;
        case 40: arch = "ARM32"; break;
        case 3: arch = "x86"; break;
        case 62: arch = "x86-64"; break;
        default: arch = "Unknown machine"; break;
    }

    return "ELF detected\n"
        "Architecture: " + arch +
        "\nELF class: " + std::to_string(bits) + "-bit" +
        "\nMachine ID: " + std::to_string(machine) +
        "\nValidation: Header inspection only";
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_titanx_emulator_NativeBridge_nativeInfo(
    JNIEnv* env, jclass
) {
    std::string result =
        "TitanX Native Core v0.7\n"
        "Architecture: " + cpuArchitecture() +
        "\nPointer size: " +
        std::to_string(sizeof(void*) * 8) +
        " bits\nC++ engine: ACTIVE\nJNI bridge: ACTIVE";

    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_titanx_emulator_NativeBridge_nativeSelfTest(
    JNIEnv*, jclass
) {
    volatile int a = 19;
    volatile int b = 23;
    return a + b;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_titanx_emulator_NativeBridge_nativeInspectElf(
    JNIEnv* env, jclass, jstring jpath
) {
    if (jpath == nullptr) {
        return env->NewStringUTF("Null file path");
    }

    const char* raw = env->GetStringUTFChars(jpath, nullptr);

    if (raw == nullptr) {
        return nullptr;
    }

    std::string path(raw);
    env->ReleaseStringUTFChars(jpath, raw);

    std::string result = inspectElf(path);
    return env->NewStringUTF(result.c_str());
}
