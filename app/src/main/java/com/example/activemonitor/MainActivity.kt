package com.example.activemonitor

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.delay
import kotlin.random.Random


// 1. Theme/Color Setup

private val DarkGreenPrimary = Color(0xFF1B5E20)
private val DarkGreenOnPrimary = Color.White
private val AppBackground = Color.Black
private val AppSurface = Color(0xFF2E2E2E)
private val OnSurfaceColor = Color.White

private val CustomDarkColorScheme = darkColorScheme(
    primary = DarkGreenPrimary,
    onPrimary = DarkGreenOnPrimary,
    secondary = Color(0xFF388E3C),
    onSecondary = Color.White,
    background = AppBackground,
    onBackground = Color.White,
    surface = AppSurface,
    onSurface = OnSurfaceColor
)

// 1a) larger Typography
private val LargerTypography = Typography(
    // Headline for large text on the home screen
    headlineSmall = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold
    ),
    // Title for app bars or primary headings
    titleLarge = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold
    ),
    // Body text large
    bodyLarge = TextStyle(
        fontSize = 20.sp
    ),
    // Body text medium
    bodyMedium = TextStyle(
        fontSize = 18.sp
    ),
)

// If the machine is OFF, we color numeric stats in a darker yellow
private val InactiveYellow = Color(0xFFDAA520)

// We'll consider +15 above the safe range to be "slightly unsafe" (orange)
private const val SAFE_TEMP = 100
private const val ORANGE_TEMP = SAFE_TEMP + 15

private const val SAFE_SPEED = 1500
private const val ORANGE_SPEED = SAFE_SPEED + 15

// We'll keep track of which machines appear in the drop-down menu
val machinesInMenu = mutableStateListOf("id01", "id02")

@Composable
fun ActiveMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CustomDarkColorScheme,
        typography = LargerTypography,  // Apply our bigger text style
        content = content
    )
}


// 2. Data Structures

enum class MachineType { CONVEYOR, BOILER }

data class MachineInfo(
    val machineId: String,
    val type: MachineType,
    val name: String,
    val location: String,
    var status: String,    // "ON"/"OFF"

    // For a conveyor, interpret speedOrFlow as "Speed" (RPM).
    // For a boiler, interpret speedOrFlow as "Fuel Flow" (L/h).
    var temperature: Int,
    var speedOrFlow: Int,

    // Additional stats for conveyors
    var loadCapacity: Int?,
    var beltTension: Int?,
    var vibration: Double?,
    var operatingTime: Int?,

    // Additional stats for boilers
    var pressure: Int?,
    var waterLevel: Int?,
    var heatOutput: Int?,
    var co2Emission: Int?,
    var maintenanceCycles: String?,
)

// We'll create a map of MachineInfo for each id
val machineDb = mapOf(
    // id01 => Conveyor Belt (Dynamic)
    "id01" to MachineInfo(
        machineId       = "id01",
        type            = MachineType.CONVEYOR,
        name            = "Conveyor Belt #id01",
        location        = "Factory A",
        status          = "ON",
        temperature     = 75,
        speedOrFlow     = 1200,
        loadCapacity    = 50,
        beltTension     = 250,
        vibration       = 1.2,
        operatingTime   = 120,
        pressure        = null,
        waterLevel      = null,
        heatOutput      = null,
        co2Emission     = null,
        maintenanceCycles = null
    ),
    // id02 => Conveyor Belt (Static, all zero)
    "id02" to MachineInfo(
        machineId       = "id02",
        type            = MachineType.CONVEYOR,
        name            = "Conveyor Belt #id02",
        location        = "Factory A",
        status          = "OFF",
        temperature     = 0,
        speedOrFlow     = 0,
        loadCapacity    = 0,
        beltTension     = 0,
        vibration       = 0.0,
        operatingTime   = 0,
        pressure        = null,
        waterLevel      = null,
        heatOutput      = null,
        co2Emission     = null,
        maintenanceCycles = null
    ),
    // id03 => Industrial Boiler (Dynamic)
    "id03" to MachineInfo(
        machineId       = "id03",
        type            = MachineType.BOILER,
        name            = "Industrial Boiler #id03",
        location        = "Factory A",
        status          = "ON",
        temperature     = 100,
        speedOrFlow     = 10,  // Fuel Flow
        loadCapacity    = null,
        beltTension     = null,
        vibration       = null,
        operatingTime   = null,
        pressure        = 5,
        waterLevel      = 85,
        heatOutput      = 600,
        co2Emission     = 220,
        maintenanceCycles = "Last done 20 hours ago"
    )
)


// 3. Main Activity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ActiveMonitorTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        ButtonExample(navController = navController)
                    }
                    composable(
                        route = "machinery/{machineId}",
                        arguments = listOf(navArgument("machineId") { type = NavType.StringType })
                    ) { navBackStackEntry ->
                        val machineId = navBackStackEntry.arguments?.getString("machineId") ?: ""
                        MachineryDetailsScreen(navController, machineId)
                    }
                }
            }
        }
    }
}


// 4. HOME SCREEN

@Composable
fun ButtonExample(navController: NavHostController) {
    var text by remember { mutableStateOf("Tap to Scan your machine!") }
    var scanResult by remember { mutableStateOf("") }

    val context = LocalContext.current

    val qrScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            val scan = IntentIntegrator.parseActivityResult(result.resultCode, result.data)
            if (scan != null && scan.contents != null) {
                scanResult = scan.contents
                // Navigate to the machinery screen with the scanned ID
                navController.navigate("machinery/${scan.contents}")
            }
        }
    )

    fun startQRScan(activity: Activity) {
        val integrator = IntentIntegrator(activity)
        integrator.setOrientationLocked(false)
        integrator.setBeepEnabled(false)
        val scanIntent = integrator.createScanIntent()
        qrScannerLauncher.launch(scanIntent)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                startQRScan(context as Activity)
            }
        }
    )

    Scaffold(
        topBar = {
            SmallTopAppBarWithMenu(
                title = "Active Monitor",
                showBackButton = true,
                onBackClick = { /* Usually no-op on home */ },
                onSelectMachine = { selectedMachineId ->
                    navController.navigate("machinery/$selectedMachineId")
                }
            )
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.headlineSmall, // bigger text
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(24.dp)) // bigger spacer

                if (scanResult.isNotEmpty()) {
                    Text(
                        "QR Code: $scanResult",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED
                        ) {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        } else {
                            startQRScan(context as Activity)
                        }
                    },
                    modifier = Modifier.padding(12.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Scan QR", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}


// 5. MACHINE SCREEN

@Composable
fun MachineryDetailsScreen(navController: NavHostController, machineId: String) {
    // We'll see if it's in the machineDb
    val initialInfo = machineDb[machineId]

    // We use a state to show an error dialog if the machine is not found
    var showErrorDialog by remember { mutableStateOf(false) }

    if (initialInfo == null) {
        // If not found in DB => Show an error dialog
        showErrorDialog = true
    }

    // The "ERROR" dialog
    if (showErrorDialog) {
        ErrorQrDialog(
            onClose = {
                // When the user closes, pop back to the home (or simply close the dialog).
                // We'll just pop the backstack to go home:
                navController.navigateUp()
            }
        )
    }

    // If found => show the real screen
    if (initialInfo != null) {
        var machineInfo by remember { mutableStateOf(initialInfo) }

        Scaffold(
            topBar = {
                SmallTopAppBarWithMenu(
                    title = "Active Monitor",
                    showBackButton = true,
                    onBackClick = { navController.navigateUp() },
                    onSelectMachine = { selectedMachineId ->
                        navController.navigate("machinery/$selectedMachineId")
                    }
                )
            }
        ) { paddingValues ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                color = MaterialTheme.colorScheme.background
            ) {
                when (machineInfo.type) {
                    MachineType.CONVEYOR -> ConveyorDisplay(machineInfo) { machineInfo = it }
                    MachineType.BOILER   -> BoilerDisplay(machineInfo)   { machineInfo = it }
                }
            }
        }
    }
}

// A simple composable that shows a dialog with "ERROR! QR code NOT VALID" and a close button
@Composable
fun ErrorQrDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text("ERROR!",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Text("QR code is NOT VALID",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        confirmButton = {
            Button(onClick = onClose) {
                Text("Close", style = MaterialTheme.typography.bodyLarge)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp)
    )
}


// 6. CONVEYOR DISPLAY (id01 dynamic, id02 static)

@Composable
fun ConveyorDisplay(
    info: MachineInfo,
    onMachineInfoUpdate: (MachineInfo) -> Unit
) {
    val isDynamic = (info.machineId == "id01")

    var tempSpiked by remember { mutableStateOf(false) }
    val eventLog = remember { mutableStateListOf<String>().apply { addAll(generateFakeEventLog(info.machineId)) } }
    var showEventLog by remember { mutableStateOf(false) }
    var showHighTempDialog by remember { mutableStateOf(false) }
    var emergencyStopTriggered by remember { mutableStateOf(false) }


    LaunchedEffect(isDynamic, emergencyStopTriggered, info.status, tempSpiked) {
        if (isDynamic && !emergencyStopTriggered && info.status == "ON") {
            while (true) {
                delay(2000)
                if (emergencyStopTriggered || info.status != "ON") break

                val updated = info.copy(
                    temperature = if (tempSpiked) info.temperature
                    else (info.temperature + (-2..2).random()).coerceIn(0,120),
                    speedOrFlow = (info.speedOrFlow + (-10..10).random()).coerceIn(0,2000)
                ).also { newInfo ->
                    newInfo.loadCapacity?.let { oldLoad ->
                        newInfo.loadCapacity = (oldLoad + (-5..5).random()).coerceIn(0,100)
                    }
                    newInfo.beltTension?.let { oldTension ->
                        newInfo.beltTension = (oldTension + (-20..20).random()).coerceIn(0,400)
                    }
                    newInfo.vibration?.let { oldVib ->
                        newInfo.vibration = (oldVib + Random.nextDouble(-0.5,0.5)).coerceIn(0.0,3.0)
                    }
                    newInfo.operatingTime?.let { oldTime ->
                        newInfo.operatingTime = oldTime + 2
                    }
                }
                onMachineInfoUpdate(updated)
            }
        }
    }

    LaunchedEffect(isDynamic) {
        if (isDynamic && info.status == "ON") {
            delay(10_000)
            if (!emergencyStopTriggered && info.status == "ON") {
                val updated = info.copy(temperature = 200)
                onMachineInfoUpdate(updated)
                tempSpiked = true
                eventLog.add("High temperature spike observed (machine ${info.machineId})")
                showHighTempDialog = true
            }
        }
    }

    LaunchedEffect(emergencyStopTriggered) {
        if (emergencyStopTriggered) {
            eventLog.add("Emergency Stop triggered (machine ${info.machineId})")

            var localMachine = info
            while (
                localMachine.speedOrFlow > 0 ||
                localMachine.temperature > 0 ||
                (localMachine.loadCapacity ?: 0) > 0 ||
                (localMachine.beltTension ?: 0) > 0 ||
                (localMachine.vibration ?: 0.0) > 0.0
            ) {
                delay(200)
                val updated = localMachine.copy(
                    speedOrFlow  = (localMachine.speedOrFlow - 50).coerceAtLeast(0),
                    temperature  = (localMachine.temperature - 2).coerceAtLeast(0),
                    loadCapacity = localMachine.loadCapacity?.let { cap -> (cap - 5).coerceAtLeast(0) },
                    beltTension  = localMachine.beltTension?.let { tens -> (tens - 20).coerceAtLeast(0) },
                    vibration    = localMachine.vibration?.let { vib -> (vib - 0.5).coerceAtLeast(0.0) }
                )
                onMachineInfoUpdate(updated)
                localMachine = updated
            }
            val finalCopy = localMachine.copy(status = "OFF")
            onMachineInfoUpdate(finalCopy)
        }
    }

    val buttonText = if (!emergencyStopTriggered) "EMERGENCY STOP" else "Machine Stopped"
    val statusColor = if (info.status == "ON") Color.Green else Color.Red
    val tempColor = if (info.status == "OFF") InactiveYellow else getColorForValue(info.temperature, SAFE_TEMP, ORANGE_TEMP)
    val speedColor = if (info.status == "OFF") InactiveYellow else getColorForValue(info.speedOrFlow, SAFE_SPEED, ORANGE_SPEED)

    val (buttonContainerColor, buttonContentColor) = if (info.status == "OFF") {
        Color.Gray to Color.Black
    } else {
        Color.Red to Color.White
    }

    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        elevation = CardDefaults.cardElevation(8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.SpaceAround,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Machine Type: Conveyor Belt System",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text("Location: ${info.location}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.3f), thickness = 2.dp)

            Text("Name: ${info.name}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text("Status: ${info.status}",
                style = MaterialTheme.typography.bodyLarge,
                color = statusColor
            )
            Text("Temperature: ${info.temperature}°C",
                style = MaterialTheme.typography.bodyLarge,
                color = tempColor
            )
            Text("Speed: ${info.speedOrFlow} RPM",
                style = MaterialTheme.typography.bodyLarge,
                color = speedColor
            )

            if (info.loadCapacity != null) {
                Text("Load Capacity: ${info.loadCapacity}% load",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (info.status=="OFF") InactiveYellow else Color.Green
                )
            }
            if (info.beltTension != null) {
                Text("Belt Tension: ${info.beltTension}N",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (info.status=="OFF") InactiveYellow else Color.Green
                )
            }
            if (info.vibration != null) {
                Text(String.format("Vibration: %.1fg", info.vibration),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (info.status=="OFF") InactiveYellow else Color.Green
                )
            }
            if (info.operatingTime != null) {
                Text("Operating Time: ${info.operatingTime} hours",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (info.status=="OFF") InactiveYellow else Color.Green
                )
            }

            Button(onClick = { showEventLog = true }) {
                Text("Show Event Log", style = MaterialTheme.typography.bodyLarge)
            }

            Button(
                onClick = {
                    if (info.status == "ON" && !emergencyStopTriggered) {
                        emergencyStopTriggered = true
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonContainerColor,
                    contentColor   = buttonContentColor
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(buttonText, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    if (showEventLog) {
        EventLogDialog(onDismiss = { showEventLog = false }, eventLog = eventLog)
    }
    // High temperature warning
    if (showHighTempDialog && !emergencyStopTriggered) {
        AlertDialog(
            onDismissRequest = { showHighTempDialog = false },
            title = {
                Text("High Temperature Warning",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text("Temperature for machine ${info.machineId} too high, emergency stop advised.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                Button(onClick = { showHighTempDialog = false }) {
                    Text("OK", style = MaterialTheme.typography.bodyLarge)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}


// 7. BOILER DISPLAY (Dynamic for id03)

@Composable
fun BoilerDisplay(
    info: MachineInfo,
    onMachineInfoUpdate: (MachineInfo) -> Unit
) {
    val isDynamic = (info.machineId == "id03")
    val eventLog = remember { mutableStateListOf<String>().apply { addAll(generateFakeEventLog(info.machineId)) } }
    var showEventLog by remember { mutableStateOf(false) }
    var emergencyStopTriggered by remember { mutableStateOf(false) }

    val isInMenu = remember { mutableStateOf(machinesInMenu.contains("id03")) }

    LaunchedEffect(isDynamic, emergencyStopTriggered, info.status) {
        if (isDynamic && !emergencyStopTriggered && info.status == "ON") {
            while (true) {
                delay(2000)
                if (emergencyStopTriggered || info.status != "ON") break

                val updated = info.copy(
                    temperature = (info.temperature + (-2..2).random()).coerceIn(0, 200),
                    speedOrFlow = (info.speedOrFlow + (-1..1).random()).coerceIn(0, 50),
                ).also { newInfo ->
                    newInfo.pressure?.let { oldP ->
                        newInfo.pressure = (oldP + (-1..1).random()).coerceIn(0, 20)
                    }
                    newInfo.waterLevel?.let { oldW ->
                        newInfo.waterLevel = (oldW + (-2..2).random()).coerceIn(0, 100)
                    }
                    newInfo.heatOutput?.let { oldH ->
                        newInfo.heatOutput = (oldH + (-10..10).random()).coerceIn(0, 1000)
                    }
                    newInfo.co2Emission?.let { oldC ->
                        newInfo.co2Emission = (oldC + (-10..10).random()).coerceIn(0, 500)
                    }
                    newInfo.maintenanceCycles?.let {
                        val hrs = (10..30).random()
                        newInfo.maintenanceCycles = "Last done $hrs hours ago"
                    }
                }
                onMachineInfoUpdate(updated)
            }
        }
    }

    LaunchedEffect(emergencyStopTriggered) {
        if (emergencyStopTriggered) {
            eventLog.add("Emergency Stop triggered (machine ${info.machineId})")

            var localMachine = info
            while (
                localMachine.speedOrFlow > 0 ||
                localMachine.temperature > 0 ||
                (localMachine.pressure ?: 0) > 0 ||
                (localMachine.waterLevel ?: 0) > 0 ||
                (localMachine.heatOutput ?: 0) > 0 ||
                (localMachine.co2Emission ?: 0) > 0
            ) {
                delay(200)
                val updated = localMachine.copy(
                    speedOrFlow  = (localMachine.speedOrFlow - 1).coerceAtLeast(0),
                    temperature  = (localMachine.temperature - 2).coerceAtLeast(0),
                    pressure     = localMachine.pressure?.let { p -> (p - 1).coerceAtLeast(0) },
                    waterLevel   = localMachine.waterLevel?.let { w -> (w - 2).coerceAtLeast(0) },
                    heatOutput   = localMachine.heatOutput?.let { h -> (h - 10).coerceAtLeast(0) },
                    co2Emission  = localMachine.co2Emission?.let { c -> (c - 10).coerceAtLeast(0) }
                )
                onMachineInfoUpdate(updated)
                localMachine = updated
            }
            val finalCopy = localMachine.copy(status = "OFF")
            onMachineInfoUpdate(finalCopy)
        }
    }

    val buttonText = if (!emergencyStopTriggered) "EMERGENCY STOP" else "Machine Stopped"
    val statusColor = if (info.status == "ON") Color.Green else Color.Red
    val tempColor = if (info.status == "OFF") InactiveYellow else getColorForValue(info.temperature, SAFE_TEMP, ORANGE_TEMP)
    val flowColor = if (info.status == "OFF") InactiveYellow else Color.Green

    val (buttonContainerColor, buttonContentColor) = if (info.status == "OFF") {
        Color.Gray to Color.Black
    } else {
        Color.Red to Color.White
    }

    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        elevation = CardDefaults.cardElevation(8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.SpaceAround,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Machine Type: Industrial Boiler",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text("Location: ${info.location}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.3f), thickness = 2.dp)

            Text("Name: ${info.name}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text("Status: ${info.status}",
                style = MaterialTheme.typography.bodyLarge,
                color = statusColor
            )
            Text("Temperature: ${info.temperature}°C",
                style = MaterialTheme.typography.bodyLarge,
                color = tempColor
            )
            Text("Fuel Flow: ${info.speedOrFlow} L/h",
                style = MaterialTheme.typography.bodyLarge,
                color = flowColor
            )

            info.pressure?.let { pVal ->
                Text("Pressure: ${pVal} bar",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (info.status=="OFF") InactiveYellow else Color.Green
                )
            }
            info.waterLevel?.let { wVal ->
                Text("Water Level: ${wVal}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (info.status=="OFF") InactiveYellow else Color.Green
                )
            }
            info.heatOutput?.let { hVal ->
                Text("Heat Output: ${hVal} kW",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (info.status=="OFF") InactiveYellow else Color.Green
                )
            }
            info.co2Emission?.let { cVal ->
                Text("CO2 Emission: ${cVal} ppm",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (info.status=="OFF") InactiveYellow else Color.Green
                )
            }
            info.maintenanceCycles?.let { mVal ->
                Text("Maintenance Cycles: $mVal",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (info.status=="OFF") InactiveYellow else MaterialTheme.colorScheme.onSurface
                )
            }

            if (!isInMenu.value) {
                Button(onClick = {
                    machinesInMenu.add(info.machineId)
                    isInMenu.value = true
                }) {
                    Text("Add Machine to Menu", style = MaterialTheme.typography.bodyLarge)
                }
            }

            Button(onClick = { showEventLog = true }) {
                Text("Show Event Log", style = MaterialTheme.typography.bodyLarge)
            }

            Button(
                onClick = {
                    if (info.status == "ON" && !emergencyStopTriggered) {
                        emergencyStopTriggered = true
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonContainerColor,
                    contentColor   = buttonContentColor
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(buttonText, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    if (showEventLog) {
        EventLogDialog(onDismiss = { showEventLog = false }, eventLog = eventLog)
    }
}


// 9. EVENT LOG DIALOG

@Composable
fun EventLogDialog(onDismiss: () -> Unit, eventLog: List<String>) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Event Log",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                eventLog.forEach { entry ->
                    Text("• $entry",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close", style = MaterialTheme.typography.bodyLarge)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp)
    )
}


// 10. Color Helper

fun getColorForValue(value: Int, safeLimit: Int, orangeLimit: Int): Color {
    return when {
        value < safeLimit    -> Color.Green
        value <= orangeLimit -> Color(0xFFFF9800)
        else                 -> Color.Red
    }
}


// 11. Fake Event Logs

fun generateFakeEventLog(machineId: String): List<String> {
    val possibleEvents = listOf(
        "Starting working date",
        "Hours since start",
        "Maintenance scheduled",
        "Random malfunction",
        "Machine turned ON",
        "Machine turned OFF",
        "Operator login",
        "Operator logout",
        "Temperature exceeded threshold",
        "Speed exceeding recommended range"
    )
    return List(10) {
        val randomIndex = Random.nextInt(possibleEvents.size)
        "${possibleEvents[randomIndex]} (machine $machineId)"
    }
}


// 12. TOP APP BAR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmallTopAppBarWithMenu(
    title: String,
    showBackButton: Boolean,
    onBackClick: () -> Unit,
    onSelectMachine: (String) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val navigationIcon: @Composable (() -> Unit)? = if (showBackButton) {
        {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Go back", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    } else null

    if (navigationIcon != null) {
        SmallTopAppBar(
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            },
            navigationIcon = navigationIcon,
            actions = {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = MaterialTheme.colorScheme.onPrimary)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    machinesInMenu.forEach { machineId ->
                        DropdownMenuItem(
                            text = { Text(machineId, style = MaterialTheme.typography.bodyLarge) },
                            onClick = {
                                menuExpanded = false
                                onSelectMachine(machineId)
                            }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.smallTopAppBarColors(
                containerColor = DarkGreenPrimary,
                titleContentColor = DarkGreenOnPrimary
            )
        )
    }
}
