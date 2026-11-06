package com.yukisoffd.lyra.x11.tools;

import dalvik.system.DexClassLoader;
import android.system.Os;
import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Runs with app_process against the actual optimized APK, without a Linux/rootfs dependency. */
public final class GuiArtVerifier {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("APK and mapped class-list paths required");
        File apk = new File(args[0]);
        if (!apk.isFile() || (Os.stat(apk.getAbsolutePath()).st_mode & 0222) != 0)
            throw new IllegalArgumentException("APK must be a read-only file");
        ClassLoader loader = new DexClassLoader(apk.getAbsolutePath(), apk.getParent(), null,
                GuiArtVerifier.class.getClassLoader());
        int verified = 0, tasks = 0, failures = 0;
        for (String name : Files.readAllLines(new File(args[1]).toPath(), StandardCharsets.UTF_8)) {
            if (name.isEmpty()) continue;
            try {
                Class<?> type = Class.forName(name, false, loader);
                type.getDeclaredMethods();
                Constructor<?>[] constructors = type.getDeclaredConstructors();
                // Synthetic task constructors only store captures. Instantiate without running
                // them so ART verifies their complete bodies without launching guest commands.
                if (Runnable.class.isAssignableFrom(type) && !type.isInterface() && constructors.length > 0) {
                    Constructor<?> constructor = constructors[0];
                    constructor.setAccessible(true);
                    Class<?>[] parameters = constructor.getParameterTypes();
                    Object[] captures = new Object[parameters.length];
                    for (int i = 0; i < parameters.length; i++) captures[i] = zero(parameters[i]);
                    constructor.newInstance(captures);
                    tasks++;
                    System.out.println("ART_GUI_TASK " + name);
                }
                verified++;
            } catch (Throwable error) {
                failures++;
                System.err.println("ART_REJECTED " + name);
                error.printStackTrace(System.err);
            }
        }
        if (tasks == 0 && failures == 0) {
            System.err.println("No GUI Runnable was verified; check the APK and mapping pair");
            failures++;
        }
        System.out.println("ART_GUI_VERIFY classes=" + verified + " tasks=" + tasks + " failures=" + failures);
        if (failures != 0) System.exit(1);
    }

    private static Object zero(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        throw new AssertionError(type);
    }
}
