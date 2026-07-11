package de.robv.android.xposed;

public final class XposedHelpers {
    public static Class<?> findClass(String n, ClassLoader cl) throws ClassNotFoundException { return null; }
    public static Class<?> findClassIfExists(String n, ClassLoader cl) { return null; }
    public static Object callMethod(Object o, String m, Object... args) { return null; }
    public static Object callStaticMethod(Class<?> c, String m, Object... args) { return null; }
    public static Object getObjectField(Object o, String f) { return null; }
    public static void setObjectField(Object o, String f, Object v) {}
    public static Object newInstance(Class<?> c, Object... args) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> c, String m, Object... args) { return null; }
}