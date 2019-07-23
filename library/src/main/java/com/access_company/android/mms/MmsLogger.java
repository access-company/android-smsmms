package com.access_company.android.mms;

import android.util.Log;

public class MmsLogger {
    private static Logger sLogger = new SystemLogger();

    private MmsLogger() {
    }

    public static void setLogger(Logger logger) {
        sLogger = logger;
    }

    public static boolean isLoggable() {
        return sLogger.isLoggable();
    }

    public static <T> T runIfLoggable(Task<T> task) {
        if (isLoggable()) {
            return task.run();
        }

        return null;
    }

    public static int v(String msg) {
        return sLogger.log(Log.VERBOSE, msg, null);
    }

    public static int v(String msg, Throwable tr) {
        return sLogger.log(Log.VERBOSE, msg, tr);
    }

    public static int d(String msg) {
        return sLogger.log(Log.DEBUG, msg, null);
    }

    public static int d(String msg, Throwable tr) {
        return sLogger.log(Log.DEBUG, msg, tr);
    }

    public static int i(String msg) {
        return sLogger.log(Log.INFO, msg, null);
    }

    public static int i(String msg, Throwable tr) {
        return sLogger.log(Log.INFO, msg, tr);
    }

    public static int w(String msg) {
        return sLogger.log(Log.WARN, msg, null);
    }

    public static int w(String msg, Throwable tr) {
        return sLogger.log(Log.WARN, msg, tr);
    }

    public static int e(String msg) {
        return sLogger.log(Log.ERROR, msg, null);
    }

    public static int e(String msg, Throwable tr) {
        return sLogger.log(Log.ERROR, msg, tr);
    }

    public interface Task<T> {
        T run();
    }

    public interface Logger {
        boolean isLoggable();

        int log(int level, String msg, Throwable tr);
    }

    private static class SystemLogger implements Logger {
        @Override
        public boolean isLoggable() {
            return true;
        }

        @Override
        public int log(int level, String msg, Throwable tr) {
            String stackTrace = "";
            if (tr != null) {
                stackTrace = Log.getStackTraceString(tr);
            }

            int pid = android.os.Process.myPid();
            int tid = android.os.Process.myTid();
            return Log.println(level, "MmsLogger", "[p:" + pid + "][t:" + tid + "] " + msg + stackTrace);
        }
    }
}