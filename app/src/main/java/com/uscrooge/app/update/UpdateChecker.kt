package com.uscrooge.app.update

import com.google.gson.annotations.SerializedName
import com.uscrooge.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String,
    val name: String,
    val assets: List<GitHubAsset>,
    val body: String?
)

data class GitHubAsset(
    val name: String,
    @SerializedName("browser_download_url") val browserDownloadUrl: String
)

data class UpdateCheckResult(
    val isUpdateAvailable: Boolean,
    val latestVersion: String = "",
    val downloadUrl: String = "",
    val releaseNotes: String = ""
)

class UpdateChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun checkForUpdate(): Result<UpdateCheckResult> {
        return try {
            val repo = BuildConfig.GITHUB_REPO
            if (repo.isBlank() || repo == "unknown/repo") {
                return Result.failure(IllegalStateException("GitHub repository not configured"))
            }

            val requestBuilder = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github.v3+json")

            if (BuildConfig.GITHUB_TOKEN.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer ${BuildConfig.GITHUB_TOKEN}")
            }

            val request = requestBuilder.build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return Result.failure(Exception("GitHub API error: ${response.code}"))
            }

            val json = response.body?.string() ?: return Result.failure(Exception("Empty response"))
            val release = com.google.gson.Gson().fromJson(json, GitHubRelease::class.java)

            val currentVersion = BuildConfig.VERSION_NAME
            val latestVersion = release.tagName.removePrefix("v")

            if (compareVersions(latestVersion, currentVersion) > 0) {
                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                Result.success(
                    UpdateCheckResult(
                        isUpdateAvailable = true,
                        latestVersion = release.tagName,
                        downloadUrl = apkAsset?.browserDownloadUrl ?: "",
                        releaseNotes = release.body ?: ""
                    )
                )
            } else {
                Result.success(UpdateCheckResult(isUpdateAvailable = false))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        return 0
    }
}
