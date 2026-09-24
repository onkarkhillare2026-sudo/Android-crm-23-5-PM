package com.example.callbridge

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private sealed interface CrmPage {
    data object Leads : CrmPage
    data class LeadForm(val lead: Lead? = null) : CrmPage
    data class History(val lead: Lead) : CrmPage
    data class Disposition(val lead: Lead, val durationSeconds: Int) : CrmPage
}

@Composable
fun CallBridgeApp(
    api: CrmApi,
    bridgeStatus: String,
    bridgeServerUrl: String,
    isPollingActive: Boolean,
    onTogglePolling: () -> Unit,
    onUpdateBridgeUrl: (String) -> Unit,
    postCall: PostCallRequest?,
    onPostCallConsumed: () -> Unit,
    onCallLead: (Lead) -> Unit,
    onCheckBridge: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf("crm") }

    LaunchedEffect(postCall?.nonce) {
        if (postCall != null) selectedTab = "crm"
    }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "OAKsphere CallBridge",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (api.isDemoMode) "Demo Offline Mode" else "Mobile calling + mini CRM",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (api.isDemoMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        Button(
                            onClick = { selectedTab = "crm" },
                            colors = if (selectedTab == "crm") ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.testTag("tab_crm_button")
                        ) {
                            Text("CRM")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { selectedTab = "bridge" },
                            colors = if (selectedTab == "bridge") ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.testTag("tab_bridge_button")
                        ) {
                            Text("Bridge")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (selectedTab == "bridge") {
                BridgeScreen(
                    status = bridgeStatus,
                    serverUrl = bridgeServerUrl,
                    isPollingActive = isPollingActive,
                    onTogglePolling = onTogglePolling,
                    onUpdateUrl = onUpdateBridgeUrl,
                    onCheckBridge = onCheckBridge,
                )
            } else {
                CrmRoot(
                    api = api,
                    postCall = postCall,
                    onPostCallConsumed = onPostCallConsumed,
                    onCallLead = onCallLead,
                )
            }
        }
    }
}

@Composable
private fun BridgeScreen(
    status: String,
    serverUrl: String,
    isPollingActive: Boolean,
    onTogglePolling: () -> Unit,
    onUpdateUrl: (String) -> Unit,
    onCheckBridge: () -> Unit,
) {
    var editingUrl by remember(serverUrl) { mutableStateOf(serverUrl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "PC-to-Phone Calling Bridge",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "When active, your web CRM sends call targets to this phone, triggering direct cellular SIM calls automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Bridge Service Endpoint", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = editingUrl,
                    onValueChange = { editingUrl = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://192.168.1.100:5000") },
                    modifier = Modifier.fillMaxWidth().testTag("bridge_url_input"),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { onUpdateUrl(editingUrl) },
                        enabled = editingUrl.isNotBlank() && editingUrl != serverUrl,
                        modifier = Modifier.testTag("save_bridge_url_button")
                    ) {
                        Text("Save URL")
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatic Bridge Polling", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (isPollingActive) "Active (polling every 3.5s)" else "Inactive (standby)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isPollingActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isPollingActive,
                        onCheckedChange = { onTogglePolling() },
                        modifier = Modifier.testTag("bridge_polling_switch")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Button(
                    onClick = onCheckBridge,
                    modifier = Modifier.fillMaxWidth().testTag("check_bridge_now_button"),
                ) {
                    Text("Check For Pending Call Now")
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "ℹ",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Status", style = MaterialTheme.typography.labelSmall)
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun CrmRoot(
    api: CrmApi,
    postCall: PostCallRequest?,
    onPostCallConsumed: () -> Unit,
    onCallLead: (Lead) -> Unit,
) {
    var loggedIn by remember { mutableStateOf(api.hasSession()) }
    var page: CrmPage by remember { mutableStateOf(CrmPage.Leads) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val rootScope = rememberCoroutineScope()

    LaunchedEffect(postCall?.nonce) {
        if (postCall != null) {
            page = CrmPage.Disposition(postCall.lead, postCall.durationSeconds)
            onPostCallConsumed()
        }
    }

    if (!loggedIn) {
        LoginScreen(
            api = api,
            onLoggedIn = {
                loggedIn = true
                page = CrmPage.Leads
            }
        )
        return
    }

    when (val p = page) {
        is CrmPage.Leads -> LeadsScreen(
            api = api,
            refreshKey = refreshKey,
            onAdd = { page = CrmPage.LeadForm(null) },
            onEdit = { page = CrmPage.LeadForm(it) },
            onHistory = { page = CrmPage.History(it) },
            onCall = onCallLead,
            onLogout = {
                rootScope.launch(Dispatchers.IO) { api.logout() }
                loggedIn = false
            },
            onSessionExpired = { loggedIn = false }
        )
        is CrmPage.LeadForm -> LeadFormScreen(
            api = api,
            lead = p.lead,
            onBack = { page = CrmPage.Leads },
            onSaved = {
                refreshKey++
                page = CrmPage.Leads
            }
        )
        is CrmPage.History -> CallHistoryScreen(
            api = api,
            lead = p.lead,
            onBack = { page = CrmPage.Leads }
        )
        is CrmPage.Disposition -> DispositionScreen(
            api = api,
            lead = p.lead,
            durationSeconds = p.durationSeconds,
            onBack = { page = CrmPage.Leads },
            onSaved = {
                refreshKey++
                page = CrmPage.Leads
            }
        )
    }
}

@Composable
private fun LoginScreen(api: CrmApi, onLoggedIn: () -> Unit) {
    var server by remember { mutableStateOf(api.baseUrl.takeIf { it != "https://demo.oaksphere.local" } ?: "") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Connect to OAKsphere CRM",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Access leads and log SIM calls directly into your recruitment dashboard.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        // Quick Demo Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    api.startDemoSession()
                    onLoggedIn()
                }
                .testTag("demo_mode_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "▶",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Explore with Offline Demo Mode",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Test full leads list, dialer integration & dispositions immediately without server setup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                " OR SIGN IN TO CRM ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text("CRM Server URL") },
            placeholder = { Text("https://your-crm-server.com") },
            modifier = Modifier.fillMaxWidth().testTag("server_url_input"),
            singleLine = true,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Recruiter Email") },
            modifier = Modifier.fillMaxWidth().testTag("email_input"),
            singleLine = true,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth().testTag("password_input"),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                if (server.isBlank() || email.isBlank() || password.isBlank()) {
                    error = "Server URL, email and password are required."
                    return@Button
                }
                scope.launch {
                    loading = true
                    error = null
                    try {
                        withContext(Dispatchers.IO) { api.login(server, email, password) }
                        onLoggedIn()
                    } catch (e: Exception) {
                        error = e.message ?: "Login failed"
                    } finally {
                        loading = false
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("sign_in_button"),
        ) {
            if (loading) CircularProgressIndicator(
                modifier = Modifier.width(20.dp).height(20.dp),
                strokeWidth = 2.dp
            )
            else Text("Sign In to CRM")
        }
    }
}

@Composable
private fun LeadsScreen(
    api: CrmApi,
    refreshKey: Int,
    onAdd: () -> Unit,
    onEdit: (Lead) -> Unit,
    onHistory: (Lead) -> Unit,
    onCall: (Lead) -> Unit,
    onLogout: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    var leads by remember { mutableStateOf<List<Lead>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey, searchNonce) {
        loading = true
        error = null
        try {
            leads = withContext(Dispatchers.IO) { api.listLeads(search) }
        } catch (e: CrmApiException) {
            error = e.message
            if (e.statusCode == 401) onSessionExpired()
        } catch (e: Exception) {
            error = e.message ?: "Unable to load leads"
        } finally {
            loading = false
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.testTag("add_lead_fab")
            ) {
                Text(
                    "+",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Recruiter Leads",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onLogout,
                    modifier = Modifier.testTag("sign_out_button")
                ) {
                    Text(if (api.isDemoMode) "Exit Demo" else "Sign out")
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search by name, phone or company...") },
                    modifier = Modifier.weight(1f).testTag("leads_search_input"),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { searchNonce++ },
                    modifier = Modifier.testTag("leads_search_button")
                ) {
                    Text("Search")
                }
            }

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))

            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (leads.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No leads found. Tap + to create one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(leads, key = { it.id }) { lead ->
                        LeadCard(
                            lead = lead,
                            onCall = { onCall(lead) },
                            onEdit = { onEdit(lead) },
                            onHistory = { onHistory(lead) },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LeadCard(
    lead: Lead,
    onCall: () -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("lead_card_${lead.id}"),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(lead.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(lead.phone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    if (lead.company.isNotBlank()) {
                        Text(lead.company, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(
                    onClick = onCall,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("call_lead_button_${lead.id}")
                ) {
                    Text("Call")
                }
            }

            if (lead.lastCallOutcome != null || lead.status.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Status: ${lead.status.replace('_', ' ').capitalize(Locale.US)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Calls: ${lead.callCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onHistory, modifier = Modifier.testTag("history_button_${lead.id}")) {
                    Text("History")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onEdit, modifier = Modifier.testTag("edit_button_${lead.id}")) {
                    Text("Edit")
                }
            }
        }
    }
}

@Composable
private fun LeadFormScreen(
    api: CrmApi,
    lead: Lead?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    var name by remember { mutableStateOf(lead?.name ?: "") }
    var phone by remember { mutableStateOf(lead?.phone ?: "") }
    var company by remember { mutableStateOf(lead?.company ?: "") }
    var notes by remember { mutableStateOf(lead?.notes ?: "") }
    var duplicateAck by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(8.dp))
            Text(
                if (lead == null) "New Candidate Lead" else "Edit Lead",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Candidate Name *") },
            modifier = Modifier.fillMaxWidth().testTag("lead_name_input"),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone Number *") },
            modifier = Modifier.fillMaxWidth().testTag("lead_phone_input"),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = company,
            onValueChange = { company = it },
            label = { Text("Company / Client") },
            modifier = Modifier.fillMaxWidth().testTag("lead_company_input"),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Recruiter Notes") },
            modifier = Modifier.fillMaxWidth().testTag("lead_notes_input"),
            minLines = 3,
        )
        if (lead == null) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = duplicateAck, onCheckedChange = { duplicateAck = it })
                Text("Allow saving duplicate phone number if already exists", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                if (name.isBlank() || phone.isBlank()) {
                    error = "Name and phone are required."
                    return@Button
                }
                scope.launch {
                    loading = true
                    error = null
                    try {
                        withContext(Dispatchers.IO) {
                            if (lead == null) api.createLead(name, phone, company, notes, duplicateAck)
                            else api.updateLead(lead.id, name, phone, company, notes)
                        }
                        onSaved()
                    } catch (e: Exception) {
                        error = e.message ?: "Unable to save lead"
                    } finally {
                        loading = false
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("save_lead_button"),
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
            else Text("Save Lead")
        }
    }
}

@Composable
private fun CallHistoryScreen(api: CrmApi, lead: Lead, onBack: () -> Unit) {
    var calls by remember { mutableStateOf<List<CallHistoryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(lead.id) {
        loading = true
        try {
            calls = withContext(Dispatchers.IO) { api.callHistory(lead.id) }
        } catch (e: Exception) {
            error = e.message ?: "Unable to load call logs"
        } finally {
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(8.dp))
            Text("Call History: ${lead.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        } else if (calls.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No calls recorded for this candidate yet.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(calls, key = { it.id }) { call ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(call.outcomeLabel, fontWeight = FontWeight.Bold)
                                Text(formatDuration(call.durationSeconds), style = MaterialTheme.typography.bodySmall)
                            }
                            if (call.notes.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(call.notes, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(formatTimestamp(call.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DispositionScreen(
    api: CrmApi,
    lead: Lead,
    durationSeconds: Int,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    var metadata by remember { mutableStateOf<DispositionMetadata?>(null) }
    var selectedOption by remember { mutableStateOf<DispositionOption?>(null) }
    var notes by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf(durationSeconds.toString()) }
    var followupAt by remember { mutableStateOf("") }
    var followupReason by remember { mutableStateOf("") }
    var closureReason by remember { mutableStateOf("") }
    var expectedJoining by remember { mutableStateOf("") }

    var interviewAt by remember { mutableStateOf("") }
    var interviewClient by remember { mutableStateOf(lead.company) }
    var interviewJob by remember { mutableStateOf("") }
    var interviewType by remember { mutableStateOf("telephonic") }
    var interviewLocation by remember { mutableStateOf("") }
    var interviewContact by remember { mutableStateOf("") }

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(lead.id) {
        loading = true
        try {
            val meta = withContext(Dispatchers.IO) { api.dispositionMetadata(lead.id) }
            metadata = meta
            selectedOption = meta.allOptions.firstOrNull()
        } catch (e: Exception) {
            error = e.message ?: "Failed loading disposition options"
        } finally {
            loading = false
        }
    }

    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Text("Call Disposition: ${lead.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Call Summary", fontWeight = FontWeight.Bold)
                Text("Phone: ${lead.phone}")
                Text("Duration: ${formatDuration(duration.toIntOrNull() ?: 0)}")
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Select Call Outcome *", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        metadata?.groups?.forEach { group ->
            Text(group.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            group.options.forEach { opt ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedOption = opt }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectedOption?.value == opt.value) "● " else "○ ",
                        color = if (selectedOption?.value == opt.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(opt.label, fontWeight = if (selectedOption?.value == opt.value) FontWeight.Bold else FontWeight.Normal)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Discussion Notes") },
            modifier = Modifier.fillMaxWidth().testTag("disposition_notes_input"),
            minLines = 2,
        )

        selectedOption?.let { opt ->
            if (opt.requiresFollowup) {
                Spacer(Modifier.height(12.dp))
                Text("Follow-up Details", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row {
                    OutlinedButton(onClick = { followupAt = isoAfterHours(2) }) { Text("+2 Hours") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { followupAt = isoTomorrowAt10() }) { Text("Tomorrow 10 AM") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = followupAt,
                    onValueChange = { followupAt = it },
                    label = { Text("Follow-up Time (ISO format)") },
                    modifier = Modifier.fillMaxWidth().testTag("followup_time_input"),
                    singleLine = true,
                )
            }

            if (opt.requiresInterview) {
                Spacer(Modifier.height(12.dp))
                Text("Schedule Interview", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = interviewAt,
                    onValueChange = { interviewAt = it },
                    label = { Text("Interview Date/Time (ISO format) *") },
                    modifier = Modifier.fillMaxWidth().testTag("interview_time_input"),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = interviewJob,
                    onValueChange = { interviewJob = it },
                    label = { Text("Job Title / Role *") },
                    modifier = Modifier.fillMaxWidth().testTag("interview_job_input"),
                    singleLine = true,
                )
            }

            if (opt.requiresClosureReason) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = closureReason,
                    onValueChange = { closureReason = it },
                    label = { Text("Reason for Rejection / Closure *") },
                    modifier = Modifier.fillMaxWidth().testTag("closure_reason_input"),
                )
            }

            if (opt.requiresExpectedJoiningDate) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = expectedJoining,
                    onValueChange = { expectedJoining = it },
                    label = { Text("Expected Joining Date (YYYY-MM-DD) *") },
                    modifier = Modifier.fillMaxWidth().testTag("joining_date_input"),
                )
            }
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                val opt = selectedOption ?: return@Button
                if (opt.requiresFollowup && followupAt.isBlank()) {
                    error = "A next follow-up date/time is required."
                    return@Button
                }
                if (opt.requiresClosureReason && closureReason.isBlank()) {
                    error = "A closure reason is required."
                    return@Button
                }
                if (opt.requiresExpectedJoiningDate && expectedJoining.isBlank()) {
                    error = "Expected joining date is required."
                    return@Button
                }
                if (opt.requiresInterview && (interviewAt.isBlank() || interviewJob.isBlank())) {
                    error = "Interview time and job role are required."
                    return@Button
                }
                scope.launch {
                    saving = true
                    error = null
                    try {
                        val payload = JSONObject()
                            .put("outcome", opt.value)
                            .put("duration_seconds", duration.toIntOrNull() ?: 0)
                            .put("notes", jsonNullable(notes))
                            .put("next_followup_at", jsonNullable(followupAt))
                            .put("closure_reason", jsonNullable(closureReason))
                            .put("expected_joining_date", jsonNullable(expectedJoining))
                        if (opt.requiresInterview) {
                            payload.put(
                                "interview", JSONObject()
                                    .put("scheduled_at", interviewAt.trim())
                                    .put("client", interviewClient.trim())
                                    .put("job", interviewJob.trim())
                                    .put("type", interviewType)
                                    .put("location", jsonNullable(interviewLocation))
                                    .put("contact_person", jsonNullable(interviewContact))
                            )
                        }
                        withContext(Dispatchers.IO) { api.saveDisposition(lead.id, payload) }
                        onSaved()
                    } catch (e: Exception) {
                        error = e.message ?: "Unable to save disposition"
                    } finally {
                        saving = false
                    }
                }
            },
            enabled = !saving && selectedOption != null,
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("save_disposition_button"),
        ) {
            Text(if (saving) "Saving..." else "Submit Call Disposition")
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun formatDuration(seconds: Int): String {
    val min = seconds / 60
    val sec = seconds % 60
    return if (min > 0) "${min}m ${sec}s" else "${sec}s"
}

private fun formatTimestamp(value: String): String = value.replace('T', ' ').replace("Z", " UTC")

private fun isoAfterHours(hours: Int): String {
    val cal = Calendar.getInstance()
    cal.add(Calendar.HOUR_OF_DAY, hours)
    return isoFormat(cal.time)
}

private fun isoTomorrowAt10(): String {
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, 1)
    cal.set(Calendar.HOUR_OF_DAY, 10)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return isoFormat(cal.time)
}

private fun isoFormat(date: Date): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(date)

private fun jsonNullable(value: String): Any = if (value.trim().isBlank()) JSONObject.NULL else value.trim()
