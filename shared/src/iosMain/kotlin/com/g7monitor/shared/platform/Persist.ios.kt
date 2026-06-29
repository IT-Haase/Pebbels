package com.g7monitor.shared.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

private fun pathFor(key: String): String? {
    val dirs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
    val dir = dirs.firstOrNull() as? String ?: return null
    return "$dir/pebbels_$key.txt"
}

@OptIn(ExperimentalForeignApi::class)
actual fun persistRead(key: String): String? {
    val p = pathFor(key) ?: return null
    return NSString.stringWithContentsOfFile(p, NSUTF8StringEncoding, null)
}

@OptIn(ExperimentalForeignApi::class)
actual fun persistWrite(key: String, value: String) {
    val p = pathFor(key) ?: return
    (value as NSString).writeToFile(p, atomically = true, encoding = NSUTF8StringEncoding, error = null)
}
