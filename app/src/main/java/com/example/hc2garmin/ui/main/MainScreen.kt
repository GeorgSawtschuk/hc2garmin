package com.example.hc2garmin.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    vm: MainViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { _ -> vm.loadState() }

    LaunchedEffect(Unit) { vm.loadState() }

    LaunchedEffect(state.syncError) {
        state.syncError?.let {
            snackbarHostState.showSnackbar(it)
            vm.dismissError()
        }
    }

    // MFA dialog
    if (state.isMfaRequired) {
        AlertDialog(
            onDismissRequest = vm::dismissConnectDialog,
            title = { Text("Two-Factor Authentication") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Enter the 6-digit code sent to your email.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = state.mfaCode,
                        onValueChange = vm::onMfaCodeChange,
                        label = { Text("One-time code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isSubmittingMfa,
                        textStyle = LocalTextStyle.current.copy(
                            textAlign = TextAlign.Center,
                            letterSpacing = androidx.compose.ui.unit.TextUnit(4f, androidx.compose.ui.unit.TextUnitType.Sp)
                        ),
                        placeholder = {
                            Text("000000", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = vm::submitMfaCode,
                    enabled = state.mfaCode.length == 6 && !state.isSubmittingMfa
                ) {
                    if (state.isSubmittingMfa) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Verify")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissConnectDialog) { Text("Cancel") }
            }
        )
    }

    // Connect dialog
    if (state.showConnectDialog && !state.isMfaRequired) {
        AlertDialog(
            onDismissRequest = vm::dismissConnectDialog,
            title = { Text("Connect to Garmin") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Enter your Garmin Connect credentials.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = state.dialogEmail,
                        onValueChange = vm::onDialogEmailChange,
                        label = { Text("Email") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isConnecting
                    )
                    OutlinedTextField(
                        value = state.dialogPassword,
                        onValueChange = vm::onDialogPasswordChange,
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isConnecting
                    )
                    state.dialogError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = vm::connectGarmin,
                    enabled = state.dialogEmail.isNotBlank() && state.dialogPassword.isNotBlank() && !state.isConnecting
                ) {
                    if (state.isConnecting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Connecting...")
                    } else {
                        Text("Connect")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissConnectDialog) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HC2Garmin") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Status card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    StatusRow("Health Connect", state.hasHcPermission)
                    StatusRow("Garmin Connect", state.isGarminAuthenticated)
                }
            }

            // Sync info card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Last Sync", style = MaterialTheme.typography.titleMedium)
                    Text(state.lastSyncText, style = MaterialTheme.typography.bodyLarge)
                    if (state.lastSyncCount > 0) {
                        Text(
                            "${state.lastSyncCount} measurement(s) uploaded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Permission request if needed
            if (!state.hasHcPermission) {
                OutlinedButton(
                    onClick = {
                        permissionLauncher.launch(
                            setOf("android.permission.health.READ_WEIGHT")
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Health Connect Permission")
                }
            }

            // Connect to Garmin button when not authenticated
            if (!state.isGarminAuthenticated) {
                FilledTonalButton(
                    onClick = vm::showConnectDialog,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect to Garmin")
                }
            }

            // Sync now button
            Button(
                onClick = vm::triggerManualSync,
                enabled = !state.isSyncing && state.hasCredentials && state.hasHcPermission && state.isGarminAuthenticated,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isSyncing) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Syncing...")
                } else {
                    Text("Sync Now")
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                "Background sync runs automatically every hour when connected to Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (ok) Color(0xFF4CAF50) else MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.size(10.dp)
        ) {}
        Text(
            "$label: ${if (ok) "Connected" else "Not connected"}",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
