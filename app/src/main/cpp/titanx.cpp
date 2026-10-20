
#include <jni.h>
#include <elf.h>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>
#include <sstream>

using Bytes = std::vector<unsigned char>;

static bool within(uint64_t p, uint64_t n, uint64_t size) {
    return p <= size && n <= size - p;
}

template<class T>
static bool get(const Bytes& b, uint64_t p, T& value) {
    if (!within(p, sizeof(T), b.size())) return false;
    memcpy(&value, b.data() + p, sizeof(T));
    return true;
}

static std::string arch(uint16_t m) {
    switch (m) {
        case EM_AARCH64: return "ARM64";
        case EM_ARM: return "ARM32";
        case EM_386: return "x86";
        case EM_X86_64: return "x86-64";
        default: return "Unknown";
    }
}

static std::string type(uint16_t t) {
    switch (t) {
        case ET_EXEC: return "Executable";
        case ET_DYN: return "Shared object / PIE";
        case ET_REL: return "Relocatable";
        default: return "Other";
    }
}

template<class EH, class PH, class DY>
static std::string parse(const Bytes& b, const EH& h) {
    std::ostringstream out;
    out << "ELF detected\n";
    out << "Architecture: " << arch(h.e_machine) << "\n";
    out << "Type: " << type(h.e_type) << "\n";
    out << "Machine ID: " << h.e_machine << "\n";

    if (h.e_phnum == 0) {
        out << "DT_SONAME: None\nDT_NEEDED: None\n";
        return out.str();
    }

    if (h.e_phentsize != sizeof(PH) ||
        !within(h.e_phoff,
            uint64_t(h.e_phnum) * sizeof(PH), b.size())) {
        return "Error: Invalid program headers";
    }

    std::vector<PH> loads;
    PH dynamic = {};
    bool foundDynamic = false;

    for (uint32_t i = 0; i < h.e_phnum; ++i) {
        PH p = {};
        if (!get(b, uint64_t(h.e_phoff) +
                     uint64_t(i) * sizeof(PH), p)) {
            return "Error: Cannot read program header";
        }

        if (p.p_type == PT_LOAD) {
            if (!within(p.p_offset, p.p_filesz, b.size()))
                return "Error: Invalid PT_LOAD";
            loads.push_back(p);
        }

        if (p.p_type == PT_DYNAMIC) {
            dynamic = p;
            foundDynamic = true;
        }
    }

    if (!foundDynamic) {
        out << "DT_SONAME: None\nDT_NEEDED: None\n";
        return out.str();
    }

    if (!within(dynamic.p_offset,
                dynamic.p_filesz, b.size())) {
        return "Error: Invalid PT_DYNAMIC";
    }

    uint64_t strAddr = 0, strSize = 0;
    uint64_t sonameIndex = 0;
    bool hasSoname = false;
    bool terminated = false;
    std::vector<uint64_t> needed;

    uint64_t count = dynamic.p_filesz / sizeof(DY);
    for (uint64_t i = 0; i < count; ++i) {
        DY d = {};
        if (!get(b, uint64_t(dynamic.p_offset) +
                     i * sizeof(DY), d)) {
            return "Error: Invalid dynamic entry";
        }

        if (d.d_tag == DT_NULL) {
            terminated = true;
            break;
        }
        if (d.d_tag == DT_STRTAB)
            strAddr = uint64_t(d.d_un.d_ptr);
        if (d.d_tag == DT_STRSZ)
            strSize = uint64_t(d.d_un.d_val);
        if (d.d_tag == DT_NEEDED)
            needed.push_back(uint64_t(d.d_un.d_val));
        if (d.d_tag == DT_SONAME) {
            sonameIndex = uint64_t(d.d_un.d_val);
            hasSoname = true;
        }
    }

    if (!terminated)
        return "Error: Unterminated dynamic table";

    if (!hasSoname && needed.empty()) {
        out << "DT_SONAME: None\nDT_NEEDED: None\n";
        return out.str();
    }

    if (strAddr == 0 || strSize == 0)
        return "Error: Missing string table";

    uint64_t strOff = 0;
    bool mapped = false;

    for (const PH& p : loads) {
        uint64_t addr = uint64_t(p.p_vaddr);
        uint64_t size = uint64_t(p.p_filesz);
        if (strAddr < addr) continue;

        uint64_t delta = strAddr - addr;
        if (delta > size || strSize > size - delta)
            continue;

        uint64_t offset = uint64_t(p.p_offset) + delta;
        if (!within(offset, strSize, b.size()))
            continue;

        strOff = offset;
        mapped = true;
        break;
    }

    if (!mapped)
        return "Error: Cannot map dynamic strings";

    auto stringAt = [&](uint64_t index, std::string& s)
        -> bool {
        if (index >= strSize) return false;
        uint64_t start = strOff + index;
        uint64_t end = strOff + strSize;
        uint64_t pos = start;
        while (pos < end && b[pos] != 0) ++pos;
        if (pos == end) return false;
        s.assign(reinterpret_cast<const char*>(
                     b.data() + start),
                 static_cast<size_t>(pos - start));
        return true;
    };

    if (hasSoname) {
        std::string s;
        if (!stringAt(sonameIndex, s))
            return "Error: Invalid DT_SONAME";
        out << "DT_SONAME: " << s << "\n";
    } else {
        out << "DT_SONAME: None\n";
    }

    if (needed.empty()) {
        out << "DT_NEEDED: None\n";
    } else {
        out << "DT_NEEDED (" << needed.size()
            << " entries):\n";
        for (uint64_t index : needed) {
            std::string s;
            if (!stringAt(index, s))
                return "Error: Invalid DT_NEEDED";
            out << " - " << s << "\n";
        }
    }

    out << "Inspection only; not executed";
    return out.str();
}

static std::string inspect(const std::string& path) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) return "Error: Cannot open file";

    std::streamoff length = file.tellg();
    if (length < 0 || length > 64LL * 1024 * 1024)
        return "Error: Unsupported file size";

    if (length < EI_NIDENT)
        return "Not an ELF binary";

    Bytes b(static_cast<size_t>(length));
    file.seekg(0);
    if (!file.read(reinterpret_cast<char*>(b.data()), length))
        return "Error: Cannot read file";

    if (memcmp(b.data(), ELFMAG, SELFMAG) != 0)
        return "Not an ELF binary";

    if (b[EI_DATA] != ELFDATA2LSB)
        return "Error: Unsupported endianness";

    if (b[EI_VERSION] != EV_CURRENT)
        return "Error: Invalid ELF version";

    if (b[EI_CLASS] == ELFCLASS64) {
        Elf64_Ehdr h = {};
        if (!get(b, 0, h) || h.e_ehsize != sizeof(h))
            return "Error: Invalid ELF64 header";
        return parse<Elf64_Ehdr, Elf64_Phdr, Elf64_Dyn>(b, h);
    }

    if (b[EI_CLASS] == ELFCLASS32) {
        Elf32_Ehdr h = {};
        if (!get(b, 0, h) || h.e_ehsize != sizeof(h))
            return "Error: Invalid ELF32 header";
        return parse<Elf32_Ehdr, Elf32_Phdr, Elf32_Dyn>(b, h);
    }

    return "Error: Unsupported ELF class";
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_titanx_emulator_NativeBridge_nativeInfo(
    JNIEnv* env, jclass) {
    std::string info =
        "TitanX Native Core v1.0\n"
        "Architecture: ARM64 / AArch64\n"
        "Pointer size: " +
        std::to_string(sizeof(void*) * 8) +
        " bits\nC++ engine: ACTIVE\n"
        "JNI bridge: ACTIVE\n"
        "ELF + SONAME inspector: AVAILABLE";
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_titanx_emulator_NativeBridge_nativeSelfTest(
    JNIEnv*, jclass) {
    volatile int a = 19, b = 23;
    return a + b;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_titanx_emulator_NativeBridge_nativeInspectElf(
    JNIEnv* env, jclass, jstring jpath) {
    if (!jpath) return env->NewStringUTF("Error: Null path");

    const char* raw = env->GetStringUTFChars(jpath, nullptr);
    if (!raw) return nullptr;

    std::string path(raw);
    env->ReleaseStringUTFChars(jpath, raw);

    std::string result = inspect(path);
    return env->NewStringUTF(result.c_str());
}
