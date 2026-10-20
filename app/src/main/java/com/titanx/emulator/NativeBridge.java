
package com.titanx.emulator;

import java.io.File;

public final class NativeBridge {

    private static boolean loaded = false;
    private static String error = "";

    static {
        try {
            System.loadLibrary("titanx");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            error = e.toString();
        } catch (SecurityException e) {
            error = e.toString();
        }
    }

    private NativeBridge() {}

    private static native String nativeInfo();
    private static native int nativeSelfTest();
    private static native String nativeInspectElf(String path);

    public static String report() {
        if (!loaded) {
            return "NATIVE ENGINE: NOT LOADED\n" + error;
        }

        try {
            int test = nativeSelfTest();

            return "NATIVE ENGINE: LOADED\n"
                + nativeInfo()
                + "\nSelf-test: "
                + (test == 42 ? "PASS" : "FAIL")
                + "\nWine: NOT INTEGRATED"
                + "\nFEX: NOT INTEGRATED"
                + "\nDXVK: NOT INTEGRATED";
        } catch (Throwable e) {
            return "NATIVE ERROR:\n" + e;
        }
    }

    public static String inspect(File file) {
        if (!loaded) {
            return "Native library not loaded: " + error;
        }

        if (file == null || !file.isFile()) {
            return "Invalid file";
        }

        try {
            return nativeInspectElf(file.getAbsolutePath());
        } catch (Throwable e) {
            return "Inspection failed: " + e;
        }
    }
}
