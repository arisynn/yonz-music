package com.yonzmusic.app.network

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Public-client OAuth: no client secret. Separated for RFC 7636 fixture checks. */
object SpotifyPkce {
    fun random(): String = ByteArray(48).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun validState(expected: String, actual: String): Boolean = expected.length>=32 && MessageDigest.isEqual(expected.toByteArray(),actual.toByteArray())
}
