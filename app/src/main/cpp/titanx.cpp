
#include <jni.h>
#include <elf.h>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>
#include <sstream>
#include <algorithm>

static const uint64_t MAX_FILE = 64ULL * 1024 * 1024;

static std::string cpuArchitecture() {
#if defined(__aarch64__)
    return "ARM64 / AArch64";
#elif defined(__arm__)
    return "ARM32";
#elif defined(__x86_64__)
    return "x86-64";
#else
    return "Unknown";
#endif
}

static bool rangeOK(
    uint64_t off,
    uint64_t len,
    uint64_t size
) {
    return off <= size && len <= size - off;
}

template<typename T>
static bool readAt(
    const std::vector<uint8_t>& data,
    uint64_t offset,
    T& result
) {
    if (!rangeOK(offset, sizeof(T), data.size()))
        return false;

    std::memcpy(
        &result,
        data.data() + offset,
        sizeof(T)
    );
    return true;
}

static std::string machineName(uint16_t machine) {
    switch (machine) {
        case EM_AARCH64: return "ARM64";
        case EM_ARM: return "ARM32";
        case EM_386: return "x86";
        case EM_X86_64: return "x86-64";
        default: return "Unknown";
    }
}

static std::string typeName(uint16_t type) {
    switch (type) {
        case ET_EXEC: return "Executable";
        case ET_DYN: return "Shared object / PIE";
        case ET_REL: return "Relocatable";
        case ET_CORE: return "Core";
        default: return "Unknown";
    }
}

template<typename EH, typename PH, typename DY>
static std::string inspectTyped(
    const std::vector<uint8_t>& data,
    const EH& header
) {
    std::ostringstream out;

    out << "ELF detected\n";
    out << "Architecture: "
        << machineName(header.e_machine) << "\n";
    out << "Type: "
        << typeName(header.e_type) << "\n";
    out << "Machine ID: "
        << header.e_machine << "\n";

    if (header.e_phnum == 0) {
        out << "Dependencies: No program headers\n";
        return out.str();
    }

    if (header.e_phentsize != sizeof(PH)) {
        out << "Error: Invalid program header size\n";
        return out.str();
    }

    uint64_t phBytes =
        uint64_t(header.e_phnum) * sizeof(PH);

    if (!rangeOK(
        header.e_phoff, phBytes, data.size()
    )) {
        out << "Error: Program headers out of range\n";
        return out.str();
    }

    std::vector<PH> loads;
    PH dynamicPH = {};
    bool hasDynamic = false;

    for (uint32_t i = 0; i < header.e_phnum; i++) {
        PH ph = {};

        uint64_t off = uint64_t(header.e_phoff)
            + uint64_t(i) * sizeof(PH);

        if (!readAt(data, off, ph)) {
            out << "Error: Cannot read program header\n";
            return out.str();
        }

        if (ph.p_type == PT_LOAD) {
            if (!rangeOK(
                ph.p_offset,
                ph.p_filesz,
                data.size()
            )) {
                out << "Error: Invalid load segment\n";
                return out.str();
            }
            loads.push_back(ph);
        }

        if (ph.p_type == PT_DYNAMIC) {
            dynamicPH = ph;
            hasDynamic = true;
        }
    }

    if (!hasDynamic) {
        out << "Dependencies: No PT_DYNAMIC\n";
        return out.str();
    }

    if (!rangeOK(
        dynamicPH.p_offset,
        dynamicPH.p_filesz,
        data.size()
    )) {
        out << "Error: Dynamic section out of range\n";
        return out.str();
    }

    uint64_t strVirtual = 0;
    uint64_t strSize = 0;
    std::vector<uint64_t> needed;

    uint64_t count =
        dynamicPH.p_filesz / sizeof(DY);

    bool terminated = false;

    for (uint64_t i = 0; i < count; i++) {
        DY d = {};

        if (!readAt(
            data,
            uint64_t(dynamicPH.p_offset)
                + i * sizeof(DY),
            d
        )) {
            out << "Error: Invalid dynamic entry\n";
            return out.str();
        }

        if (d.d_tag == DT_NULL) {
            terminated = true;
            break;
        }

        if (d.d_tag == DT_STRTAB)
            strVirtual = uint64_t(d.d_un.d_ptr);

        if (d.d_tag == DT_STRSZ)
            strSize = uint64_t(d.d_un.d_val);

        if (d.d_tag == DT_NEEDED)
            needed.push_back(
                uint64_t(d.d_un.d_val)
            );
    }

    if (!terminated) {
        out << "Error: Dynamic section not terminated\n";
        return out.str();
    }

    if (needed.empty()) {
        out << "DT_NEEDED: None\n";
        return out.str();
    }

    if (strVirtual == 0 || strSize == 0) {
        out << "Error: Missing dynamic string table\n";
        return out.str();
    }

    bool mapped = false;
    uint64_t strOffset = 0;

    for (const PH& ph : loads) {
        uint64_t vaddr = uint64_t(ph.p_vaddr);
        uint64_t filesz = uint64_t(ph.p_filesz);

        if (strVirtual < vaddr)
            continue;

        uint64_t delta = strVirtual - vaddr;

        if (delta > filesz)
            continue;

        if (strSize > filesz - delta)
            continue;

        uint64_t off =
            uint64_t(ph.p_offset) + delta;

        if (!rangeOK(off, strSize, data.size()))
            continue;

        strOffset = off;
        mapped = true;
        break;
    }

    if (!mapped) {
        out << "Error: Cannot map DT_STRTAB\n";
        return out.str();
    }

    out << "DT_NEEDED (" << needed.size()
        << " entries):\n";

    for (uint64_t index : needed) {
        if (index >= strSize) {
            out << " - [Invalid string offset]\n";
            continue;
        }

        uint64_t start = strOffset + index;
        uint64_t end = strOffset + strSize;

        uint64_t pos = start;

        while (pos < end && data[pos] != 0)
            pos++;

        if (pos == end) {
            out << " - [Unterminated string]\n";
            continue;
        }

        std::string name(
            reinterpret_cast<const char*>(
                data.data() + start
            ),
            static_cast<size_t>(pos - start)
        );

        out << " - " << name << "\n";
    }

    out << "\nInspection only; not executed";
    return out.str();
}

static std::string inspectElf(
    const std::string& path
) {
    std::ifstream file(
        path,
        std::ios::binary | std::ios::ate
    );

    if (!file)
        return "Cannot open file";

    std::streamoff length = file.tellg();

    if (length < 0)
        return "Cannot determine file size";

    if (length > static_cast<std::streamoff>(MAX_FILE))
        return "File exceeds 64 MB inspection limit";

    if (length < EI_NIDENT)
        return "File too small";

    std::vector<uint8_t> data(
        static_cast<size_t>(length)
    );

    file.seekg(0);

    if (!file.read(
        reinterpret_cast<char*>(data.data()),
        length
    )) {
        return "Cannot read file";
    }

    if (std::memcmp(
        data.data(),
        ELFMAG,
        SELFMAG
    ) != 0) {
        return "Not an ELF binary";
    }

    if (data[EI_DATA] != ELFDATA2LSB)
        return "Unsupported ELF endianness";

    if (data[EI_VERSION] != EV_CURRENT)
        return "Invalid ELF version";

    if (data[EI_CLASS] == ELFCLASS64) {
        Elf64_Ehdr h = {};

        if (!readAt(data, 0, h))
            return "Invalid ELF64 header";

        if (h.e_ehsize != sizeof(Elf64_Ehdr))
            return "Invalid ELF64 header size";

        return inspectTyped<
            Elf64_Ehdr,
            Elf64_Phdr,
            Elf64_Dyn
        >(data, h);
    }

    if (data[EI_CLASS] == ELFCLASS32) {
        Elf32_Ehdr h = {};

        if (!readAt(data, 0, h))
            return "Invalid ELF32 header";

        if (h.e_ehsize != sizeof(Elf32_Ehdr))
            return "Invalid ELF32 header size";

        return inspectTyped<
            Elf32_Ehdr,
            Elf32_Phdr,
            Elf32_Dyn
        >(data, h);
    }

    return "Unsupported ELF class";
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_titanx_emulator_NativeBridge_nativeInfo(
    JNIEnv* env, jclass
) {
    std::string result =
        "TitanX Native Core v0.8\n"
        "Architecture: " + cpuArchitecture() +
        "\nPointer size: " +
        std::to_string(sizeof(void*) * 8) +
        " bits\nC++ engine: ACTIVE\n"
        "JNI bridge: ACTIVE\n"
        "ELF dependency inspector: AVAILABLE";

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
    JNIEnv* env,
    jclass,
    jstring jpath
) {
    if (jpath == nullptr)
        return env->NewStringUTF("Null path");

    const char* raw =
        env->GetStringUTFChars(jpath, nullptr);

    if (raw == nullptr)
        return nullptr;

    std::string path(raw);
    env->ReleaseStringUTFChars(jpath, raw);

    std::string result = inspectElf(path);

    return env->NewStringUTF(result.c_str());
}
