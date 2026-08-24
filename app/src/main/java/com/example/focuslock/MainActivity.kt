package com.example.focuslock

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ceil

data class InstalledApp(
    val name: String,
    val packageName: String,
    val icon: ImageBitmap,
    val blockCount: Int
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FocusLockScreen()
                }
            }
        }
    }
}

@Composable
fun FocusLockScreen() {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }

    LaunchedEffect(Unit) {
        installedApps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Focus Lock",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Create") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Sessions") }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Websites") }
            )
        }

        when (selectedTab) {
            0 -> CreateSessionTab(
                installedApps = installedApps,
                onSessionStarted = { selectedPackages ->
                    installedApps = installedApps.map { app ->
                        if (app.packageName in selectedPackages) {
                            app.copy(blockCount = app.blockCount + 1)
                        } else {
                            app
                        }
                    }
                    selectedTab = 1
                }
            )
            1 -> TrackingTab(installedApps)
            else -> WebsiteBlockingTab()
        }
    }
}

@Composable
private fun WebsiteBlockingTab() {
    val context = LocalContext.current
    var domainInput by remember { mutableStateOf("") }
    var blockedDomains by remember { mutableStateOf(WebsiteBlockStore.domains(context)) }
    var vpnRunning by remember { mutableStateOf(WebsiteBlockVpnService.isRunning) }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (VpnService.prepare(context) == null) startWebsiteVpn(context)
    }

    LaunchedEffect(Unit) {
        while (true) {
            vpnRunning = WebsiteBlockVpnService.isRunning
            blockedDomains = WebsiteBlockStore.domains(context)
            delay(1_000L)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Blocked websites", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = domainInput,
                onValueChange = { domainInput = it },
                label = { Text("Domain") },
                placeholder = { Text("instagram.com") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                val domain = WebsiteBlockStore.add(context, domainInput)
                if (domain == null) {
                    Toast.makeText(context, "Enter a valid domain", Toast.LENGTH_SHORT).show()
                } else {
                    domainInput = ""
                    blockedDomains = WebsiteBlockStore.domains(context)
                    if (vpnRunning) refreshWebsiteVpn(context)
                }
            }) {
                Text("Add")
            }
        }

        Text(
            "Suggested",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(WebsiteBlockStore.suggestions) { domain ->
                AssistChip(
                    onClick = {
                        WebsiteBlockStore.add(context, domain)
                        blockedDomains = WebsiteBlockStore.domains(context)
                        if (vpnRunning) refreshWebsiteVpn(context)
                    },
                    label = { Text(domain) },
                    enabled = domain !in blockedDomains
                )
            }
        }

        Text(
            "Block list (${blockedDomains.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(blockedDomains.toList(), key = { it }) { domain ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(domain, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        WebsiteBlockStore.remove(context, domain)
                        blockedDomains = WebsiteBlockStore.domains(context)
                        if (blockedDomains.isEmpty()) stopWebsiteVpn(context)
                        else if (vpnRunning) refreshWebsiteVpn(context)
                    }) {
                        Text("Remove")
                    }
                }
                HorizontalDivider()
            }
            if (blockedDomains.isEmpty()) {
                item { Text("No blocked domains", modifier = Modifier.padding(vertical = 24.dp)) }
            }
        }

        Text(
            if (vpnRunning) "DNS filter active" else "DNS filter stopped",
            color = if (vpnRunning) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Button(
            onClick = {
                if (vpnRunning) {
                    stopWebsiteVpn(context)
                } else if (blockedDomains.isEmpty()) {
                    Toast.makeText(context, "Add a domain first", Toast.LENGTH_SHORT).show()
                } else {
                    val permissionIntent = VpnService.prepare(context)
                    if (permissionIntent == null) startWebsiteVpn(context)
                    else vpnPermissionLauncher.launch(permissionIntent)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (vpnRunning) "Stop website blocking" else "Start website blocking")
        }
        Text(
            "Matches the domain and all subdomains. Browser Secure DNS can bypass system DNS filtering.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun CreateSessionTab(
    installedApps: List<InstalledApp>,
    onSessionStarted: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    var durationMinutes by remember { mutableFloatStateOf(30f) }
    var searchQuery by remember { mutableStateOf("") }
    var reservedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectedApps = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        while (true) {
            reservedPackages = withContext(Dispatchers.IO) {
                FocusSessionStore.currentAndUpcoming(context).flatMap { it.packageNames }.toSet()
            }
            delay(1_000L)
        }
    }
    val frequentApps = installedApps
        .filter { it.blockCount > 0 }
        .sortedByDescending { it.blockCount }
        .take(5)
    val visibleApps = installedApps
        .filter {
            searchQuery.isBlank() ||
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
        .sortedWith(
            compareByDescending<InstalledApp> { it.packageName in selectedApps }
                .thenBy { it.name.lowercase() }
        )

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { requestUsageStatsPermission(context) }) { Text("Usage access") }
            Button(onClick = { requestOverlayPermission(context) }) { Text("Overlay access") }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Block for ${formatDuration(durationMinutes.toInt())}",
            style = MaterialTheme.typography.titleMedium
        )
        Slider(
            value = durationMinutes,
            onValueChange = { durationMinutes = it },
            valueRange = 5f..240f,
            steps = 46
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (searchQuery.isBlank() && frequentApps.isNotEmpty()) {
                item {
                    Text(
                        "Frequently blocked",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
                    )
                }
                items(frequentApps, key = { "frequent:${it.packageName}" }) { app ->
                    AppSelectionRow(
                        app = app,
                        selected = app.packageName in selectedApps,
                        willQueue = app.packageName in reservedPackages
                    ) {
                        toggleSelection(selectedApps, app.packageName)
                    }
                }
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            }

            item {
                Text(
                    "All apps (${selectedApps.size} selected)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
                )
            }
            items(visibleApps, key = { "all:${it.packageName}" }) { app ->
                AppSelectionRow(
                    app = app,
                    selected = app.packageName in selectedApps,
                    willQueue = app.packageName in reservedPackages
                ) {
                    toggleSelection(selectedApps, app.packageName)
                }
            }
            if (visibleApps.isEmpty()) {
                item { Text("No apps match your search", modifier = Modifier.padding(vertical = 24.dp)) }
            }
        }

        Button(
            onClick = {
                when {
                    !hasUsageStatsPermission(context) -> {
                        Toast.makeText(context, "Grant usage access first", Toast.LENGTH_SHORT).show()
                        requestUsageStatsPermission(context)
                    }
                    !Settings.canDrawOverlays(context) -> {
                        Toast.makeText(context, "Grant overlay access first", Toast.LENGTH_SHORT).show()
                        requestOverlayPermission(context)
                    }
                    selectedApps.isEmpty() -> {
                        Toast.makeText(context, "Select at least one app", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        val selectedPackages = selectedApps.toSet()
                        if (createFocusSession(context, selectedPackages, durationMinutes.toInt())) {
                            selectedApps.clear()
                            onSessionStarted(selectedPackages)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start blocking")
        }
    }
}

@Composable
private fun TrackingTab(installedApps: List<InstalledApp>) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf<List<FocusSession>>(emptyList()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val appsByPackage = installedApps.associateBy { it.packageName }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            sessions = withContext(Dispatchers.IO) {
                FocusSessionStore.currentAndUpcoming(context, now)
            }
            delay(1_000L)
        }
    }

    if (sessions.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("No active sessions", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Create a session to start blocking apps.")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Sessions (${sessions.size})",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
            )
        }
        items(sessions, key = { it.id }) { session ->
            SessionRow(session, appsByPackage, now)
            HorizontalDivider()
        }
    }
}

@Composable
private fun SessionRow(
    session: FocusSession,
    appsByPackage: Map<String, InstalledApp>,
    now: Long
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            if (session.startedAt > now) {
                "Starts in ${durationLabel(session.startedAt - now)}"
            } else {
                "${durationLabel(session.endsAt - now)} left"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(10.dp))
        session.packageNames.forEach { packageName ->
            val app = appsByPackage[packageName]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                app?.let {
                    Image(
                        bitmap = it.icon,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Text(
                    app?.name ?: packageName,
                    modifier = Modifier.padding(start = if (app == null) 0.dp else 12.dp)
                )
            }
        }
    }
}

@Composable
private fun AppSelectionRow(
    app: InstalledApp,
    selected: Boolean,
    willQueue: Boolean,
    onToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp)
    ) {
        Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(44.dp))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(app.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (willQueue) "Will queue after existing session" else app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = if (willQueue) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
    }
}

private fun durationLabel(remainingMillis: Long): String {
    if (remainingMillis <= 0L) return "0 seconds"
    val totalSeconds = ceil(remainingMillis / 1_000.0).toLong()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "$minutes min ${seconds}s left" else "$seconds seconds left"
}

private fun toggleSelection(selectedApps: MutableList<String>, packageName: String) {
    if (packageName in selectedApps) selectedApps.remove(packageName)
    else selectedApps.add(packageName)
}

private fun loadLaunchableApps(context: Context): List<InstalledApp> {
    val packageManager = context.packageManager
    val blockHistory = context.getSharedPreferences(BLOCK_HISTORY_PREFS, Context.MODE_PRIVATE)
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(launcherIntent, 0)
    }
    return activities
        .asSequence()
        .filter { it.activityInfo.packageName != context.packageName }
        .map {
            val packageName = it.activityInfo.packageName
            InstalledApp(
                name = it.loadLabel(packageManager).toString(),
                packageName = packageName,
                icon = it.loadIcon(packageManager).toBitmap(96, 96).asImageBitmap(),
                blockCount = blockHistory.getInt(packageName, 0)
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.name.lowercase() }
        .toList()
}

private fun formatDuration(minutes: Int): String = when {
    minutes < 60 -> "$minutes minutes"
    minutes % 60 == 0 -> "${minutes / 60} hours"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

private fun startWebsiteVpn(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, WebsiteBlockVpnService::class.java).setAction(
            WebsiteBlockVpnService.ACTION_REFRESH
        )
    )
}

private fun refreshWebsiteVpn(context: Context) {
    if (WebsiteBlockVpnService.isRunning) startWebsiteVpn(context)
}

private fun stopWebsiteVpn(context: Context) {
    context.startService(
        Intent(context, WebsiteBlockVpnService::class.java).setAction(
            WebsiteBlockVpnService.ACTION_STOP
        )
    )
}

private fun createFocusSession(
    context: Context,
    selectedApps: Set<String>,
    durationMinutes: Int
): Boolean {
    val now = System.currentTimeMillis()
    val session = FocusSessionStore.add(
        context = context,
        packageNames = selectedApps,
        startedAt = now,
        endsAt = now + durationMinutes * 60_000L
    )

    val blockHistory = context.getSharedPreferences(BLOCK_HISTORY_PREFS, Context.MODE_PRIVATE)
    blockHistory.edit().apply {
        selectedApps.forEach { packageName ->
            putInt(packageName, blockHistory.getInt(packageName, 0) + 1)
        }
    }.apply()

    val intent = Intent(context, FocusService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
    val message = if (session.startedAt > now) {
        "Session queued until the current block ends"
    } else {
        "Blocking session started"
    }
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    return session.packageNames.isNotEmpty()
}

fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

fun requestUsageStatsPermission(context: Context) {
    if (!hasUsageStatsPermission(context)) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}

fun requestOverlayPermission(context: Context) {
    if (!Settings.canDrawOverlays(context)) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }
}

private const val BLOCK_HISTORY_PREFS = "block_history"
