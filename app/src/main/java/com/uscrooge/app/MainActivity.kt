package com.uscrooge.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.uscrooge.app.data.model.TradingConfig
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.ui.screen.DashboardScreen
import com.uscrooge.app.ui.screen.SettingsScreen
import com.uscrooge.app.ui.screen.SignalsScreen
import com.uscrooge.app.ui.screen.TradeJournalScreen
import com.uscrooge.app.ui.theme.UScroogeAppTheme
import com.uscrooge.app.ui.viewmodel.DashboardViewModel
import com.uscrooge.app.ui.viewmodel.SettingsViewModel
import com.uscrooge.app.ui.viewmodel.SignalsViewModel
import com.uscrooge.app.ui.viewmodel.TradeJournalViewModel
import com.uscrooge.app.worker.MarketAnalysisWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var configRepository: ConfigRepository

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            scheduleBackgroundWork()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                scheduleBackgroundWork()
            }
        } else {
            scheduleBackgroundWork()
        }

        setContent {
            val config by configRepository.configFlow.collectAsState(initial = TradingConfig())
            UScroogeAppTheme(darkTheme = config.useDarkMode) {
                MainScreen(configRepository = configRepository)
            }
        }
    }

    private fun scheduleBackgroundWork() {
        lifecycleScope.launch {
            val config = configRepository.configFlow.first()
            val intervalMinutes = (config.checkIntervalSeconds / 60).toLong()
            MarketAnalysisWorker.schedule(this@MainActivity, intervalMinutes)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(configRepository: ConfigRepository) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                listOf(
                    Screen.Dashboard,
                    Screen.Signals,
                    Screen.TradeJournal,
                    Screen.Settings
                ).forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) {
                val viewModel: DashboardViewModel = hiltViewModel()
                DashboardScreen(viewModel)
            }

            composable(Screen.Signals.route) {
                val viewModel: SignalsViewModel = hiltViewModel()
                SignalsScreen(viewModel)
            }

            composable(Screen.Settings.route) {
                val viewModel: SettingsViewModel = hiltViewModel()
                SettingsScreen(viewModel)
            }

            composable(Screen.TradeJournal.route) {
                val viewModel: TradeJournalViewModel = hiltViewModel()
                TradeJournalScreen(viewModel)
            }
        }
    }
}

sealed class Screen(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Home)
    object Signals : Screen("signals", "Signals", Icons.Default.Notifications)
    object TradeJournal : Screen("trade_journal", "Journal", Icons.Default.DateRange)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}
