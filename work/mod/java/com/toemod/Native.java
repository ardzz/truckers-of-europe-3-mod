package com.toemod;

/** JNI bridge implemented in libtoemod.so. */
public final class Native {
    private Native() {}

    /**
     * JSON array of feature descriptors:
     * [{"id","category","label","type"(0 toggle,1 toggle+float,2 toggle+int,3 choice),
     *   "def_on","def_value","min","max","choices":"A|B|C" or ""}]
     */
    public static native String features();

    public static native void set(String id, boolean on, float value);

    /** True once the il2cpp hooks are installed; {@link #set} must not be called before. */
    public static native boolean ready();

    /** Fills a reusable 32-value buffer with cached diagnostics; NaN means no sample. */
    public static native void debugSnapshot(double[] values);
}
