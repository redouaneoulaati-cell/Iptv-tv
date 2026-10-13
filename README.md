# Serveur IPTV — Node.js, authentification JWT, Stripe/PayPal Sandbox

## État

Ce dossier prépare un backend testable. Il n'est pas encore un service de production : les comptes de paiement, les identifiants des plans, la base hébergée et les tests réels des webhooks doivent être configurés par le propriétaire.

## Plans de test (USD)

- Mensuel : 4,99 USD
- Trimestriel : 12,99 USD
- Annuel : 44,99 USD

Ces montants sont des exemples à valider. Ils ne deviennent les montants facturés qu'après création des prix récurrents correspondants dans les tableaux de bord Stripe et PayPal.

## Développement local

1. Node.js 20 ou plus récent et PostgreSQL.
2. `npm install`
3. Créez une base PostgreSQL et exécutez `psql "$DATABASE_URL" -f sql/schema.sql`.
4. Copiez `.env.example` vers `.env`. Générez `AUTH_JWT_SECRET` avec `openssl rand -base64 48`. Ne publiez jamais `.env`.
5. Créez les prix/Plans récurrents en USD dans les dashboards Sandbox et placez leurs IDs dans les variables `STRIPE_PRICE_*` et `PAYPAL_PLAN_*`.
6. `npm test` puis `npm start`.

## Authentification

- `POST /auth/register` JSON `{ "email":"test@example.com", "password":"au-moins-12-caracteres" }`
- `POST /auth/login` JSON `{ "email":"test@example.com", "password":"..." }`
- Les réponses contiennent un JWT Bearer à durée limitée. Envoyez `Authorization: Bearer <token>` à `/subscription` et `/checkout`.
- Les mots de passe sont hachés avec bcrypt (coût 12), les endpoints d'authentification sont limités en débit et les identifiants anonymes `X-Account-Id` ne sont plus acceptés.
- L'application Android actuelle utilise encore `X-Account-Id` : son écran d'inscription/connexion et son stockage sécurisé du jeton doivent être développés avant que l'application puisse utiliser ces endpoints. Ne publiez pas la vente d'abonnements avant cette intégration.

## Stripe et PayPal

Stripe webhook : `POST /webhooks/stripe`. Activez au minimum `checkout.session.completed`, `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted` et `invoice.payment_failed`. La signature Stripe est vérifiée sur le corps brut et les événements sont dédupliqués en base.

PayPal webhook : `POST /webhooks/paypal`. Le serveur demande à l'API PayPal de vérifier la signature avec le Webhook ID. Configurez les événements de cycle de vie d'abonnement : `BILLING.SUBSCRIPTION.ACTIVATED`, `BILLING.SUBSCRIPTION.CANCELLED`, `BILLING.SUBSCRIPTION.EXPIRED`, `BILLING.SUBSCRIPTION.SUSPENDED` et `BILLING.SUBSCRIPTION.RE-ACTIVATED`.

Pour les tests locaux, utilisez les outils de test Stripe et PayPal Sandbox et un tunnel HTTPS tel que Stripe CLI pour le webhook Stripe. Ne testez pas avec de vraies cartes ou de vrais paiements. Vérifiez les logs du serveur et la ligne correspondante dans `subscriptions` après chaque événement.

## Compatibilité Maroc et devise USD

- Stripe ne prend pas en charge l'ouverture standard de comptes marchands directement au Maroc. Un compte Stripe live ne doit être utilisé que si l'entreprise dispose réellement d'une entité et des documents bancaires/fiscaux exigés dans un pays pris en charge. Ne contournez pas les contrôles d'éligibilité. Source : https://stripe.com/global et https://support.stripe.com/questions/requirements-to-open-a-stripe-account-in-another-country
- PayPal publie une offre de paiements récurrents pour le Maroc : https://www.paypal.com/ma/business/accept-payments/checkout/recurring. Confirmez les fonctionnalités de réception, de retrait et les conditions de compte directement dans PayPal Business avant la mise en production.
- Les plans de test utilisent USD, mais la devise et le montant facturés proviennent des objets de prix réels créés dans chaque fournisseur.

## Hébergement gratuit (tests seulement)

Render permet d'héberger un service web Node.js gratuit avec HTTPS, mais les services gratuits s'endorment après 15 minutes d'inactivité et la base PostgreSQL gratuite expire après 30 jours. Ce n'est pas recommandé pour vendre des abonnements ou conserver des données clients en production. Consultez https://render.com/docs/free.

Déploiement test : connecter le dépôt à Render, créer un Web Service depuis le dossier `server`, Build Command `npm install`, Start Command `npm start`, ajouter les variables d'environnement depuis `.env.example` dans le tableau de bord, créer PostgreSQL et renseigner son URL. Définir `PUBLIC_BASE_URL` sur l'URL HTTPS Render réelle. Ne stockez pas les secrets dans le dépôt Git.

## Publication Google Play et contenu IPTV

Vérifiez la politique Google Play Billing pour les abonnements donnant accès à du contenu numérique, y compris les programmes d'accès alternatif applicables à votre marché : https://support.google.com/googleplay/android-developer/answer/10281818. Une intégration Stripe/PayPal externe dans l'app n'est pas automatiquement permise.

Un flux public IPTV n'est pas automatiquement libre de droits. Gardez une preuve de licence/autorisation pour chaque chaîne commercialisée et retirez les flux dont les droits ne sont pas établis.
