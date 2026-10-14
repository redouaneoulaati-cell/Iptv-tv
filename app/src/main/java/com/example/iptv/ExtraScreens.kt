package com.example.iptv

import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

// ---------- Bouton Chromecast ----------

@Composable
fun CastButton() {
    AndroidView(
        factory = { ctx ->
            try {
                MediaRouteButton(ctx).also { CastButtonFactory.setUpMediaRouteButton(ctx, it) }
            } catch (e: Exception) {
                View(ctx) // Google Play Services absent : pas de Cast
            }
        },
        modifier = Modifier.size(48.dp)
    )
}

// ---------- Enregistrements ----------

@Composable
fun RecordingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val active by RecordingState.active.collectAsState()
    val error by RecordingState.error.collectAsState()
    var files by remember { mutableStateOf(listRecordings(ctx)) }
    LaunchedEffect(active) { files = listRecordings(ctx) }

    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        active?.let { name ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("⏺ En cours : $name", Modifier.weight(1f), maxLines = 1)
                        TextButton(onClick = { stopRecording(ctx) }) { Text("Arrêter") }
                    }
                }
            }
        }
        error?.let { item { Text("Erreur : $it", color = MaterialTheme.colorScheme.error) } }
        if (files.isEmpty()) {
            item { Text("Aucun enregistrement. Utilisez « Enreg. » pendant la lecture d'une chaîne.") }
        }
        items(files) { f ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 1)
                        Text("${f.length() / 1_000_000} Mo", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        vm.play(Channel(null, f.name, f.toURI().toString(), null, null))
                    }) { Text("Lire") }
                    TextButton(onClick = { f.delete(); files = listRecordings(ctx) }) { Text("Supprimer") }
                }
            }
        }
    }
}

// ---------- Réglages + code parental ----------

@Composable
fun SettingsScreen(vm: MainViewModel) {
    var pinInput by remember { mutableStateOf("") }
    var currentPin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val groups = remember(vm.channels) { vm.channels.mapNotNull { it.group }.distinct().sorted() }
    val pinKeyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)

    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Text("Code parental", style = MaterialTheme.typography.titleMedium) }

        if (!vm.pinSet) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Définissez un code à 4 chiffres pour verrouiller des groupes de chaînes.")
                    OutlinedTextField(
                        pinInput, { if (it.length <= 4 && it.all(Char::isDigit)) pinInput = it },
                        label = { Text("Nouveau code") }, singleLine = true,
                        keyboardOptions = pinKeyboard,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Button(onClick = {
                        if (pinInput.length == 4) { vm.setPin(pinInput); pinInput = ""; msg = "Code défini" }
                        else msg = "Le code doit contenir 4 chiffres"
                    }) { Text("Définir le code") }
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        currentPin, { if (it.length <= 4 && it.all(Char::isDigit)) currentPin = it },
                        label = { Text("Code actuel") }, singleLine = true,
                        keyboardOptions = pinKeyboard,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            msg = if (vm.unlock(currentPin)) "Déverrouillé pendant 30 minutes" else "Code incorrect"
                            currentPin = ""
                        }) { Text("Déverrouiller") }
                        OutlinedButton(onClick = {
                            msg = if (vm.removePin(currentPin)) "Code supprimé" else "Code incorrect"
                            currentPin = ""
                        }) { Text("Supprimer le code") }
                    }
                }
            }
            item {
                Text(
                    "Les groupes dont le nom contient « adult », « xxx » ou « 18+ » sont verrouillés automatiquement.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (vm.unlocked) {
                items(groups) { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g, Modifier.weight(1f), maxLines = 1)
                        Switch(checked = g in vm.lockedGroups, onCheckedChange = { vm.toggleLockedGroup(g) })
                    }
                }
            } else {
                item { Text("Déverrouillez avec le code pour choisir les groupes à verrouiller.") }
            }
        }
        msg?.let { item { Text(it) } }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Lecture et enregistrement", style = MaterialTheme.typography.titleMedium) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Image dans l'image en quittant l'appli", Modifier.weight(1f))
                Switch(checked = vm.pipEnabled, onCheckedChange = { vm.setPip(it) })
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Durée max d'un enregistrement : ${vm.recMinutes} min", Modifier.weight(1f))
                TextButton(onClick = { vm.cycleRecMinutes() }) { Text("Changer") }
            }
        }
    }
}

// ---------- Boîte de dialogue du code ----------

@Composable
fun PinDialog(vm: MainViewModel) {
    val pending = vm.pendingChannel ?: return
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { vm.cancelPin() },
        title = { Text("Chaîne verrouillée") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pending.name)
                OutlinedTextField(
                    pin, { if (it.length <= 4 && it.all(Char::isDigit)) pin = it },
                    label = { Text("Code") }, singleLine = true,
                    isError = vm.pinError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation()
                )
                if (vm.pinError) Text("Code incorrect", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { vm.submitPin(pin); pin = "" }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { vm.cancelPin() }) { Text("Annuler") } }
    )
}
