package com.uscrooge.app.data.api

import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Response
import java.security.MessageDigest
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class KrakenAuthInterceptor(
    private val apiKey: String,
    private val apiSecret: String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Only add auth to private endpoints
        if (!originalRequest.url.encodedPath.contains("/private/")) {
            return chain.proceed(originalRequest)
        }

        val path = originalRequest.url.encodedPath

        val originalFormBody = originalRequest.body as? FormBody
        val nonceFromBody = originalFormBody?.let { formBody ->
            var value: Long? = null
            for (i in 0 until formBody.size) {
                if (formBody.encodedName(i) == "nonce") {
                    value = formBody.encodedValue(i).toLongOrNull()
                    break
                }
            }
            value
        }
        val nonce = nonceFromBody ?: (System.currentTimeMillis() * 1000)

        val signedBody: FormBody? = originalFormBody?.let { formBody ->
            if (nonceFromBody != null) {
                formBody
            } else {
                FormBody.Builder().apply {
                    for (i in 0 until formBody.size) {
                        addEncoded(formBody.encodedName(i), formBody.encodedValue(i))
                    }
                    addEncoded("nonce", nonce.toString())
                }.build()
            }
        }

        val postData = signedBody?.let { formBody ->
            buildString {
                for (i in 0 until formBody.size) {
                    if (i > 0) append("&")
                    append(formBody.encodedName(i))
                    append("=")
                    append(formBody.encodedValue(i))
                }
            }
        } ?: "nonce=$nonce"

        val signature = generateSignature(path, postData, nonce)

        val requestBuilder = originalRequest.newBuilder()
        if (signedBody != null && signedBody !== originalFormBody) {
            requestBuilder.method(originalRequest.method, signedBody)
        }

        val newRequest = requestBuilder
            .header("API-Key", apiKey)
            .header("API-Sign", signature)
            .build()

        return chain.proceed(newRequest)
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
