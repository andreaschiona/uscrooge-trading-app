package com.uscrooge.app.ui.screen

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uscrooge.app.data.model.TradingConfig
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.integration.GitHubIssueReporter
import com.uscrooge.app.ui.viewmodel.SettingsViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: android.content.Context = mockk()
    private val configRepository: ConfigRepository = mockk(relaxed = true)
    private val gitHubIssueReporter: GitHubIssueReporter = mockk(relaxed = true)

    @Before
    fun setup() {
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
    }

    @Test
    fun settingsTitleIsDisplayed() {
        val viewModel = SettingsViewModel(context, configRepository, gitHubIssueReporter)
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            SettingsScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Settings").assertExists()
    }

    @Test
    fun saveButtonIsDisplayed() {
        val viewModel = SettingsViewModel(context, configRepository, gitHubIssueReporter)
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            SettingsScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Save").assertExists()
    }

    @Test
    fun resetButtonIsDisplayed() {
        val viewModel = SettingsViewModel(context, configRepository, gitHubIssueReporter)
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            SettingsScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Reset").assertExists()
    }

    @Test
    fun riskManagementSectionIsDisplayed() {
        val viewModel = SettingsViewModel(context, configRepository, gitHubIssueReporter)
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            SettingsScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Risk Management").assertExists()
    }

    @Test
    fun strategyParametersSectionIsDisplayed() {
        val viewModel = SettingsViewModel(context, configRepository, gitHubIssueReporter)
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            SettingsScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Strategy Parameters").assertExists()
    }

    @Test
    fun executionSectionIsDisplayed() {
        val viewModel = SettingsViewModel(context, configRepository, gitHubIssueReporter)
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            SettingsScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Execution").assertExists()
    }

    @Test
    fun technicalAnalysisSectionIsDisplayed() {
        val viewModel = SettingsViewModel(context, configRepository, gitHubIssueReporter)
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            SettingsScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Technical Analysis").assertExists()
    }

    @Test
    fun krakenApiSectionIsDisplayed() {
        val viewModel = SettingsViewModel(context, configRepository, gitHubIssueReporter)
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            SettingsScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Kraken API (Crypto)").assertExists()
    }

    @Test
    fun alpacaApiSectionIsDisplayed() {
        val viewModel = SettingsViewModel(context, configRepository, gitHubIssueReporter)
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            SettingsScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Alpaca API (Stocks)").assertExists()
    }

    @Test
    fun darkModeSwitchIsDisplayed() {
        val viewModel = SettingsViewModel(context, configRepository, gitHubIssueReporter)
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            SettingsScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Dark Mode").assertExists()
    }
}
