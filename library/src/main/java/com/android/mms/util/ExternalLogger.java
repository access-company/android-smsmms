
package com.android.mms.util;


import java.util.concurrent.CopyOnWriteArrayList;

public class ExternalLogger {
    private static final CopyOnWriteArrayList<LoggingListener> sListener = new CopyOnWriteArrayList<LoggingListener>();

    public interface LoggingListener {
        void v(String message, Throwable tr);

        void d(String message, Throwable tr);

        void i(String message, Throwable tr);

        void w(String message, Throwable tr);

        void e(String message, Throwable tr);
    }

    private ExternalLogger() {
    }

    public static void addListener(LoggingListener listener) {
        sListener.add(listener);
    }

    public static void removeListener(LoggingListener listener) {
        sListener.remove(listener);
    }

    public static void v(String message) {
        v(message, null);
    }

    public static void v(String message, Throwable tr) {
        for (LoggingListener listener : sListener) {
            listener.v(message, tr);
        }
    }

    public static void d(String message) {
        d(message, null);
    }

    public static void d(String message, Throwable tr) {
        for (LoggingListener listener : sListener) {
            listener.d(message, tr);
        }
    }

    public static void i(String message) {
        i(message, null);
    }

    public static void i(String message, Throwable tr) {
        for (LoggingListener listener : sListener) {
            listener.i(message, tr);
        }
    }

    public static void w(String message) {
        w(message, null);
    }

    public static void w(String message, Throwable tr) {
        for (LoggingListener listener : sListener) {
            listener.w(message, tr);
        }
    }

    public static void w(Throwable tr) {
        w(null, tr);
    }

    public static void e(String message) {
        e(message, null);
    }

    public static void e(String message, Throwable tr) {
        for (LoggingListener listener : sListener) {
            listener.e(message, tr);
        }
    }

    public static String getHashCodeHex(Object obj) {
        if (obj == null) {
            return "null";
        }
        return Integer.toString(obj.hashCode(), 16);
    }

    public static String getSimpleName(Object obj) {
        if (obj == null) {
            return "null";
        }
        return obj.getClass().getSimpleName();
    }

    public static String getNameWithHash(Object obj) {
        return getSimpleName(obj) + "@" + getHashCodeHex(obj);
    }
}
