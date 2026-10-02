package com.thehomeodoc.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
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
    DASHBOARD("Home", Icons.Default.Home),
    CAMPAIGNS("Campaigns", Icons.Default.Campaign),
    INBOX("Inbox", Icons.Default.Chat),
    ANALYTICS("Analytics", Icons.Default.Analytics),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
private fun MainShell(api: ApiClient, onLogout: () -> Unit) {
    var selected by remember { mutableStateOf(Tab.DASHBOARD) }
    Scaffold(
        containerColor = Dark,
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(selected == tab, { selected = tab }, icon = { Icon(tab.icon, null) }, label = { Text(tab.label, fontSize = 10.sp) })
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selected) {
                Tab.DASHBOARD -> DashboardScreen(api)
                Tab.CAMPAIGNS -> CampaignsScreen(api)
                Tab.INBOX -> InboxScreen(api)
                Tab.ANALYTICS -> AnalyticsScreen(api)
                Tab.SETTINGS -> SettingsScreen(api, onLogout)
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun CampaignsScreen(api: ApiClient) {
    var campaigns by remember { mutableStateOf<JsonArray?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun load() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val d = api.get("/api/automations").getAsJsonArray("data")
                withContext(Dispatchers.Main) { campaigns = d }
            } catch (e: Exception) { withContext(Dispatchers.Main) { message = e.message } }
        }
    }
    LaunchedEffect(Unit) { load() }
    Screen("Campaigns", ::load) {
        Button(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth()) { Text("Create campaign") }
        Spacer(Modifier.height(12.dp))
        message?.let { ErrorText(it) }
        campaigns?.let { list ->
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list.asList()) { item ->
                    val c = item.asJsonObject
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(c.get("name")?.asString ?: "Campaign", fontWeight = FontWeight.Bold)
                            Text(if (c.get("isActive")?.asBoolean == true) "Active" else "Paused", color = if (c.get("isActive")?.asBoolean == true) Color(0xFF7DD3A5) else Color.Gray)
                            Text("DMs sent: ${c.getAsJsonObject("analytics")?.get("sent")?.asInt ?: 0} • Clicks: ${c.getAsJsonObject("analytics")?.get("clicks")?.asInt ?: 0}", color = Color.Gray)
                            Text(c.getAsJsonArray("keywords")?.asList()?.joinToString(", ") ?: "Any word", fontSize = 12.sp, color = Accent)
                        }
                    }
                }
            }
        } ?: Loading()
    }
    if (showCreate) CreateCampaignDialog(api, { showCreate = false; load() })
}

@Composable
private fun CreateCampaignDialog(api: ApiClient, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf("") }
    var dm by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDone() },
        title = { Text("New campaign") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(name, "Campaign name") { name = it }
                Field(keyword, "Keyword") { keyword = it }
                Field(dm, "DM message") { dm = it }
                Field(link, "Optional link") { link = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(follow, { follow = it }); Text("Require follow")
                }
                error?.let { ErrorText(it) }
            }
        },
        confirmButton = {
            Button(enabled = !saving, onClick = {
                saving = true
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        val body = JsonObject().apply {
                            addProperty("name", name.trim())
                            addProperty("matchAnyPost", true)
                            add("keywords", JsonArray().apply { add(keyword.trim()) })
                            addProperty("dmMessage", dm.trim())
                            addProperty("requireFollow", follow)
                            addProperty("isActive", true)
                            addProperty("wholeWordMatch", true)
                            if (link.isNotBlank()) addProperty("trackedDestinationUrl", link.trim())
                        }
                        api.post("/api/automations", body)
                        withContext(Dispatchers.Main) { onDone() }
                    } catch (e: Exception) { withContext(Dispatchers.Main) { error = e.message; saving = false } }
                }
            }) { Text(if (saving) "Saving…" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDone, enabled = !saving) { Text("Cancel") } }
    )
}

@Composable
private fun InboxScreen(api: ApiClient) {
    var conversations by remember { mutableStateOf<JsonArray?>(null) }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun load() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val d = api.get("/api/instagram/conversations").getAsJsonObject("data")
                withContext(Dispatchers.Main) { conversations = d.getAsJsonArray("conversations"); error = null }
            } catch (e: Exception) { withContext(Dispatchers.Main) { error = e.message } }
        }
    }
    LaunchedEffect(Unit) { load() }
    Screen("Inbox", ::load) {
        error?.let { ErrorText(it) }
        selected?.let { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selected = null }) { Text("← Back") }
                Text(c.getAsJsonObject("contact")?.get("username")?.asString ?: "Conversation", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                Text(c.getAsJsonObject("lastMessage")?.get("text")?.asString ?: "No message", Modifier.padding(16.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Reply…") })
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val recipient = c.getAsJsonObject("contact")?.get("id")?.asString ?: return@Button
                    val body = JsonObject().apply { addProperty("recipientId", recipient); addProperty("text", text) }
                    GlobalScope.launch(Dispatchers.IO) {
                        runCatching { api.post("/api/instagram/conversations", body) }
                        withContext(Dispatchers.Main) { text = ""; load() }
                    }
                }) { Text("Send") }
            }
        } ?: run {
            conversations?.let { list ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list.asList()) { item ->
                        val c = item.asJsonObject
                        Card(onClick = { selected = c }, colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(c.getAsJsonObject("contact")?.get("username")?.asString ?: "Instagram user", fontWeight = FontWeight.Bold)
                                Text(c.getAsJsonObject("lastMessage")?.get("text")?.asString ?: "No messages", color = Color.Gray, maxLines = 2)
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    fun load() {
        GlobalScope.launch(Dispatchers.IO) {
            runCatching { api.get("/api/instagram/accounts").getAsJsonObject("data").getAsJsonArray("instagramAccounts") }.onSuccess {
                GlobalScope.launch(Dispatchers.Main) { accounts = it }
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    Screen("Settings", ::load) {
        Text("Instagram accounts", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        accounts?.let { list ->
            if (list.size() == 0) Text("No Instagram account connected.", color = Color.Gray)
            list.asList().forEach { item ->
                val a = item.asJsonObject
                Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("@${a.get("username")?.asString ?: ""}", fontWeight = FontWeight.Bold)
                            Text(a.get("name")?.asString ?: "", color = Color.Gray)
                        }
                        TextButton(onClick = {
                            GlobalScope.launch(Dispatchers.IO) {
                                val b = JsonObject().apply { addProperty("instagramAccountId", a.get("id").asString) }
                                runCatching { api.post("/api/instagram/disconnect", b) }
                                withContext(Dispatchers.Main) { load() }
                            }
                        }) { Text("Disconnect") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        } ?: Loading()
        Button(onClick = {
            val uri = Uri.parse("${BuildConfig.API_BASE_URL}/api/instagram/connect")
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
        }, modifier = Modifier.fillMaxWidth()) { Text("Connect Instagram") }
        Spacer(Modifier.height(18.dp))
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
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
