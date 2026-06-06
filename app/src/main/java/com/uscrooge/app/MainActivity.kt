package com.uscrooge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.app.AlertDialog
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.uscrooge.app.data.model.TradingConfig
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.ui.lock.AppLockScreen
import com.uscrooge.app.ui.onboarding.OnboardingScreen
import com.uscrooge.app.ui.screen.DashboardScreen
import com.uscrooge.app.ui.screen.SettingsScreen
import com.uscrooge.app.ui.screen.SignalsScreen
import com.uscrooge.app.ui.screen.TradeJournalScreen
import com.uscrooge.app.ui.theme.UScroogeAppTheme
import com.uscrooge.app.ui.viewmodel.DashboardViewModel
import com.uscrooge.app.ui.viewmodel.OnboardingViewModel
import com.uscrooge.app.ui.viewmodel.SettingsViewModel
import com.uscrooge.app.ui.viewmodel.SignalsViewModel
import com.uscrooge.app.ui.viewmodel.TradeJournalViewModel
import com.uscrooge.app.worker.MarketAnalysisWorker
import com.uscrooge.app.worker.UpdateCheckWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

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
                checkBatteryOptimization()
            }
        } else {
            scheduleBackgroundWork()
            checkBatteryOptimization()
        }

        setContent {
            val config by configRepository.configFlow.collectAsState(initial = TradingConfig())
            var onboardingDone by remember { mutableStateOf(false) }
            val showOnboarding = !config.onboardingCompleted && !onboardingDone

            var biometricUnlocked by remember { mutableStateOf(!config.biometricEnabled) }
            var lastActivityTime by remember { mutableStateOf(System.currentTimeMillis()) }
            val inactivityTimeoutMs = 300_000L

            LaunchedEffect(config.biometricEnabled) {
                if (!config.biometricEnabled) biometricUnlocked = true
                else biometricUnlocked = false
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, config.biometricEnabled) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START && config.biometricEnabled) {
                        biometricUnlocked = false
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            LaunchedEffect(biometricUnlocked, config.biometricEnabled) {
                if (biometricUnlocked && config.biometricEnabled) {
                    while (true) {
                        delay(60_000)
                        if (System.currentTimeMillis() - lastActivityTime > inactivityTimeoutMs) {
                            biometricUnlocked = false
                        }
                    }
                }
            }

            UScroogeAppTheme(darkTheme = config.useDarkMode) {
                if (config.biometricEnabled && !biometricUnlocked) {
                    AppLockScreen(
                        onUnlocked = {
                            biometricUnlocked = true
                            lastActivityTime = System.currentTimeMillis()
                        },
                        onUnavailable = {
                            biometricUnlocked = true
                            lastActivityTime = System.currentTimeMillis()
                        }
                    )
                } else if (showOnboarding) {
                    val onboardingViewModel: OnboardingViewModel = hiltViewModel()
                    OnboardingScreen(
                        viewModel = onboardingViewModel,
                        onComplete = {
                            onboardingDone = true
                        }
                    )
                } else {
                    val navigateToSettings = remember {
                        intent?.getBooleanExtra("navigate_to_settings", false) == true
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(config.biometricEnabled) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent()
                                        lastActivityTime = System.currentTimeMillis()
                                    }
                                }
                            }
                    ) {
                        MainScreen(
                            configRepository = configRepository,
                            navigateToSettings = navigateToSettings
                        )
                    }
                }
            }
        }
    }

    private fun scheduleBackgroundWork() {
        lifecycleScope.launch {
            val config = configRepository.configFlow.first()
            val intervalMinutes = (config.checkIntervalSeconds / 60).toLong()
            MarketAnalysisWorker.schedule(this@MainActivity, intervalMinutes)

            val updateIntervalHours = config.updateCheckIntervalHours.toLong()
            UpdateCheckWorker.schedule(this@MainActivity, updateIntervalHours)
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_optimization_title)
            .setMessage(R.string.battery_optimization_message)
            .setPositiveButton(R.string.battery_optimization_allow) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton(R.string.battery_optimization_skip, null)
            .show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(configRepository: ConfigRepository, navigateToSettings: Boolean = false) {
    val navController = rememberNavController()

    LaunchedEffect(navigateToSettings) {
        if (navigateToSettings) {
            navController.navigate(Screen.Settings.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

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
