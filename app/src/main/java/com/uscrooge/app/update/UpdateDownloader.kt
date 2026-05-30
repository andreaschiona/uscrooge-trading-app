package com.uscrooge.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class UpdateDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "UpdateDownloader"
        private const val APK_DIR = "updates"
        private const val APK_FILENAME = "app-release.apk"
    }

    fun downloadAndInstall(downloadUrl: String): Result<Uri> {
        return try {
            val request = Request.Builder()
                .url(downloadUrl)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return Result.failure(Exception("Download failed: ${response.code}"))
            }

            val dir = File(context.filesDir, APK_DIR)
            dir.mkdirs()
            val apkFile = File(dir, APK_FILENAME)

            response.body?.byteStream()?.use { input ->
                FileOutputStream(apkFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return Result.failure(Exception("Empty response body"))

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            Result.success(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Download/install failed", e)
            Result.failure(e)
        }
    }
}
