package com.g7monitor.shared.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
actual fun httpPostJson(url: String, body: String, onResult: (Boolean, String) -> Unit) {
    val nsurl = NSURL.URLWithString(url)
    if (nsurl == null) { onMain(onResult, false, "URL?"); return }
    val req = NSMutableURLRequest(uRL = nsurl)
    req.setHTTPMethod("POST")
    req.setValue("application/json; charset=utf-8", forHTTPHeaderField = "Content-Type")
    req.setValue("application/json", forHTTPHeaderField = "Accept")
    req.setValue("Mozilla/5.0 (Pebbels iOS)", forHTTPHeaderField = "User-Agent")
    req.setHTTPBody((body as NSString).dataUsingEncoding(NSUTF8StringEncoding))
    val task = NSURLSession.sharedSession.dataTaskWithRequest(req) {
            data: NSData?, response: NSURLResponse?, error: NSError? ->
        val code = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: -1
        val bodyStr = data?.let { (NSString.create(it, NSUTF8StringEncoding) as String?) } ?: ""
        val info = error?.localizedDescription ?: "HTTP $code ${bodyStr.take(120)}".trim()
        onMain(onResult, code in 200..299, info)
    }
    task.resume()
}

private fun onMain(cb: (Boolean, String) -> Unit, ok: Boolean, info: String) {
    dispatch_async(dispatch_get_main_queue()) { cb(ok, info) }
}
