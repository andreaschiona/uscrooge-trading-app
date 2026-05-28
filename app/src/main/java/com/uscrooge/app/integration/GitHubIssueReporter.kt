package com.uscrooge.app.integration

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.uscrooge.app.BuildConfig
import com.uscrooge.app.data.model.Position
import com.uscrooge.app.data.model.TradingConfig
import com.uscrooge.app.data.model.TradingSignal
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
    ): Result<String> {
        if (!isConfigured()) {
            Log.e(TAG, "Cannot report error to GitHub: reporter not configured")
            return Result.failure(IllegalStateException("GitHub issue reporter is not configured. Please check your GitHub token and repository settings."))
        }

        val token = getToken()
        val currentRepo = repo

        return withContext(Dispatchers.IO) {
            try {
                val existing = findExistingIssue(token, currentRepo, title)
                if (existing != null) {
                    Log.i(TAG, "Open issue already exists: #${existing.number} - $title")
                    return@withContext Result.success("Issue already exists: #${existing.number} - $title")
                }

                val number = createIssue(token, currentRepo, title, body, labels)
                Log.i(TAG, "Created GitHub issue #$number: $title")
                Result.success("Created GitHub issue #$number: $title")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report error to GitHub", e)
                Result.failure(Exception("Failed to report issue: ${e.message ?: "Unknown error"}", e))
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
        if (!response.isSuccessful) {
            val errBody = response.body?.string() ?: "No body"
            throw Exception("GitHub search API error: ${response.code} - $errBody")
        }

        val json = response.body?.string() ?: throw Exception("Empty response from search API")
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

    suspend fun reportPositionFeedback(
        position: Position,
        openingSignal: TradingSignal?,
        exitReason: String?,
        config: TradingConfig
    ): Result<String> {
        val holdingPeriod = position.closedAt?.minus(position.openedAt)
        val holdingPeriodStr = if (holdingPeriod != null) {
            val hours = holdingPeriod / 3_600_000
            val minutes = (holdingPeriod % 3_600_000) / 60_000
            "${hours}h ${minutes}m"
        } else {
            "N/A"
        }

        val title = "[Position Analysis] ${position.pair} closed"
        val body = buildString {
            appendLine("## Position Analysis Request: ${position.pair}")
            appendLine()
            appendLine("### Position Details")
            appendLine("- **Pair:** ${position.pair}")
            appendLine("- **Broker:** ${position.broker}")
            appendLine("- **Opened:** ${formatTimestamp(position.openedAt)}")
            appendLine("- **Closed:** ${formatTimestamp(position.closedAt ?: 0)}")
            appendLine("- **Holding Period:** $holdingPeriodStr")
            appendLine("- **Entry Price:** ${formatPrice(position.averageEntryPrice)}")
            appendLine("- **Exit Price:** ${formatPrice(position.currentPrice)}")
            appendLine("- **Realized PnL:** ${formatPnL(position.realizedPnL)}")
            appendLine()
            appendLine("### Opening Signal")
            if (openingSignal != null) {
                appendLine("- **Signal Strength:** ${String.format("%.2f", openingSignal.strength)}")
                appendLine("- **Take Profit:** ${formatPrice(openingSignal.takeProfit)}")
                appendLine("- **Stop Loss:** ${formatPrice(openingSignal.stopLoss)}")
                appendLine("- **Risk/Reward:** ${String.format("%.2f", openingSignal.riskRewardRatio)}")
                appendLine("- **Reasons:** ${openingSignal.getReasonsList().joinToString(", ")}")
            } else {
                appendLine("- *Signal data not available*")
            }
            appendLine()
            appendLine("### Exit Reason")
            appendLine(exitReason ?: "Manual or sync-triggered close")
            appendLine()
            appendLine("### Current Configuration")
            appendLine("- **Stop Loss:** ${config.stopLossPercent}%")
            appendLine("- **Take Profit:** ${config.takeProfitPercent}%")
            appendLine("- **Trailing Stop:** ${config.trailingStopPercent}%")
            appendLine("- **Risk Per Trade:** ${String.format("%.0f", config.riskPerTrade * 100)}%")
            appendLine("- **Max Open Positions:** ${config.maxOpenPositions}")
            appendLine("- **Max Daily Trades:** ${config.maxDailyTrades}")
            appendLine("- **Min Signal Strength:** ${config.minSignalStrength}")
            appendLine("- **Strong Signal Threshold:** ${config.strongSignalThreshold}")
            appendLine("- **Circuit Breaker Enabled:** ${config.circuitBreakerEnabled}")
            appendLine("- **Max Daily Drawdown:** ${config.maxDailyDrawdownPercent}%")
            appendLine("- **Trading Pairs:** ${config.tradingPairs.joinToString(", ")}")
            appendLine("- **Automatic Trading:** ${config.automaticTrading}")
            appendLine("- **Use Limit Orders:** ${config.useLimitOrders}")
            appendLine("- **Check Interval:** ${config.checkIntervalSeconds}s")
            appendLine()
            appendLine("### App Version")
            appendLine("- **Version:** ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("_This issue was automatically created when the position was closed._")
            appendLine("_Please analyze the trade outcome and evaluate if the current strategy is performing as expected._")
        }

        return reportError(title, body, labels = listOf("enhancement", "position-analysis", "auto-reported"))
    }

    private fun formatTimestamp(millis: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return sdf.format(java.util.Date(millis))
    }

    private fun formatPrice(price: Double): String {
        return String.format("%.2f", price)
    }

    private fun formatPnL(pnl: Double?): String {
        if (pnl == null) return "N/A"
        val sign = if (pnl >= 0) "+" else ""
        return "$sign${String.format("%.2f", pnl)} EUR"
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
