package com.example.iptv

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** URL de VOTRE serveur d'abonnements (voir README, section « Serveur de paiement »). */
const val API_BASE = "https://api.example.com"

/** true = il faut un abonnement actif pour regarder les chaînes. */
const val REQUIRE_SUBSCRIPTION = false

enum class PayMethod(val api: String, val label: String, val hint: String) {
    CARD("card", "Carte bancaire", "Visa, Mastercard, CB…"),
    GOOGLE_PAY("google_pay", "Google Pay", "Paiement rapide avec votre compte Google"),
    APPLE_PAY("apple_pay", "Apple Pay", "Via un lien à ouvrir sur iPhone, iPad ou Mac"),
    PAYPAL("paypal", "PayPal", "Compte PayPal ou carte via PayPal"),
    PHONE("phone_topup", "Recharge téléphonique", "Débité sur votre crédit ou mobile money")
}

data class Plan(val id: String, val name: String, val months: Int, val price: String)

data class SubscriptionStatus(val active: Boolean = false, val expiresAt: Long = 0L)

data class CheckoutResult(val url: String?, val message: String?)

/** Formules affichées si le serveur est injoignable (prix indicatifs, le vrai prix vient du serveur). */
val DEFAULT_PLANS = listOf(
    Plan("monthly", "Mensuel", 1, "4,99 €"),
    Plan("quarterly", "Trimestriel", 3, "12,99 €"),
    Plan("yearly", "Annuel", 12, "44,99 €")
)

/**
 * Client du serveur d'abonnements. L'appli ne manipule JAMAIS de données de carte :
 * le serveur crée une session de paiement hébergée (Stripe, PayPal, agrégateur mobile) et renvoie son URL.
 */
object SubscriptionApi {
    private fun conn(path: String, accountId: String, method: String = "GET"): HttpURLConnection =
        (URL(API_BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("X-Account-Id", accountId)
            setRequestProperty("Accept", "application/json")
        }

    private fun HttpURLConnection.body(): String {
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            throw IllegalStateException(
                runCatching { JSONObject(text).getString("error") }.getOrDefault("Erreur serveur ($code)")
            )
        }
        return text
    }

    fun plans(accountId: String): List<Plan> {
        val arr = JSONArray(conn("/plans", accountId).body())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Plan(o.getString("id"), o.getString("name"), o.getInt("months"), o.getString("price"))
        }
    }

    fun status(accountId: String): SubscriptionStatus {
        val o = JSONObject(conn("/subscription", accountId).body())
        return SubscriptionStatus(o.optBoolean("active"), o.optLong("expires_at", 0L) * 1000)
    }

    fun checkout(accountId: String, planId: String, method: PayMethod, phone: String?): CheckoutResult {
        val c = conn("/checkout", accountId, "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val json = JSONObject().put("plan_id", planId).put("method", method.api)
        if (phone != null) json.put("phone", phone)
        c.outputStream.use { it.write(json.toString().toByteArray()) }
        val o = JSONObject(c.body())
        return CheckoutResult(
            o.optString("checkout_url").ifBlank { null },
            o.optString("message").ifBlank { null }
        )
    }
}

class BillingViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    /** Identifiant de compte anonyme. En production, remplacez-le par une vraie connexion (e-mail, Google...). */
    val accountId: String = prefs.getString("account_id", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("account_id", it).apply() }

    var plans by mutableStateOf(DEFAULT_PLANS)
        private set
    var status by mutableStateOf(SubscriptionStatus())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var pollJob: Job? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            try {
                plans = withContext(Dispatchers.IO) { SubscriptionApi.plans(accountId) }.ifEmpty { DEFAULT_PLANS }
            } catch (e: Exception) { /* on garde les formules par défaut */ }
            try {
                status = withContext(Dispatchers.IO) { SubscriptionApi.status(accountId) }
                error = null
            } catch (e: Exception) {
                error = "Serveur d'abonnement injoignable"
            }
        }
    }

    fun pay(ctx: Context, plan: Plan, method: PayMethod, phone: String) {
        if (method == PayMethod.PHONE && phone.count { it.isDigit() } < 8) {
            error = "Numéro de téléphone invalide"
            return
        }
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            busy = true
            error = null
            message = null
            val before = status.expiresAt
            try {
                val r = withContext(Dispatchers.IO) {
                    SubscriptionApi.checkout(accountId, plan.id, method, phone.takeIf { method == PayMethod.PHONE })
                }
                val url = r.url
                if (url != null) {
                    if (method == PayMethod.APPLE_PAY) shareLink(ctx, url)
                    else CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(url))
                }
                message = r.message ?: "En attente de la confirmation du paiement…"

                // On interroge le serveur pendant ~5 minutes (le serveur active l'abonnement via webhook)
                repeat(75) {
                    delay(4_000)
                    val s = withContext(Dispatchers.IO) {
                        runCatching { SubscriptionApi.status(accountId) }.getOrNull()
                    }
                    if (s != null) {
                        status = s
                        if (s.active && s.expiresAt > before) {
                            message = "Abonnement activé !"
                            busy = false
                            return@launch
                        }
                    }
                }
                message = "Paiement non confirmé. Si vous avez payé, appuyez sur « Actualiser »."
            } catch (e: Exception) {
                error = e.message ?: "Erreur de paiement"
            }
            busy = false
        }
    }

    private fun shareLink(ctx: Context, url: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Ouvrez ce lien sur un iPhone, iPad ou Mac pour payer avec Apple Pay : $url")
        ctx.startActivity(Intent.createChooser(send, "Envoyer le lien de paiement"))
    }
}
