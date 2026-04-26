package com.example.hc2garmin.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.testResult) {
        when (val r = state.testResult) {
            is TestResult.Success -> {
                snackbarHostState.showSnackbar("Connected to Garmin successfully!")
                vm.dismissTestResult()
            }
            is TestResult.Error -> {
                snackbarHostState.showSnackbar(r.message)
                vm.dismissTestResult()
            }
            null -> Unit
        }
    }

    // MFA dialog
    if (state.isMfaRequired) {
        AlertDialog(
            onDismissRequest = vm::dismissMfa,
            title = { Text("Two-Factor Authentication") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Enter the 6-digit code from your authenticator app or SMS.",
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
                        placeholder = { Text("000000", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
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
                TextButton(onClick = vm::dismissMfa) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Garmin Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                "Enter your Garmin Connect credentials. These are stored securely on your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = state.email,
                onValueChange = vm::onEmailChange,
                label = { Text("Garmin Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isTesting
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = vm::onPasswordChange,
                label = { Text("Garmin Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isTesting
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { vm.saveCredentials(); onBack() },
                    modifier = Modifier.weight(1f),
                    enabled = !state.isTesting && state.email.isNotBlank() && state.password.isNotBlank()
                ) {
                    Text("Save")
                }

                OutlinedButton(
                    onClick = vm::testConnection,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isTesting && !state.isBlocked && state.email.isNotBlank() && state.password.isNotBlank()
                ) {
                    if (state.isTesting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Connecting...")
                    } else {
                        Text("Connect to Garmin")
                    }
                }
            }

            val attemptsColor = when {
                state.isBlocked -> MaterialTheme.colorScheme.error
                state.attemptsLeft <= 1 -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = if (state.isBlocked)
                    "Login blocked: too many attempts. Try again in 1 hour."
                else
                    "${state.attemptsLeft} of ${state.maxAttempts} login attempts remaining",
                style = MaterialTheme.typography.bodySmall,
                color = attemptsColor
            )
        }
    }
}
