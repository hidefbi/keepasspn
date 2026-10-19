package com.keepasspn.utils

/** No-op logger used to keep sensitive application data out of logcat. */
object NoOpLog {
    @JvmStatic fun d(tag: String?, msg: String?): Int = 0
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun e(tag: String?, msg: String?): Int = 0
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun i(tag: String?, msg: String?): Int = 0
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun v(tag: String?, msg: String?): Int = 0
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun w(tag: String?, msg: String?): Int = 0
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = 0
}
