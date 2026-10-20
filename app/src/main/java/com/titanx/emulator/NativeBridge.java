
package com.titanx.emulator;

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

    public static String report() {
        if (!loaded) {
            return "NATIVE ENGINE: NOT LOADED\n"
                + error;
        }

        try {
            int result = nativeSelfTest();

            return "NATIVE ENGINE: LOADED\n"
                + nativeInfo()
                + "\nSelf-test: "
                + (result == 42 ? "PASS" : "FAIL")
                + "\nWine: NOT INTEGRATED"
                + "\nFEX: NOT INTEGRATED"
                + "\nDXVK: NOT INTEGRATED";

        } catch (Throwable e) {
            return "NATIVE ENGINE ERROR:\n"
                + e.toString();
        }
    }
}
