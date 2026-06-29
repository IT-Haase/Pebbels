package com.g7monitor.shared.platform

/** Plattformunabhängiger JSON-POST. Callback IMMER auf dem Haupt-Thread
 *  (damit der Aufrufer Compose-State anfassen darf). */
expect fun httpPostJson(url: String, body: String, onResult: (Boolean, String) -> Unit)
