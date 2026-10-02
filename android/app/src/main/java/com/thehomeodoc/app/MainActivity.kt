package com.thehomeodoc.app

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private val Dark = Color(0xFF0B0B10)
private val Panel = Color(0xFF15151D)
private val Accent = Color(0xFF8B5CF6)

class MainActivity : ComponentActivity() {
    private lateinit var store: SessionStore
    private lateinit var api: ApiClient
    private var loginCodeHandled = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        api = ApiClient(store)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        scheduleNotifications()
        handleIntent(intent)
        setContent { TheHomeDocApp(store, api, ::logout) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "thehomeodoc" || uri.host != "auth" || loginCodeHandled) return
        val code = uri.getQueryParameter("code") ?: return
        loginCodeHandled = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = JsonObject().apply { addProperty("code", code) }
                val response = api.post("/api/mobile/auth/exchange", body)
                store.token = response.getAsJsonObject("data").get("token").asString
                withContext(Dispatchers.Main) { recreate() }
            } catch (_: Exception) {
                loginCodeHandled = false
            }
        }
    }

    private fun logout() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { api.post("/api/mobile/auth/logout") }
            store.token = null
            withContext(Dispatchers.Main) { recreate() }
        }
    }

    private fun scheduleNotifications() {
        val request = PeriodicWorkRequestBuilder<NotificationWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "thehomeodoc-notifications", ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }
}

@Composable
private fun TheHomeDocApp(store: SessionStore, api: ApiClient, onLogout: () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Dark, surface = Panel, primary = Accent,
            onPrimary = Color.White, onBackground = Color.White, onSurface = Color.White
        )
    ) {
        if (store.token == null) LoginScreen(api) else MainShell(api, onLogout)
    }
}

@Composable
private fun LoginScreen(api: ApiClient) {
    var email by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier.fillMaxSize().background(Dark).padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text("thehomeodoc", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(
                "Instagram comment-to-DM automation",
                color = Color.LightGray
            )

            if (sent) {
                Text(
                    "Check your email",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "We sent a secure sign-in link to $email. Tap the link in your email and you will return directly to the app.",
                    color = Color.LightGray
                )
                TextButton(onClick = {
                    sent = false
                    error = null
                }) {
                    Text("Use another email")
                }
            } else {
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Email") },
                    placeholder = { Text("you@company.com") },
                    singleLine = true
                )

                Button(
                    enabled = !sending && email.contains("@"),
                    onClick = {
                        sending = true
                        error = null
                        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                            try {
                                val body = JsonObject().apply {
                                    addProperty("email", email.trim())
                                }
                                api.post("/api/mobile/auth/request", body)
                                withContext(Dispatchers.Main) {
                                    sending = false
                                    sent = true
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    sending = false
                                    error = e.message ?: "Unable to send sign-in link."
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (sending) "Sending…" else "Send sign-in link")
                }

                error?.let { ErrorText(it) }

                Text(
                    "Your login is handled by the native app. The web dashboard is never opened for Android navigation.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}



private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Home),
    OVERVIEW("Overview", Icons.Default.Analytics),
    INBOX("Inbox", Icons.Default.Chat),
    CAMPAIGNS("Campaigns", Icons.Default.Campaign),
    LOGS("DM Logs", Icons.Default.List),
    SETTINGS("Settings", Icons.Default.Settings),
    DIAGNOSTICS("Diagnostics", Icons.Default.BugReport)
}

@Composable
private fun MainShell(api: ApiClient, onLogout: () -> Unit) {
    var selected by remember { mutableStateOf(Tab.DASHBOARD) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Panel) {
                Spacer(Modifier.height(18.dp))
                Text("thehomeodoc", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp))
                HorizontalDivider()
                Tab.entries.forEach { tab ->
                    NavigationDrawerItem(
                        label = { Text(tab.label) },
                        selected = selected == tab,
                        onClick = {
                            selected = tab
                            scope.launch { drawerState.close() }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
        }
    ) {
        Scaffold(
            containerColor = Dark,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.List, "Menu")
                    }
                    Text(selected.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (selected == Tab.SETTINGS) {
                        IconButton(onClick = onLogout) {
                            Icon(Icons.Default.Logout, "Sign out")
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (selected) {
                    Tab.DASHBOARD -> DashboardScreen(api)
                    Tab.OVERVIEW -> OverviewScreen(api)
                    Tab.INBOX -> InboxScreen(api)
                    Tab.CAMPAIGNS -> CampaignsScreen(api)
                    Tab.LOGS -> LogsScreen(api)
                    Tab.SETTINGS -> SettingsScreen(api, onLogout)
                    Tab.DIAGNOSTICS -> DiagnosticsScreen(api)
                }
            }
        }
    }
}

@Composable
private fun Screen(title: String, refresh: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            refresh?.let { IconButton(onClick = it) { Icon(Icons.Default.Refresh, "Refresh") } }
        }
        content()
    }
}

@Composable
private fun DashboardScreen(api: ApiClient) {
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val d = api.get("/api/dashboard/stats").getAsJsonObject("data")
                withContext(Dispatchers.Main) { data = d; error = null }
            } catch (e: Exception) { withContext(Dispatchers.Main) { error = e.message } }
        }
    }
    LaunchedEffect(Unit) { load() }
    Screen("Dashboard", ::load) {
        error?.let { ErrorText(it) }
        data?.let { d ->
            Text("Welcome ${d.get("userName")?.asString ?: ""}", color = Color.LightGray)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Active", d.int("activeAutomations"), Modifier.weight(1f))
                StatCard("DMs today", d.int("dmsSentToday"), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Clicks", d.int("clicksThisMonth"), Modifier.weight(1f))
                StatCard("Contacts", d.int("contactsCount"), Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            Text("Recent activity", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            val logs = d.getAsJsonArray("recentLogs") ?: JsonArray()
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logs.asList()) { item ->
                    val o = item.asJsonObject
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(o.getAsJsonObject("automation")?.get("name")?.asString ?: "DM")
                            Text(o.get("commentText")?.asString ?: "", color = Color.Gray, maxLines = 2)
                            Text(o.get("status")?.asString ?: "", color = Accent, fontSize = 12.sp)
                        }
                    }
                }
            }
        } ?: run { if (error == null) Loading() }
    }
}


@Composable
private fun OverviewScreen(api: ApiClient) {
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var accountId by remember { mutableStateOf("all") }
    var count by remember { mutableStateOf("50") }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val query = "/api/instagram/overview?count=" + count + if (accountId != "all") "&instagramAccountId=" + accountId else ""
                val d = api.get(query).getAsJsonObject("data")
                withContext(Dispatchers.Main) { data = d; error = null }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = e.message ?: "Failed to load overview." }
            }
        }
    }

    LaunchedEffect(accountId, count) { load() }

    Screen("Overview", ::load) {
        error?.let { ErrorText(it) }
        data?.let { d ->
            val accounts = d.getAsJsonArray("accounts") ?: JsonArray()
            if (accounts.size() > 1) {
                Text("Instagram account", color = Color.Gray, fontSize = 12.sp)
                accounts.asList().forEach { item ->
                    val a = item.asJsonObject
                    val id = a.get("id")?.asString ?: return@forEach
                    RadioOption(accountId == id, "@" + (a.get("username")?.asString ?: "account")) { accountId = id }
                }
                RadioOption(accountId == "all", "All accounts") { accountId = "all" }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("25", "50", "100", "all").forEach { option ->
                    FilterChip(
                        selected = count == option,
                        onClick = { count = option },
                        label = { Text(if (option == "all") "All time" else "Last " + option) }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            val totals = d.getAsJsonObject("totals") ?: JsonObject()
            val metric = listOf(
                "Views" to (totals.get("views")?.asInt ?: 0),
                "Reach" to (totals.get("reach")?.asInt ?: 0),
                "Likes" to (totals.get("likes")?.asInt ?: 0),
                "Comments" to (totals.get("comments")?.asInt ?: 0),
                "Saved" to (totals.get("saved")?.asInt ?: 0),
                "Shares" to (totals.get("shares")?.asInt ?: 0)
            )

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text("@" + (d.getAsJsonObject("account")?.get("username")?.asString ?: "instagram"), color = Accent, fontWeight = FontWeight.Bold)
                    Text((d.get("followers")?.asInt ?: 0).toString() + " followers", color = Color.Gray)
                }
                items(metric.chunked(2)) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (label, value) -> StatCard(label, value, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                item { Text("Posts", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                items(d.getAsJsonArray("posts")?.asList() ?: emptyList()) { item ->
                    val p = item.asJsonObject
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(p.get("mediaType")?.asString ?: "POST", color = Accent, fontSize = 11.sp)
                            Text(p.get("caption")?.asString ?: "No caption", maxLines = 2)
                            Text(
                                "Views " + (p.get("views")?.asInt ?: 0) + " • Reach " + (p.get("reach")?.asInt ?: 0) +
                                    " • Likes " + (p.get("likes")?.asInt ?: 0) + " • Comments " + (p.get("comments")?.asInt ?: 0),
                                color = Color.Gray, fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        } ?: Loading()
    }
}

@Composable
private fun LogsScreen(api: ApiClient) {
    val statuses = listOf("ALL", "SENT", "FAILED", "PENDING", "SKIPPED_RATE_LIMIT", "SKIPPED_PLAN_LIMIT", "SKIPPED_DEDUP")
    var status by remember { mutableStateOf("ALL") }
    var accountId by remember { mutableStateOf("all") }
    var logs by remember { mutableStateOf<JsonArray?>(null) }
    var page by remember { mutableStateOf(1) }
    var totalPages by remember { mutableStateOf(1) }
    var accounts by remember { mutableStateOf<JsonArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val query = buildString {
                    append("/api/logs?page=")
                    append(page)
                    append("&limit=20")
                    if (status != "ALL") append("&status=").append(status)
                    if (accountId != "all") append("&instagramAccountId=").append(accountId)
                }
                val d = api.get(query).getAsJsonObject("data")
                val a = api.get("/api/instagram/accounts").getAsJsonObject("data").getAsJsonArray("instagramAccounts")
                withContext(Dispatchers.Main) {
                    logs = d.getAsJsonArray("logs")
                    totalPages = d.getAsJsonObject("pagination")?.get("totalPages")?.asInt ?: 1
                    accounts = a
                    error = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = e.message ?: "Failed to load logs." }
            }
        }
    }

    LaunchedEffect(status, accountId, page) { load() }

    Screen("DM Logs", ::load) {
        error?.let { ErrorText(it) }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                if ((accounts?.size() ?: 0) > 1) {
                    Text("Account", color = Color.Gray, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = accountId == "all", onClick = { accountId = "all"; page = 1 }, label = { Text("All") })
                        accounts?.asList()?.forEach { item ->
                            val a = item.asJsonObject
                            val id = a.get("id")?.asString ?: return@forEach
                            FilterChip(selected = accountId == id, onClick = { accountId = id; page = 1 }, label = { Text("@" + (a.get("username")?.asString ?: "")) })
                        }
                    }
                }
                Text("Status", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    statuses.forEach { s ->
                        FilterChip(selected = status == s, onClick = { status = s; page = 1 }, label = { Text(s) })
                    }
                }
            }

            items(logs?.asList() ?: emptyList()) { item ->
                val l = item.asJsonObject
                Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("@" + (l.get("commenterName")?.asString ?: l.get("commenterId")?.asString?.take(8).orEmpty()), fontWeight = FontWeight.Bold)
                        Text(l.get("commentText")?.asString ?: "", color = Color.Gray, maxLines = 2)
                        Text(l.getAsJsonObject("automation")?.get("name")?.asString ?: "Campaign")
                        Text("@" + (l.getAsJsonObject("instagramAccount")?.get("username")?.asString ?: ""), color = Accent, fontSize = 12.sp)
                        Text(l.get("status")?.asString ?: "", color = Accent, fontSize = 12.sp)
                        l.get("errorMessage")?.asString?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Page " + page + " / " + totalPages, color = Color.Gray)
                    Row {
                        TextButton(enabled = page > 1, onClick = { page-- }) { Text("Previous") }
                        TextButton(enabled = page < totalPages, onClick = { page++ }) { Text("Next") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(api: ApiClient) {
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val d = api.get("/api/admin/diagnostics").getAsJsonObject("data")
                withContext(Dispatchers.Main) { data = d; error = null }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = e.message ?: "Failed to load diagnostics." }
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Screen("Diagnostics", ::load) {
        error?.let { ErrorText(it) }
        data?.let { d ->
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    val worker = d.getAsJsonObject("workerHealth")
                    val healthy = worker?.get("healthy")?.asBoolean == true
                    StatusCard("Worker health", if (healthy) "Healthy" else "Needs attention")
                }
                item {
                    val q = d.getAsJsonObject("queueCounts")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("waiting", "active", "delayed", "failed").forEach { key ->
                            StatCard(key, q?.get(key)?.asInt ?: 0, Modifier.weight(1f))
                        }
                    }
                }
                item { DiagnosticSection("Recent Worker Alerts", d.getAsJsonArray("workerAlerts"), "message") }
                item { DiagnosticSection("Campaign DM Failures And Skips", d.getAsJsonArray("dmFailures"), "errorMessage") }
                item { DiagnosticSection("Webhook Failures", d.getAsJsonArray("webhookFailures"), "errorMessage") }
                item { DiagnosticSection("Token Refresh Failures", d.getAsJsonArray("tokenRefreshFailures"), "message") }
                item { DiagnosticSection("Operational Event Timeline", d.getAsJsonArray("operationalEvents"), "message") }
            }
        } ?: Loading()
    }
}

@Composable
private fun StatusCard(title: String, value: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Color.Gray, fontSize = 12.sp)
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DiagnosticSection(title: String, array: JsonArray?, field: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            val values = array?.asList().orEmpty()
            if (values.isEmpty()) {
                Text("No records.", color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
            } else {
                values.take(10).forEach { item ->
                    val o = item.asJsonObject
                    Text(o.get(field)?.asString?.takeIf { it.isNotBlank() } ?: o.get("message")?.asString ?: "Event", modifier = Modifier.padding(top = 8.dp), fontSize = 13.sp)
                    o.get("createdAt")?.asString?.let { Text(it, color = Color.Gray, fontSize = 11.sp) }
                }
            }
        }
    }
}


@Composable
private fun CampaignsScreen(api: ApiClient) {
    val context = LocalContext.current
    var campaigns by remember { mutableStateOf<JsonArray?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var message by remember { mutableStateOf<String?>(null) }

    fun load() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val d = api.get("/api/automations").getAsJsonArray("data")
                withContext(Dispatchers.Main) { campaigns = d; message = null }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = e.message ?: "Failed to load campaigns." }
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Screen("Campaigns", ::load) {
        Button(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth()) {
            Text("New Campaign")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search campaigns by name, keyword, or message…") },
            singleLine = true
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all", "active", "paused").forEach { option ->
                FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option.replaceFirstChar { it.uppercase() }) })
            }
        }
        message?.let { ErrorText(it) }

        val query = search.trim().lowercase()
        val filtered = campaigns?.asList()?.filter { item ->
            val a = item.asJsonObject
            val active = a.get("isActive")?.asBoolean == true
            val name = a.get("name")?.asString.orEmpty()
            val dm = a.get("dmMessage")?.asString.orEmpty()
            val keys = a.getAsJsonArray("keywords")?.asList()?.joinToString(" ").orEmpty()
            (filter == "all" || (filter == "active" && active) || (filter == "paused" && !active)) &&
                (query.isBlank() || name.lowercase().contains(query) || dm.lowercase().contains(query) || keys.lowercase().contains(query))
        } ?: emptyList()

        if (campaigns != null && filtered.isEmpty()) {
            Text("No campaigns match your search.", color = Color.Gray, modifier = Modifier.padding(20.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered) { item ->
                val a = item.asJsonObject
                val id = a.get("id")?.asString ?: return@items
                val active = a.get("isActive")?.asBoolean == true
                val analytics = a.getAsJsonObject("analytics")
                Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.get("name")?.asString ?: "Campaign", fontWeight = FontWeight.Bold)
                                Text(
                                    if (active) "Active" else "Paused",
                                    color = if (active) Color(0xFF7DD3A5) else Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = active,
                                onCheckedChange = { checked ->
                                    GlobalScope.launch(Dispatchers.IO) {
                                        val body = JsonObject().apply { addProperty("isActive", checked) }
                                        runCatching { api.patch("/api/automations?id=" + id, body) }
                                        withContext(Dispatchers.Main) { load() }
                                    }
                                }
                            )
                        }
                        Text("@" + (a.getAsJsonObject("instagramAccount")?.get("username")?.asString ?: ""), color = Accent, fontSize = 12.sp)
                        Text(a.get("dmMessage")?.asString ?: "", color = Color.Gray, maxLines = 2)
                        Text(
                            "Runs " + (a.getAsJsonObject("_count")?.get("dmLogs")?.asInt ?: 0) +
                                " • " + (analytics?.get("ctr")?.asDouble ?: 0.0) + "% CTR • " +
                                (analytics?.get("sent")?.asInt ?: 0) + " sent • " +
                                (analytics?.get("clicks")?.asInt ?: 0) + " clicks",
                            color = Color.Gray, fontSize = 12.sp
                        )
                        a.getAsJsonArray("keywords")?.asList()?.takeIf { it.isNotEmpty() }?.let {
                            Text(it.joinToString(", ") { k -> k.asString }, color = Accent, fontSize = 12.sp)
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            a.get("postUrl")?.asString?.takeIf { it.isNotBlank() }?.let { url ->
                                TextButton(onClick = {
                                    val clip = context.getSystemService(ClipboardManager::class.java)
                                    clip?.setPrimaryClip(ClipData.newPlainText("Instagram URL", url))
                                }) { Text("Copy URL") }
                            }
                            TextButton(onClick = { editing = a }) { Text("Edit") }
                            TextButton(onClick = {
                                GlobalScope.launch(Dispatchers.IO) {
                                    try {
                                        api.post("/api/automations/duplicate?id=" + id)
                                        withContext(Dispatchers.Main) { load() }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) { message = e.message ?: "Duplicate failed." }
                                    }
                                }
                            }) { Text("Duplicate") }
                            TextButton(onClick = {
                                GlobalScope.launch(Dispatchers.IO) {
                                    try {
                                        api.delete("/api/automations?id=" + id)
                                        withContext(Dispatchers.Main) { load() }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) { message = e.message ?: "Delete failed." }
                                    }
                                }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateCampaignDialog(api, { showCreate = false; load() })
    }
    editing?.let { campaign ->
        CreateCampaignDialog(api, { editing = null; load() }, campaign)
    }
}

@Composable
private fun CreateCampaignDialog(api: ApiClient, onDone: () -> Unit, existing: JsonObject? = null) {
    var name by remember { mutableStateOf("") }
    var accounts by remember { mutableStateOf<JsonArray?>(null) }
    var accountId by remember { mutableStateOf("") }
    var triggerScope by remember { mutableStateOf("specific") }
    var posts by remember { mutableStateOf<JsonArray?>(null) }
    var postId by remember { mutableStateOf<String?>(null) }
    var postUrl by remember { mutableStateOf<String?>(null) }
    var postCaption by remember { mutableStateOf("") }
    var postQuery by remember { mutableStateOf("") }
    var showMorePosts by remember { mutableStateOf(false) }
    var matchAnyWord by remember { mutableStateOf(false) }
    var keywords by remember { mutableStateOf("") }
    var dmTrigger by remember { mutableStateOf(false) }
    var publicReply by remember { mutableStateOf(false) }
    var publicReplies by remember { mutableStateOf(listOf("")) }
    var openingDm by remember { mutableStateOf(false) }
    var openingMessage by remember { mutableStateOf("") }
    var openingButton by remember { mutableStateOf("") }
    var dmMessage by remember { mutableStateOf("") }
    var linkOpen by remember { mutableStateOf(false) }
    var firstLink by remember { mutableStateOf("") }
    var firstButton by remember { mutableStateOf("Open link") }
    var secondLinkOpen by remember { mutableStateOf(false) }
    var secondLink by remember { mutableStateOf("") }
    var secondButton by remember { mutableStateOf("Open link") }
    var requireFollow by remember { mutableStateOf(false) }
    var followPrompt by remember { mutableStateOf("") }
    var followButton by remember { mutableStateOf("I'm following") }
    var followUp by remember { mutableStateOf(false) }
    var followUpMessage by remember { mutableStateOf("") }
    var followUpDelay by remember { mutableStateOf("0") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var previewTab by remember { mutableStateOf("DM") }


    LaunchedEffect(existing) {
        existing?.let { e ->
            name = e.get("name")?.asString.orEmpty()
            accountId = e.get("instagramAccountId")?.asString.orEmpty()
            triggerScope = when {
                e.get("pendingNextReel")?.asBoolean == true -> "next"
                e.get("matchAnyPost")?.asBoolean == true -> "any"
                else -> "specific"
            }
            postId = e.get("postId")?.asString
            postUrl = e.get("postUrl")?.asString
            matchAnyWord = e.get("matchAnyWord")?.asBoolean == true
            keywords = e.getAsJsonArray("keywords")?.asList()?.joinToString(", ") { it.asString }.orEmpty()
            dmTrigger = e.get("dmTriggerEnabled")?.asBoolean == true
            publicReply = e.get("publicReplyEnabled")?.asBoolean == true
            publicReplies = e.getAsJsonArray("publicReplyMessages")?.asList()?.map { it.asString }?.ifEmpty { listOf("") } ?: listOf("")
            openingDm = e.get("openingDmEnabled")?.asBoolean == true
            openingMessage = e.get("openingDmMessage")?.asString.orEmpty()
            openingButton = e.get("openingDmButtonLabel")?.asString.orEmpty()
            dmMessage = e.get("dmMessage")?.asString.orEmpty()
            requireFollow = e.get("requireFollow")?.asBoolean == true
            followPrompt = e.get("followPromptMessage")?.asString.orEmpty()
            followButton = e.get("followPromptButtonLabel")?.asString ?: "I'm following"
            followUp = e.get("followUpEnabled")?.asBoolean == true
            followUpMessage = e.get("followUpMessage")?.asString.orEmpty()
            followUpDelay = (e.get("followUpDelayMinutes")?.asInt ?: 0).toString()
            val links = e.getAsJsonArray("trackedLinks")?.asList().orEmpty()
            firstLink = links.getOrNull(0)?.asJsonObject?.get("destinationUrl")?.asString.orEmpty()
            firstButton = links.getOrNull(0)?.asJsonObject?.get("label")?.asString ?: "Open link"
            secondLink = links.getOrNull(1)?.asJsonObject?.get("destinationUrl")?.asString.orEmpty()
            secondButton = links.getOrNull(1)?.asJsonObject?.get("label")?.asString ?: "Open link"
            linkOpen = firstLink.isNotBlank()
            secondLinkOpen = secondLink.isNotBlank()
        }
    }

    fun loadAccounts() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val data = api.get("/api/dashboard/stats").getAsJsonObject("data")
                val list = data.getAsJsonArray("instagramAccounts") ?: JsonArray()
                withContext(Dispatchers.Main) {
                    accounts = list
                    if (accountId.isBlank() && list.size() > 0) {
                        accountId = list[0].asJsonObject.get("id").asString
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = e.message ?: "Failed to load Instagram accounts." }
            }
        }
    }

    fun loadPosts(id: String) {
        if (id.isBlank()) return
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val data = api.get("/api/instagram/posts?instagramAccountId=$id&all=true")
                val list = data.getAsJsonArray("data") ?: JsonArray()
                withContext(Dispatchers.Main) {
                    posts = list
                    if (postId != null && list.asList().none {
                            it.asJsonObject.get("id")?.asString == postId
                        }) {
                        postId = null
                        postUrl = null
                        postCaption = ""
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = e.message ?: "Failed to load Instagram posts."
                }
            }
        }
    }

    LaunchedEffect(Unit) { loadAccounts() }
    LaunchedEffect(accountId, triggerScope) {
        if (triggerScope == "specific" && accountId.isNotBlank()) loadPosts(accountId)
    }

    val keywordList = keywords.split(",").map { it.trim() }.filter { it.isNotBlank() }
    val visiblePosts = posts?.asList()
        ?.filter {
            postQuery.isBlank() ||
                (it.asJsonObject.get("caption")?.asString ?: "")
                    .contains(postQuery, ignoreCase = true)
        }
        ?.let { if (showMorePosts) it else it.take(12) }
        ?: emptyList()

    fun save() {
        error = null
        if (accountId.isBlank()) {
            error = "Select an Instagram account."
            return
        }
        if (triggerScope == "specific" && postId.isNullOrBlank()) {
            error = "Pick a specific post or reel."
            return
        }
        if (!matchAnyWord && keywordList.isEmpty()) {
            error = "Add at least one keyword, or switch to Any word."
            return
        }
        if (dmMessage.trim().isBlank()) {
            error = "Add the main DM message."
            return
        }
        if (openingDm && (openingMessage.trim().isBlank() || openingButton.trim().isBlank())) {
            error = "Opening DM needs both a message and button label."
            return
        }
        if (requireFollow && (followPrompt.trim().isBlank() || followButton.trim().isBlank())) {
            error = "Follow gate needs a prompt and button label."
            return
        }
        if (followUp && followUpMessage.trim().isBlank()) {
            error = "Follow-up needs a message."
            return
        }
        if (linkOpen && firstLink.isNotBlank() && !firstLink.startsWith("http://") && !firstLink.startsWith("https://")) {
            error = "First link must start with http:// or https://."
            return
        }
        if (secondLinkOpen && secondLink.isNotBlank() && !secondLink.startsWith("http://") && !secondLink.startsWith("https://")) {
            error = "Second link must start with http:// or https://."
            return
        }

        saving = true
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val body = JsonObject().apply {
                    addProperty("name", name.trim().ifBlank { "Instagram campaign" })
                    addProperty("instagramAccountId", accountId)
                    addProperty("postId", if (triggerScope == "specific") postId else null)
                    addProperty("postUrl", if (triggerScope == "specific") postUrl else null)
                    addProperty("pendingNextReel", triggerScope == "next")
                    addProperty("matchAnyPost", triggerScope == "any")
                    add("keywords", JsonArray().apply {
                        if (!matchAnyWord) keywordList.forEach { add(it) }
                    })
                    addProperty("matchAnyWord", matchAnyWord)
                    addProperty("dmTriggerEnabled", dmTrigger)
                    addProperty("dmMessage", dmMessage.trim())
                    addProperty("openingDmEnabled", openingDm)
                    addProperty("openingDmMessage", if (openingDm) openingMessage.trim() else null)
                    addProperty("openingDmButtonLabel", if (openingDm) openingButton.trim() else null)
                    addProperty("publicReplyEnabled", publicReply)
                    add("publicReplyMessages", JsonArray().apply {
                        if (publicReply) publicReplies.map { it.trim() }.filter { it.isNotBlank() }.forEach { add(it) }
                    })
                    addProperty("trackedDestinationUrl", if (linkOpen) firstLink.trim() else "")
                    addProperty("linkButtonLabel", if (linkOpen) firstButton.trim().ifBlank { "Open link" } else "Open link")
                    addProperty("secondaryDestinationUrl", if (secondLinkOpen) secondLink.trim() else "")
                    addProperty("secondaryButtonLabel", if (secondLinkOpen) secondButton.trim().ifBlank { "Open link" } else "Open link")
                    addProperty("requireFollow", requireFollow)
                    addProperty("followPromptMessage", if (requireFollow) followPrompt.trim() else "")
                    addProperty("followPromptButtonLabel", if (requireFollow) followButton.trim() else "")
                    addProperty("followUpEnabled", followUp)
                    addProperty("followUpMessage", if (followUp) followUpMessage.trim() else "")
                    addProperty("followUpDelayMinutes", followUpDelay.toIntOrNull()?.coerceIn(0, 1440) ?: 0)
                    addProperty("isActive", existing?.get("isActive")?.asBoolean ?: true)
                    addProperty("wholeWordMatch", true)
                }
                if (existing == null) api.post("/api/automations", body) else api.patch("/api/automations?id=" + existing.get("id").asString, body)
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    saving = false
                    error = e.message ?: "Failed to create campaign."
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDone() },
        title = { Text("New campaign") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Field(name, "Campaign name") { name = it }

                accounts?.let { list ->
                    if (list.size() > 0) {
                        SectionTitle("Instagram account")
                        list.asList().forEach { item ->
                            val a = item.asJsonObject
                            val id = a.get("id")?.asString ?: return@forEach
                            RadioOption(accountId == id, "@" + (a.get("username")?.asString ?: "account")) {
                                accountId = id
                                postId = null
                                postUrl = null
                                postCaption = ""
                                posts = null
                            }
                        }
                    }
                }

                SectionTitle("When someone comments on")
                RadioOption(triggerScope == "specific", "a specific post or reel") { triggerScope = "specific" }
                RadioOption(triggerScope == "any", "any post or reel") { triggerScope = "any"; postId = null; postUrl = null }
                RadioOption(triggerScope == "next", "next post or reel") { triggerScope = "next"; postId = null; postUrl = null }

                if (triggerScope == "specific") {
                    Field(postQuery, "Search posts by caption") { postQuery = it; showMorePosts = false }
                    if (visiblePosts.isEmpty()) {
                        Text(if (posts == null) "Loading posts…" else "No matching posts.", color = Color.Gray, fontSize = 12.sp)
                    } else {
                        visiblePosts.forEach { item ->
                            val p = item.asJsonObject
                            val id = p.get("id")?.asString ?: return@forEach
                            val selected = postId == id
                            Card(
                                onClick = {
                                    postId = id
                                    postUrl = p.get("permalink")?.asString
                                    postCaption = p.get("caption")?.asString ?: ""
                                },
                                colors = CardDefaults.cardColors(containerColor = if (selected) Accent.copy(alpha = 0.20f) else Panel),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(
                                        model = p.get("thumbnail_url")?.asString ?: p.get("media_url")?.asString,
                                        contentDescription = "Instagram post",
                                        modifier = Modifier.size(58.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(if (selected) "Selected • " + (p.get("media_type")?.asString ?: "POST") else (p.get("media_type")?.asString ?: "POST"), color = Accent, fontSize = 11.sp)
                                        Text(p.get("caption")?.asString ?: "No caption", maxLines = 2)
                                    }
                                }
                            }
                        }
                        if ((posts?.size() ?: 0) > 12 && !showMorePosts) {
                            TextButton(onClick = { showMorePosts = true }) { Text("Show more posts") }
                        }
                    }
                }

                SectionTitle("And this comment has")
                RadioOption(!matchAnyWord, "a specific word or words") { matchAnyWord = false }
                if (!matchAnyWord) {
                    Field(keywords, "Keywords (comma separated)") { keywords = it }
                }
                RadioOption(matchAnyWord, "any word") { matchAnyWord = true }

                ToggleRow("Also reply when someone DMs", dmTrigger) { dmTrigger = it }
                ToggleRow("Reply to their comments under the post", publicReply) { publicReply = it }
                if (publicReply) {
                    publicReplies.forEachIndexed { index, value ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Field(value, "Public reply " + (index + 1)) { next ->
                                publicReplies = publicReplies.mapIndexed { i, old -> if (i == index) next else old }
                            }
                            if (publicReplies.size > 1) {
                                TextButton(onClick = {
                                    publicReplies = publicReplies.filterIndexed { i, _ -> i != index }
                                }) { Text("Remove") }
                            }
                        }
                    }
                    if (publicReplies.size < 10) {
                        TextButton(onClick = { publicReplies = publicReplies + "" }) { Text("+ Add another reply") }
                    }
                }

                SectionTitle("They will get")
                ToggleRow("Opening DM", openingDm) { openingDm = it }
                if (openingDm) {
                    MultiField(openingMessage, "Opening DM message", false) { openingMessage = it }
                    Field(openingButton, "Opening DM button label") { openingButton = it }
                }

                ToggleRow("Follow requirement first", requireFollow) { requireFollow = it }
                if (requireFollow) {
                    MultiField(followPrompt, "Follow prompt", false) { followPrompt = it }
                    Field(followButton, "Follow button label") { followButton = it }
                }

                SectionTitle("And then, they will get")
                MultiField(dmMessage, "Main DM message", false) { dmMessage = it }
                ToggleRow("Add first tracked link", linkOpen) { linkOpen = it }
                if (linkOpen) {
                    Field(firstLink, "First link URL") { firstLink = it }
                    Field(firstButton, "First link button label") { firstButton = it }
                    ToggleRow("Add second link", secondLinkOpen) { secondLinkOpen = it }
                    if (secondLinkOpen) {
                        Field(secondLink, "Second link URL") { secondLink = it }
                        Field(secondButton, "Second link button label") { secondButton = it }
                    }
                }

                ToggleRow("Follow-up message", followUp) { followUp = it }
                if (followUp) {
                    MultiField(followUpMessage, "Follow-up message", false) { followUpMessage = it }
                    Field(followUpDelay, "Follow-up delay (minutes)") { followUpDelay = it.filter(Char::isDigit) }
                }

                SectionTitle("Instagram preview")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = previewTab == "DM", onClick = { previewTab = "DM" }, label = { Text("DM") })
                    FilterChip(selected = previewTab == "Comment", onClick = { previewTab = "Comment" }, label = { Text("Comment") })
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("@" + (accounts?.asList()?.firstOrNull {
                            it.asJsonObject.get("id")?.asString == accountId
                        }?.asJsonObject?.get("username")?.asString ?: "instagram"), color = Accent, fontWeight = FontWeight.Bold)
                        if (previewTab == "Comment") {
                            Text(publicReplies.firstOrNull { it.isNotBlank() } ?: "Your public reply will appear here.", color = Color.LightGray)
                        } else {
                            if (openingDm) Text(openingMessage.ifBlank { "Opening DM message" })
                            if (requireFollow) Text(followPrompt.ifBlank { "Follow before receiving the link." }, color = Color.LightGray)
                            Text(dmMessage.ifBlank { "Your main DM message will appear here." })
                            if (linkOpen) Text(firstButton.ifBlank { "Open link" } + " → " + firstLink.ifBlank { "https://example.com" }, color = Accent)
                            if (secondLinkOpen) Text(secondButton.ifBlank { "Open link" } + " → " + secondLink.ifBlank { "https://example.com/second" }, color = Accent)
                            if (followUp) Text("Follow-up (" + followUpDelay.ifBlank { "0" } + " min): " + followUpMessage.ifBlank { "Follow-up message" }, color = Color.LightGray)
                        }
                    }
                }

                Text(
                    "Validation: account, trigger, keyword mode, main DM, and enabled optional sections are checked before saving.",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
                error?.let { ErrorText(it) }
            }
        },
        confirmButton = {
            Button(enabled = !saving, onClick = { save() }) {
                Text(if (saving) "Saving…" else if (existing == null) "Go Live" else "Save changes")
            }
        },
        dismissButton = { TextButton(onClick = onDone, enabled = !saving) { Text("Cancel") } }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
}

@Composable
private fun RadioOption(selected: Boolean, label: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = if (selected) Accent.copy(alpha = 0.15f) else Panel),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Text(label, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun MultiField(value: String, label: String, singleLine: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 6
    )
}



@Composable
private fun InboxScreen(api: ApiClient) {
    var accounts by remember { mutableStateOf<JsonArray?>(null) }
    var accountId by remember { mutableStateOf("") }
    var conversations by remember { mutableStateOf<JsonArray?>(null) }
    var active by remember { mutableStateOf<JsonObject?>(null) }
    var messages by remember { mutableStateOf<JsonArray?>(null) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    fun loadAccounts() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val d = api.get("/api/instagram/accounts").getAsJsonObject("data")
                withContext(Dispatchers.Main) {
                    accounts = d.getAsJsonArray("instagramAccounts")
                    if (accountId.isBlank()) accountId = d.get("selectedInstagramAccountId")?.asString ?: accounts?.get(0)?.asJsonObject?.get("id")?.asString.orEmpty()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = e.message ?: "Failed to load Instagram accounts." }
            }
        }
    }

    fun loadConversations() {
        if (accountId.isBlank()) return
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val d = api.get("/api/instagram/conversations?instagramAccountId=" + accountId).getAsJsonObject("data")
                withContext(Dispatchers.Main) { conversations = d.getAsJsonArray("conversations"); error = null }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = e.message ?: "Failed to load conversations." }
            }
        }
    }

    fun loadMessages(conversationId: String) {
        if (accountId.isBlank()) return
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val d = api.get("/api/instagram/conversations/" + conversationId + "?instagramAccountId=" + accountId).getAsJsonObject("data")
                val list = JsonArray()
                d.getAsJsonArray("messages")?.forEach { list.add(it) }
                withContext(Dispatchers.Main) { messages = list; error = null }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = e.message ?: "Failed to load messages." }
            }
        }
    }

    fun send() {
        val conversation = active ?: return
        val recipient = conversation.getAsJsonObject("contact")?.get("id")?.asString.orEmpty()
        val text = draft.trim()
        if (recipient.isBlank() || text.isBlank() || sending) return
        sending = true
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val body = JsonObject().apply {
                    addProperty("instagramAccountId", accountId)
                    addProperty("recipientId", recipient)
                    addProperty("text", text)
                }
                api.post("/api/instagram/conversations", body)
                withContext(Dispatchers.Main) { draft = ""; sending = false }
                loadMessages(conversation.get("id").asString)
                loadConversations()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { sending = false; error = e.message ?: "Failed to send message." }
            }
        }
    }

    LaunchedEffect(Unit) { loadAccounts() }
    LaunchedEffect(accountId) {
        active = null
        messages = null
        loadConversations()
    }
    LaunchedEffect(active?.get("id")?.asString) {
        val id = active?.get("id")?.asString ?: return@LaunchedEffect
        loadMessages(id)
    }

    Screen("Inbox", ::loadConversations) {
        error?.let { ErrorText(it) }
        if ((accounts?.size() ?: 0) > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                accounts?.asList()?.forEach { item ->
                    val a = item.asJsonObject
                    val id = a.get("id")?.asString ?: return@forEach
                    FilterChip(selected = accountId == id, onClick = { accountId = id }, label = { Text("@" + (a.get("username")?.asString ?: "")) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        active?.let { conversation ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { active = null; messages = null }) { Text("Back") }
                Text("@" + (conversation.getAsJsonObject("contact")?.get("username")?.asString ?: "unknown"), fontWeight = FontWeight.Bold)
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages?.asList() ?: emptyList()) { item ->
                    val m = item.asJsonObject
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.get("fromMe")?.asBoolean == true) Arrangement.End else Arrangement.Start) {
                        Card(colors = CardDefaults.cardColors(containerColor = if (m.get("fromMe")?.asBoolean == true) Accent.copy(alpha = 0.25f) else Panel)) {
                            Column(Modifier.padding(10.dp)) {
                                Text(m.get("text")?.asString ?: "", color = Color.White)
                                m.get("createdTime")?.asString?.let { Text(it, color = Color.Gray, fontSize = 10.sp) }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Reply…") },
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                Button(enabled = !sending && draft.isNotBlank(), onClick = { send() }) {
                    Text(if (sending) "Sending…" else "Send")
                }
            }
        } ?: run {
            Text("Conversations", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            conversations?.let { list ->
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list.asList()) { item ->
                        val conversation = item.asJsonObject
                        Card(
                            onClick = { active = conversation },
                            colors = CardDefaults.cardColors(containerColor = Panel),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    if (conversation.get("detailsUnavailable")?.asBoolean == true) "Details unavailable"
                                    else "@" + (conversation.getAsJsonObject("contact")?.get("username")?.asString ?: "unknown"),
                                    fontWeight = FontWeight.Bold
                                )
                                conversation.getAsJsonObject("lastMessage")?.let { last ->
                                    Text(last.get("text")?.asString ?: "(no text)", color = Color.Gray, maxLines = 2)
                                }
                                conversation.get("updatedTime")?.asString?.let { Text(it, color = Color.Gray, fontSize = 10.sp) }
                            }
                        }
                    }
                }
            } ?: Loading()
        }
    }
}

@Composable
private fun AnalyticsScreen(api: ApiClient) {
    var data by remember { mutableStateOf<JsonObject?>(null) }
    fun load() {
        GlobalScope.launch(Dispatchers.IO) {
            runCatching { api.get("/api/instagram/overview?count=30").getAsJsonObject("data") }.onSuccess {
                GlobalScope.launch(Dispatchers.Main) { data = it }
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    Screen("Analytics", ::load) {
        data?.let { d ->
            val t = d.getAsJsonObject("totals")
            StatCard("Followers", d.get("followers")?.asInt ?: 0, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Posts", t.int("posts"), Modifier.weight(1f))
                StatCard("Reach", t.int("reach"), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Likes", t.int("likes"), Modifier.weight(1f))
                StatCard("Comments", t.int("comments"), Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            Text("Recent posts", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(d.getAsJsonArray("posts")?.asList()?.take(10) ?: emptyList()) { item ->
                    val p = item.asJsonObject
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(p.get("mediaType")?.asString ?: "POST", color = Accent, fontSize = 12.sp)
                            Text(p.get("caption")?.asString ?: "No caption", maxLines = 2)
                            Text("Likes ${p.int("likes")} • Comments ${p.int("comments")} • Reach ${p.get("reach")?.asInt ?: 0}", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        } ?: Loading()
    }
}


@Composable
private fun SettingsScreen(api: ApiClient, onLogout: () -> Unit) {
    val context = LocalContext.current
    var accounts by remember { mutableStateOf<JsonArray?>(null) }
    var members by remember { mutableStateOf<JsonObject?>(null) }
    var workspace by remember { mutableStateOf<JsonObject?>(null) }
    var inviteEmail by remember { mutableStateOf("") }
    var inviteRole by remember { mutableStateOf("MEMBER") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun load() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val stats = api.get("/api/dashboard/stats").getAsJsonObject("data")
                val accountData = api.get("/api/instagram/accounts").getAsJsonObject("data")
                val memberData = api.get("/api/workspace/members").getAsJsonObject("data")
                withContext(Dispatchers.Main) {
                    workspace = stats.getAsJsonObject("workspace")
                    accounts = accountData.getAsJsonArray("instagramAccounts")
                    members = memberData
                    message = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = e.message ?: "Failed to load settings." }
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Screen("Settings", ::load) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SettingsPanel("Interface language") {
                    Text("English", fontWeight = FontWeight.SemiBold)
                    Text("Saved in this app. Campaign messages stay unchanged.", color = Color.Gray, fontSize = 12.sp)
                }
            }

            item {
                SettingsPanel("Instagram Connection") {
                    val connected = (accounts?.size() ?: 0) > 0
                    Text(
                        if (connected) "Connected" else "Not connected",
                        color = if (connected) Color(0xFF7DD3A5) else Color(0xFFFBBF24)
                    )
                    Text(
                        if ((accounts?.size() ?: 0) == 1) "1 connected Instagram profile"
                        else (accounts?.size() ?: 0).toString() + " connected Instagram profiles",
                        color = Color.Gray, fontSize = 12.sp
                    )

                    accounts?.asList()?.forEach { item ->
                        val a = item.asJsonObject
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("@" + (a.get("username")?.asString ?: ""), fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Token expires " + (a.get("tokenExpiresAt")?.asString ?: "not available") +
                                        " • " + if (a.get("webhookSubscribed")?.asBoolean == true) "Webhook ready" else "Webhook pending",
                                    color = Color.Gray, fontSize = 11.sp
                                )
                            }
                            TextButton(enabled = !busy, onClick = {
                                busy = true
                                GlobalScope.launch(Dispatchers.IO) {
                                    val body = JsonObject().apply { addProperty("instagramAccountId", a.get("id").asString) }
                                    runCatching { api.post("/api/instagram/disconnect", body) }
                                    withContext(Dispatchers.Main) { busy = false; load() }
                                }
                            }) { Text("Disconnect") }
                        }
                    }

                    Button(
                        onClick = {
                            CustomTabsIntent.Builder().build().launchUrl(
                                context,
                                Uri.parse(BuildConfig.API_BASE_URL + "/api/instagram/connect")
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Connect using your own Meta app")
                    }
                }
            }

            item {
                SettingsPanel("Team") {
                    val currentRole = members?.get("currentUserRole")?.asString ?: "MEMBER"
                    members?.getAsJsonArray("members")?.asList()?.forEach { item ->
                        val m = item.asJsonObject
                        val u = m.getAsJsonObject("user")
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(u?.get("name")?.asString ?: u?.get("email")?.asString ?: "Unknown member")
                                Text(u?.get("email")?.asString ?: "", color = Color.Gray, fontSize = 11.sp)
                            }
                            Text(m.get("role")?.asString ?: "MEMBER", color = Color.Gray, fontSize = 12.sp)
                        }
                    }

                    val invitations = members?.getAsJsonArray("invitations")?.asList().orEmpty()
                    if (invitations.isNotEmpty()) {
                        Text("Pending invites", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                        invitations.forEach { item ->
                            val inv = item.asJsonObject
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(inv.get("email")?.asString ?: "")
                                    Text(inv.get("inviteUrl")?.asString ?: "", color = Color.Gray, fontSize = 10.sp, maxLines = 1)
                                }
                                TextButton(onClick = {
                                    val clip = context.getSystemService(ClipboardManager::class.java)
                                    clip?.setPrimaryClip(ClipData.newPlainText("Invite URL", inv.get("inviteUrl")?.asString ?: ""))
                                }) { Text("Copy") }
                                TextButton(onClick = {
                                    GlobalScope.launch(Dispatchers.IO) {
                                        val b = JsonObject().apply { addProperty("invitationId", inv.get("id").asString) }
                                        runCatching { api.delete("/api/workspace/members", b) }
                                        withContext(Dispatchers.Main) { load() }
                                    }
                                }) { Text("Revoke") }
                            }
                        }
                    }

                    if (currentRole == "OWNER" || currentRole == "ADMIN") {
                        Spacer(Modifier.height(8.dp))
                        Field(inviteEmail, "teammate@agency.com") { inviteEmail = it }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = inviteRole == "MEMBER", onClick = { inviteRole = "MEMBER" }, label = { Text("Member") })
                            Spacer(Modifier.width(8.dp))
                            FilterChip(selected = inviteRole == "ADMIN", onClick = { inviteRole = "ADMIN" }, label = { Text("Admin") })
                        }
                        Button(
                            enabled = !busy && inviteEmail.contains("@"),
                            onClick = {
                                busy = true
                                GlobalScope.launch(Dispatchers.IO) {
                                    val b = JsonObject().apply {
                                        addProperty("email", inviteEmail.trim())
                                        addProperty("role", inviteRole)
                                    }
                                    try {
                                        api.post("/api/workspace/members", b)
                                        withContext(Dispatchers.Main) {
                                            inviteEmail = ""
                                            busy = false
                                            load()
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            busy = false
                                            message = e.message ?: "Could not invite member."
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (busy) "Inviting..." else "Invite")
                        }
                    }
                }
            }

            item {
                SettingsPanel("Usage") {
                    Text("DMs sent this month", fontWeight = FontWeight.SemiBold)
                    Text((workspace?.get("dmsSentThisPeriod")?.asInt ?: 0).toString(), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Self-hosted — no plan limits.", color = Color.Gray, fontSize = 12.sp)
                }
            }

            item {
                message?.let { ErrorText(it) }
                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Logout, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign out")
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun StatCard(title: String, value: Int, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Color.Gray, fontSize = 12.sp)
            Text(value.toString(), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Field(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}

@Composable
private fun Loading() { Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
@Composable
private fun ErrorText(text: String) { Text(text, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
private fun JsonObject.int(name: String): Int = get(name)?.asInt ?: 0