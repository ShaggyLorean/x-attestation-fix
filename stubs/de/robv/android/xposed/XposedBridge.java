package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

public final class XposedBridge {
    public static XC_MethodHook.Unhook hookMethod(Member m, XC_MethodHook hook) { return null; }
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> c, String name, XC_MethodHook hook) { return null; }
    public static void log(String s) {}
    public static void log(Throwable t) {}
}