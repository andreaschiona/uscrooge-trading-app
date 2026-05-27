package com.uscrooge.app.integration

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.uscrooge.app.BuildConfig
import com.uscrooge.app.security.ApiSecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class GitHubIssueReporter(
    private val appContext: Context,
    private val gson: Gson = Gson()
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val securityManager = ApiSecurityManager()
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "GitHubIssueReporter"
        private const val API_BASE = "https://api.github.com"
        private const val PREFS_NAME = "github_credentials"
        private const val KEY_ENCRYPTED_TOKEN = "encrypted_token"
        private const val KEY_RAW_TOKEN = "raw_token"
        private val JSON = "application/json".toMediaType()
    }

    fun configureToken(token: String) {
        if (token.isNotBlank()) {
            storeToken(token)
        }
    }

    private fun getToken(): String {
        val encrypted = prefs.getString(KEY_ENCRYPTED_TOKEN, null)
        if (encrypted != null) {
            try {
                return securityManager.decrypt(encrypted)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decrypt stored token", e)
                prefs.edit().remove(KEY_ENCRYPTED_TOKEN).apply()
            }
        }
        val raw = prefs.getString(KEY_RAW_TOKEN, null)
        if (!raw.isNullOrBlank()) {
            return raw
        }
        if (BuildConfig.GITHUB_TOKEN.isNotBlank()) {
            storeToken(BuildConfig.GITHUB_TOKEN)
            return BuildConfig.GITHUB_TOKEN
        }
        return ""
    }

    private fun storeToken(token: String) {
        prefs.edit().putString(KEY_RAW_TOKEN, token).apply()
        try {
            val encrypted = securityManager.encrypt(token)
            prefs.edit().putString(KEY_ENCRYPTED_TOKEN, encrypted).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt and store token (raw fallback saved)", e)
        }
    }

    private val repo: String get() = BuildConfig.GITHUB_REPO

    fun isConfigured(): Boolean {
        val hasToken = getToken().isNotBlank()
        val hasRepo = repo.isNotBlank() && repo != "unknown/repo"
        if (!hasToken) {
            val hasBuildConfig = BuildConfig.GITHUB_TOKEN.isNotBlank()
            val hasRawFallback = !prefs.getString(KEY_RAW_TOKEN, null).isNullOrBlank()
            val hasEncrypted = prefs.getString(KEY_ENCRYPTED_TOKEN, null) != null
            Log.w(TAG, "GitHub token not available (BuildConfig=$hasBuildConfig, rawFallback=$hasRawFallback, encrypted=$hasEncrypted)")
        }
        if (!hasRepo) Log.w(TAG, "GitHub repo not set (GITHUB_REPOSITORY env missing)")
        return hasToken && hasRepo
    }

    suspend fun reportError(
        title: String,
        body: String,
        labels: List<String> = listOf("bug", "auto-reported")
    ): Result<Int> {
        if (!isConfigured()) {
            Log.e(TAG, "Cannot report error to GitHub: reporter not configured")
            return Result.failure(IllegalStateException("GitHub not configured"))
        }

        val token = getToken()
        val currentRepo = repo

        return withContext(Dispatchers.IO) {
            try {
                val existing = findExistingIssue(token, currentRepo, title)
                if (existing != null) {
                    Log.i(TAG, "Open issue already exists: #${existing.number} - $title")
                    return@withContext Result.success(existing.number)
                }

                val number = createIssue(token, currentRepo, title, body, labels)
                Log.i(TAG, "Created GitHub issue #$number: $title")
                Result.success(number)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report error to GitHub", e)
                Result.failure(e)
            }
        }
    }

    private fun findExistingIssue(token: String, repo: String, title: String): GitHubIssue? {
        val query = "repo:$repo+is:issue+is:open+${URLEncoder.encode(title, "UTF-8")}"
        val request = Request.Builder()
            .url("$API_BASE/search/issues?q=$query&per_page=5")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val json = response.body?.string() ?: return null
        val searchResult = gson.fromJson(json, GitHubSearchResult::class.java)
        return searchResult?.items?.firstOrNull { issue ->
            issue.title.equals(title, ignoreCase = true)
        }
    }

    private fun createIssue(token: String, repo: String, title: String, body: String, labels: List<String>): Int {
        val (owner, repoName) = parseRepo(repo)
        val requestBody = CreateIssueRequest(title = title, body = body, labels = labels)
        val jsonBody = gson.toJson(requestBody)

        val request = Request.Builder()
            .url("$API_BASE/repos/$owner/$repoName/issues")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github.v3+json")
            .post(jsonBody.toRequestBody(JSON))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("GitHub API error: ${response.code} ${response.message}")
        }

        val json = response.body?.string() ?: throw Exception("Empty response")
        val created = gson.fromJson(json, GitHubIssue::class.java)
        return created.number
    }

    private fun parseRepo(repo: String): Pair<String, String> {
        val parts = repo.split("/", limit = 2)
        if (parts.size != 2) throw IllegalArgumentException("Invalid repo format: owner/repo")
        return parts[0] to parts[1]
    }

    data class CreateIssueRequest(
        val title: String,
        val body: String,
        val labels: List<String>
    )

    data class GitHubIssue(
        val number: Int,
        val title: String,
        val state: String,
        @SerializedName("html_url") val htmlUrl: String?
    )

    data class GitHubSearchResult(
        @SerializedName("total_count") val totalCount: Int,
        val items: List<GitHubIssue>?
    )
}
