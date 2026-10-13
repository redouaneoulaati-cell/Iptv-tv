'use strict';
require('dotenv').config();
const express = require('express');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const Stripe = require('stripe');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const { randomUUID } = require('node:crypto');
const { Pool } = require('pg');

const app = express();
const port = Number(process.env.PORT || 8080);
const publicBaseUrl = (process.env.PUBLIC_BASE_URL || '').replace(/\/$/, '');
const stripe = process.env.STRIPE_SECRET_KEY ? new Stripe(process.env.STRIPE_SECRET_KEY) : null;
const paypalBase = process.env.PAYPAL_MODE === 'live'
  ? 'https://api-m.paypal.com' : 'https://api-m.sandbox.paypal.com';
const pool = process.env.DATABASE_URL ? new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: process.env.DATABASE_SSL === 'true' ? { rejectUnauthorized: true } : undefined,
  max: 10,
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 5000
}) : null;

const { PLANS } = require('./plans');

app.disable('x-powered-by');
app.use(helmet());
app.use(rateLimit({ windowMs: 60_000, limit: 120, standardHeaders: 'draft-7', legacyHeaders: false }));

// Stripe requires the exact raw bytes for signature verification; this route MUST be before express.json().
app.post('/webhooks/stripe', express.raw({ type: 'application/json', limit: '1mb' }), async (req, res) => {
  if (!stripe || !process.env.STRIPE_WEBHOOK_SECRET) return res.status(503).send('Stripe webhook not configured');
  let event;
  try {
    event = stripe.webhooks.constructEvent(req.body, req.headers['stripe-signature'], process.env.STRIPE_WEBHOOK_SECRET);
  } catch (err) {
    return res.status(400).send(`Invalid Stripe signature: ${err.message}`);
  }
  try {
    if (await eventWasProcessed('stripe', event.id)) return res.json({ received: true, duplicate: true });
    await handleStripeEvent(event);
    await recordEvent('stripe', event.id);
    return res.json({ received: true });
  } catch (err) {
    console.error('Stripe webhook processing failed:', err.message);
    return res.status(500).send('Webhook processing failed');
  }
});

app.use(express.json({ limit: '100kb' }));

app.get('/health', async (_req, res) => {
  try {
    if (!pool) throw new Error('DATABASE_URL is not configured');
    await pool.query('SELECT 1');
    res.json({ ok: true, database: 'connected' });
  } catch (_e) { res.status(503).json({ ok: false, database: 'unavailable' }); }
});

app.get('/plans', (_req, res) => res.json(Object.values(PLANS).map(({ stripeEnv, paypalEnv, ...p }) => p)));

const authLimiter = rateLimit({ windowMs: 15 * 60_000, limit: 10, standardHeaders: 'draft-7', legacyHeaders: false });
function authConfigReady() {
  return Boolean(pool && process.env.AUTH_JWT_SECRET && process.env.AUTH_JWT_SECRET.length >= 32);
}
function signUserToken(user) {
  if (!process.env.AUTH_JWT_SECRET || process.env.AUTH_JWT_SECRET.length < 32) throw new Error('AUTH_JWT_SECRET must contain at least 32 characters');
  return jwt.sign({ email: user.email }, process.env.AUTH_JWT_SECRET, { subject: user.id, expiresIn: process.env.JWT_EXPIRES_IN || '1h', issuer: 'iptv-api', audience: 'iptv-android' });
}
function requireAccount(req, res, next) {
  if (!authConfigReady()) return res.status(503).json({ error: 'Authentification indisponible: configurez DATABASE_URL et AUTH_JWT_SECRET' });
  const header = String(req.get('Authorization') || '');
  const match = header.match(/^Bearer\s+(.+)$/i);
  if (!match) return res.status(401).json({ error: 'Jeton Bearer requis' });
  try {
    const claims = jwt.verify(match[1], process.env.AUTH_JWT_SECRET, { issuer: 'iptv-api', audience: 'iptv-android' });
    if (!claims.sub) return res.status(401).json({ error: 'Jeton invalide' });
    req.accountId = claims.sub;
    req.userEmail = claims.email;
    next();
  } catch (_err) { return res.status(401).json({ error: 'Jeton expiré ou invalide' }); }
}

app.post('/auth/register', authLimiter, async (req, res) => {
  if (!authConfigReady()) return res.status(503).json({ error: 'Configurez DATABASE_URL et AUTH_JWT_SECRET (32 caractères minimum)' });
  const email = String(req.body?.email || '').trim().toLowerCase();
  const password = String(req.body?.password || '');
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254) return res.status(400).json({ error: 'Adresse e-mail invalide' });
  if (password.length < 12 || password.length > 128) return res.status(400).json({ error: 'Le mot de passe doit contenir de 12 à 128 caractères' });
  try {
    const id = randomUUID();
    const hash = await bcrypt.hash(password, 12);
    await db().query('INSERT INTO users(id,email,password_hash) VALUES($1,$2,$3)', [id, email, hash]);
    const user = { id, email };
    return res.status(201).json({ token: signUserToken(user), token_type: 'Bearer', expires_in: process.env.JWT_EXPIRES_IN || '1h', user: { id, email } });
  } catch (err) {
    if (err.code === '23505') return res.status(409).json({ error: 'Un compte existe déjà avec cette adresse e-mail' });
    console.error('Registration failed:', err.message);
    return res.status(503).json({ error: 'Création du compte indisponible' });
  }
});

app.post('/auth/login', authLimiter, async (req, res) => {
  if (!authConfigReady()) return res.status(503).json({ error: 'Authentification indisponible' });
  const email = String(req.body?.email || '').trim().toLowerCase();
  const password = String(req.body?.password || '');
  if (!email || !password || email.length > 254 || password.length > 128) return res.status(400).json({ error: 'Identifiants invalides' });
  try {
    const result = await db().query('SELECT id,email,password_hash FROM users WHERE email=$1', [email]);
    const user = result.rows[0];
    if (!user || !(await bcrypt.compare(password, user.password_hash))) return res.status(401).json({ error: 'E-mail ou mot de passe incorrect' });
    return res.json({ token: signUserToken(user), token_type: 'Bearer', expires_in: process.env.JWT_EXPIRES_IN || '1h', user: { id: user.id, email: user.email } });
  } catch (err) {
    console.error('Login failed:', err.message);
    return res.status(503).json({ error: 'Connexion indisponible' });
  }
});

app.get('/subscription', requireAccount, async (req, res) => {
  try {
    const result = await db().query('SELECT active, expires_at FROM subscriptions WHERE account_id=$1', [req.accountId]);
    const row = result.rows[0];
    const expiresAt = row ? Number(row.expires_at) : 0;
    res.json({ active: Boolean(row && row.active && expiresAt > Math.floor(Date.now() / 1000)), expires_at: expiresAt });
  } catch (err) { console.error(err.message); res.status(503).json({ error: 'Service abonnement indisponible' }); }
});

app.post('/checkout', requireAccount, async (req, res) => {
  const { plan_id: planId, method } = req.body || {};
  const plan = PLANS[planId];
  if (!plan) return res.status(400).json({ error: 'Formule inconnue' });
  try {
    if (method === 'card' || method === 'google_pay' || method === 'apple_pay') {
      if (!stripe) return res.status(503).json({ error: 'Stripe n’est pas configuré' });
      const price = process.env[plan.stripeEnv];
      if (!price) return res.status(503).json({ error: `Configurez ${plan.stripeEnv}` });
      if (!publicBaseUrl.startsWith('https://')) return res.status(503).json({ error: 'PUBLIC_BASE_URL HTTPS requis' });
      const session = await stripe.checkout.sessions.create({
        mode: 'subscription',
        line_items: [{ price, quantity: 1 }],
        client_reference_id: req.accountId,
        metadata: { account_id: req.accountId, plan_id: plan.id },
        subscription_data: { metadata: { account_id: req.accountId, plan_id: plan.id } },
        success_url: `${publicBaseUrl}/payment/success?session_id={CHECKOUT_SESSION_ID}`,
        cancel_url: `${publicBaseUrl}/payment/cancelled`
      });
      return res.json({ checkout_url: session.url, message: 'Terminez le paiement sur la page sécurisée Stripe.' });
    }
    if (method === 'paypal') {
      const paypalPlanId = process.env[plan.paypalEnv];
      if (!paypalPlanId) return res.status(503).json({ error: `Configurez ${plan.paypalEnv}` });
      const accessToken = await paypalAccessToken();
      const response = await fetch(`${paypalBase}/v1/billing/subscriptions`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json', Prefer: 'return=representation' },
        body: JSON.stringify({
          plan_id: paypalPlanId,
          custom_id: req.accountId,
          application_context: {
            brand_name: 'IPTV',
            user_action: 'SUBSCRIBE_NOW',
            return_url: `${publicBaseUrl}/payment/paypal/success`,
            cancel_url: `${publicBaseUrl}/payment/cancelled`
          }
        })
      });
      const body = await response.json();
      if (!response.ok) { console.error('PayPal create subscription:', body); return res.status(502).json({ error: 'Impossible de créer la souscription PayPal' }); }
      const approval = (body.links || []).find(x => x.rel === 'approve');
      if (!approval) return res.status(502).json({ error: 'Lien d’approbation PayPal absent' });
      return res.json({ checkout_url: approval.href, message: 'Approuvez la souscription sur PayPal.' });
    }
    if (method === 'phone_topup') return res.status(501).json({ error: 'La recharge téléphonique nécessite un agrégateur de paiement compatible avec votre pays.' });
    return res.status(400).json({ error: 'Méthode de paiement non prise en charge' });
  } catch (err) {
    console.error('Checkout failed:', err.message);
    return res.status(502).json({ error: 'Impossible de créer la session de paiement' });
  }
});

app.post('/webhooks/paypal', async (req, res) => {
  if (!process.env.PAYPAL_WEBHOOK_ID || !process.env.PAYPAL_CLIENT_ID || !process.env.PAYPAL_CLIENT_SECRET) {
    return res.status(503).json({ error: 'Webhook PayPal non configuré' });
  }
  try {
    if (!(await verifyPayPalWebhook(req.headers, req.body))) return res.status(400).json({ error: 'Signature PayPal invalide' });
    const event = req.body;
    if (!event.id || !event.event_type) return res.status(400).json({ error: 'Événement PayPal invalide' });
    if (await eventWasProcessed('paypal', event.id)) return res.json({ received: true, duplicate: true });
    await handlePayPalEvent(event);
    await recordEvent('paypal', event.id);
    return res.json({ received: true });
  } catch (err) {
    console.error('PayPal webhook processing failed:', err.message);
    return res.status(500).json({ error: 'Webhook processing failed' });
  }
});

// Channel directory: provides official country-specific public playlist links, not a guarantee of retransmission rights.
const IPTV_ORG_BASE = 'https://iptv-org.github.io/iptv';
app.get('/api/channels', (_req, res) => res.json({
  source: 'iptv-org/iptv',
  disclaimer: 'Les flux publics ne sont pas automatiquement libres de droits. Vérifiez les droits de diffusion et les conditions applicables à chaque chaîne avant redistribution ou monétisation.',
  worldwide_playlist: `${IPTV_ORG_BASE}/index.m3u`,
  country_index_playlist: `${IPTV_ORG_BASE}/index.country.m3u`,
  countries_api: 'https://iptv-org.github.io/api/countries.json',
  usage: 'Pour une liste par pays, utilisez /api/channels/:iso2 avec un code ISO 3166-1 alpha-2 (ex. ma, fr, us).'
}));
app.get('/api/channels/:country', (req, res) => {
  const code = String(req.params.country || '').toLowerCase();
  if (!/^[a-z]{2}$/.test(code)) return res.status(400).json({ error: 'Utilisez un code pays ISO alpha-2, par exemple ma ou fr.' });
  res.json({ country_code: code, playlist_url: `${IPTV_ORG_BASE}/countries/${code}.m3u`, source: 'iptv-org/iptv', note: 'Vérifiez la disponibilité du pays et les droits de chaque flux avant utilisation commerciale.' });
});

app.get('/payment/success', (_req, res) => res.status(200).send('Paiement reçu. Vous pouvez revenir dans l’application et actualiser votre abonnement.'));
app.get('/payment/cancelled', (_req, res) => res.status(200).send('Paiement annulé. Aucun abonnement ne doit être activé.'));

function db() { if (!pool) throw new Error('DATABASE_URL non configurée'); return pool; }
async function eventWasProcessed(provider, eventId) {
  const r = await db().query('SELECT 1 FROM webhook_events WHERE provider=$1 AND event_id=$2', [provider, eventId]);
  return r.rowCount > 0;
}
async function recordEvent(provider, eventId) {
  await db().query('INSERT INTO webhook_events(provider,event_id) VALUES($1,$2) ON CONFLICT DO NOTHING', [provider, eventId]);
}
async function upsertSubscription({ accountId, provider, customerId = null, subscriptionId = null, planId, active, expiresAt }) {
  if (!accountId || !planId) return;
  await db().query(`INSERT INTO subscriptions(account_id,provider,provider_customer_id,provider_subscription_id,plan_id,active,expires_at,updated_at)
    VALUES($1,$2,$3,$4,$5,$6,$7,NOW())
    ON CONFLICT(account_id) DO UPDATE SET provider=EXCLUDED.provider, provider_customer_id=COALESCE(EXCLUDED.provider_customer_id,subscriptions.provider_customer_id),
    provider_subscription_id=COALESCE(EXCLUDED.provider_subscription_id,subscriptions.provider_subscription_id), plan_id=EXCLUDED.plan_id,
    active=EXCLUDED.active, expires_at=EXCLUDED.expires_at, updated_at=NOW()`,
  [accountId, provider, customerId, subscriptionId, planId, active, expiresAt || 0]);
}
async function handleStripeEvent(event) {
  const obj = event.data.object;
  if (event.type === 'checkout.session.completed' && obj.mode === 'subscription') {
    const accountId = obj.metadata?.account_id || obj.client_reference_id;
    const planId = obj.metadata?.plan_id;
    const sub = obj.subscription ? await stripe.subscriptions.retrieve(obj.subscription) : null;
    await upsertSubscription({ accountId, provider: 'stripe', customerId: obj.customer, subscriptionId: obj.subscription, planId,
      active: Boolean(sub && ['active', 'trialing'].includes(sub.status)), expiresAt: sub?.current_period_end || 0 });
  } else if (event.type.startsWith('customer.subscription.')) {
    const accountId = obj.metadata?.account_id;
    const planId = obj.metadata?.plan_id;
    if (!accountId || !planId) return;
    await upsertSubscription({ accountId, provider: 'stripe', customerId: obj.customer, subscriptionId: obj.id, planId,
      active: ['active', 'trialing'].includes(obj.status), expiresAt: obj.current_period_end || 0 });
  } else if (event.type === 'invoice.payment_failed') {
    const subId = typeof obj.subscription === 'string' ? obj.subscription : obj.subscription?.id;
    if (subId) await db().query("UPDATE subscriptions SET active=FALSE, updated_at=NOW() WHERE provider='stripe' AND provider_subscription_id=$1", [subId]);
  }
}
async function paypalAccessToken() {
  const auth = Buffer.from(`${process.env.PAYPAL_CLIENT_ID}:${process.env.PAYPAL_CLIENT_SECRET}`).toString('base64');
  const r = await fetch(`${paypalBase}/v1/oauth2/token`, { method: 'POST', headers: { Authorization: `Basic ${auth}`, 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'grant_type=client_credentials' });
  const j = await r.json();
  if (!r.ok || !j.access_token) throw new Error('PayPal OAuth token failed');
  return j.access_token;
}
async function verifyPayPalWebhook(headers, body) {
  const token = await paypalAccessToken();
  const payload = {
    auth_algo: headers['paypal-auth-algo'],
    cert_url: headers['paypal-cert-url'],
    transmission_id: headers['paypal-transmission-id'],
    transmission_sig: headers['paypal-transmission-sig'],
    transmission_time: headers['paypal-transmission-time'],
    webhook_id: process.env.PAYPAL_WEBHOOK_ID,
    webhook_event: body
  };
  const r = await fetch(`${paypalBase}/v1/notifications/verify-webhook-signature`, {
    method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  });
  const j = await r.json();
  return r.ok && j.verification_status === 'SUCCESS';
}
async function handlePayPalEvent(event) {
  const resource = event.resource || {};
  const subId = resource.id || resource.billing_agreement_id;
  const accountId = resource.custom_id || null;
  const planId = resource.plan_id ? Object.values(PLANS).find(p => process.env[p.paypalEnv] === resource.plan_id)?.id : null;
  const activated = ['BILLING.SUBSCRIPTION.ACTIVATED', 'BILLING.SUBSCRIPTION.RE-ACTIVATED'].includes(event.event_type);
  const cancelled = ['BILLING.SUBSCRIPTION.CANCELLED', 'BILLING.SUBSCRIPTION.EXPIRED', 'BILLING.SUBSCRIPTION.SUSPENDED'].includes(event.event_type);
  if (accountId && planId && (activated || cancelled)) {
    const expiresAt = resource.billing_info?.next_billing_time ? Math.floor(new Date(resource.billing_info.next_billing_time).getTime()/1000) : 0;
    await upsertSubscription({ accountId, provider: 'paypal', subscriptionId: subId, planId, active: activated, expiresAt });
  }
  // A payment sale event is intentionally not used to activate access by itself; subscription state events are authoritative.
}

app.use((err, _req, res, _next) => {
  console.error('Unhandled request error:', err.message);
  res.status(500).json({ error: 'Erreur interne du serveur' });
});

if (require.main === module) {
  if (!pool) console.warn('ATTENTION: DATABASE_URL absente. Configurez server/.env avant utilisation.');
  app.listen(port, () => console.log(`IPTV API listening on port ${port}`));
}
module.exports = { app, PLANS };
