package com.g7monitor.shared.crypto

import java.security.SecureRandom

private val RNG = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also { RNG.nextBytes(it) }
