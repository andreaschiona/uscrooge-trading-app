package com.uscrooge.app.data.api

import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Response
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class KrakenAuthInterceptor(
    private val apiKey: String,
    private val apiSecret: String
) : Interceptor {

    companion object {
        private const val TAG = "KrakenAuthInterceptor"
        private val globalNonce = AtomicLong(0L)

        fun nextNonce(): Long {
            while (true) {
                val now = System.currentTimeMillis() * 1_000_000L
                val last = globalNonce.get()
                val candidate = if (now > last) now else last + 1L
                if (globalNonce.compareAndSet(last, candidate)) {
                    return candidate
                }
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Only add auth to private endpoints
        if (!originalRequest.url.encodedPath.contains("/private/")) {
            return chain.proceed(originalRequest)
        }

        val path = originalRequest.url.encodedPath

        for (attempt in 1..2) {
            val originalFormBody = originalRequest.body as? FormBody
            val nonce = nextNonce()

            val signedBody = rebuildFormBody(originalFormBody, nonce)
            val postData = buildPostData(signedBody)
            val signature = generateSignature(path, postData, nonce)

            val newRequest = originalRequest.newBuilder()
                .method(originalRequest.method, signedBody)
                .header("API-Key", apiKey)
                .header("API-Sign", signature)
                .build()

            val response = chain.proceed(newRequest)

            if (attempt < 2 && isInvalidNonceResponse(response)) {
                android.util.Log.w(TAG, "EAPI:Invalid nonce, retrying with fresh nonce")
                response.close()
                continue
            }

            return response
        }

        // Should not reach here
        return chain.proceed(originalRequest)
    }

    private fun isInvalidNonceResponse(response: Response): Boolean {
        return try {
            val body = response.body ?: return false
            val source = body.source()
            source.request(Long.MAX_VALUE)
            val buffer = source.buffer
            val bodyString = buffer.clone().readUtf8()
            bodyString.contains("EAPI:Invalid nonce", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun rebuildFormBody(originalFormBody: FormBody?, nonce: Long): FormBody {
        return originalFormBody?.let { formBody ->
            val builder = FormBody.Builder()
            var nonceReplaced = false
            for (i in 0 until formBody.size) {
                val name = formBody.encodedName(i)
                if (name == "nonce") {
                    builder.addEncoded(name, nonce.toString())
                    nonceReplaced = true
                } else {
                    builder.addEncoded(name, formBody.encodedValue(i))
                }
            }
            if (!nonceReplaced) {
                builder.addEncoded("nonce", nonce.toString())
            }
            builder.build()
        } ?: FormBody.Builder()
            .addEncoded("nonce", nonce.toString())
            .build()
    }

    private fun buildPostData(signedBody: FormBody): String {
        return buildString {
            for (i in 0 until signedBody.size) {
                if (i > 0) append("&")
                append(signedBody.encodedName(i))
                append("=")
                append(signedBody.encodedValue(i))
            }
        }
    }

    private fun generateSignature(path: String, postData: String, nonce: Long): String {
        // Kraken signature algorithm:
        // HMAC-SHA512 of (URI path + SHA256(nonce + POST data)) with API secret as key

        val sha256 = MessageDigest.getInstance("SHA-256")
        val hash = sha256.digest("$nonce$postData".toByteArray())

        val message = path.toByteArray() + hash

        val secretDecoded = Base64.getDecoder().decode(apiSecret)
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(secretDecoded, "HmacSHA512"))
        val signature = mac.doFinal(message)

        return Base64.getEncoder().encodeToString(signature)
    }
}
