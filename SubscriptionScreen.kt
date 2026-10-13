package com.example.iptv

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SubscriptionScreen(billing: BillingViewModel) {
    val ctx = LocalContext.current
    var planId by remember { mutableStateOf<String?>(null) }
    var method by remember { mutableStateOf(PayMethod.CARD) }
    var phone by remember { mutableStateOf("") }
    val plan = billing.plans.firstOrNull { it.id == planId } ?: billing.plans.firstOrNull()
    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // --- Statut ---
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mon abonnement", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (billing.status.active)
                                "Actif jusqu'au ${dateFmt.format(Date(billing.status.expiresAt))}"
                            else "Aucun abonnement actif"
                        )
                    }
                    TextButton(onClick = { billing.refresh() }) { Text("Actualiser") }
                }
            }
        }

        // --- Formules ---
        item { Text("Choisissez une formule", style = MaterialTheme.typography.titleMedium) }
        items(billing.plans) { p ->
            val selected = p.id == plan?.id
            Card(
                Modifier.fillMaxWidth().clickable { planId = p.id },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = { planId = p.id })
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        Text("${p.months} mois", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(p.price, style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        // --- Moyens de paiement ---
        item { Text("Moyen de paiement", style = MaterialTheme.typography.titleMedium) }
        items(PayMethod.entries) { m ->
            Row(
                Modifier.fillMaxWidth().clickable { method = m },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = method == m, onClick = { method = m })
                Column {
                    Text(m.label)
                    Text(m.hint, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (method == PayMethod.PHONE) {
            item {
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Numéro de téléphone (avec indicatif)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
            }
        }

        // --- Paiement ---
        item {
            Button(
                onClick = { plan?.let { billing.pay(ctx, it, method, phone) } },
                enabled = !billing.busy && plan != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (billing.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Payer ${plan?.price.orEmpty()}")
            }
        }
        billing.message?.let { item { Text(it) } }
        billing.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item {
            Text(
                "Paiement sécurisé : les données de carte ne transitent jamais par l'application.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
