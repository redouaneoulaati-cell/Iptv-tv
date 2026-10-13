# IPTV Android — projet complet (Kotlin + Compose + Media3)

Ouvrir le dossier dans Android Studio (Ladybug ou plus récent) → Sync → Run.
Versions (AGP 8.7.2, Kotlin 2.0.21, KSP 2.0.21-1.0.25) à ajuster si Android Studio le propose.
Ce code n'a pas été compilé ici : quelques ajustements mineurs peuvent être nécessaires.

## Fonctionnalités
- Playlists M3U : URL, fichier local, connexion Xtream Codes (génère M3U + EPG)
- Plusieurs playlists (Room), dernière playlist rechargée au démarrage
- Recherche, groupes, favoris, logos (Coil)
- Guide XMLTV (.xml / .gz) : programme en cours + suivant + progression
- Lecteur Media3 (HLS, DASH, TS...), zapping, Réessayer, minuteur de veille
- Plein écran, image dans l'image (désactivable dans Réglages)
- **Lecture en arrière-plan** : le lecteur vit dans PlaybackService (MediaSessionService), notification média
- **Chromecast** : bouton Cast ; le service bascule la lecture sur l'appareil Cast et revient au téléphone à la déconnexion
- **Enregistrement** : bouton « Enreg. » → fichier .ts dans le stockage de l'appli (service au premier plan, durée max réglable), onglet « Enreg. » pour lire / supprimer
- **Code parental** : code à 4 chiffres (haché), groupes verrouillés au choix + groupes « adult / xxx / 18+ » verrouillés automatiquement, déverrouillage 30 min, zapping qui saute les chaînes verrouillées
- **Android TV** : lanceur Leanback, bannière (res/drawable/banner.xml), télécommande (haut/bas = zapping, OK = contrôles)

- **Abonnement** : onglet « Abonnement » (formules, statut, paiement par carte bancaire, Google Pay, Apple Pay, PayPal ou recharge téléphonique). `REQUIRE_SUBSCRIPTION = true` dans Billing.kt exige un abonnement actif pour regarder.

## Serveur de paiement (à fournir)
L'appli ne touche jamais aux données de carte : elle appelle VOTRE serveur (`API_BASE` dans Billing.kt), qui crée une session de paiement hébergée et active l'abonnement à la réception du webhook du prestataire.

| Endpoint | Rôle |
|---|---|
| `GET /plans` | `[{"id","name","months","price"}]` |
| `GET /subscription` | `{"active": bool, "expires_at": <epoch secondes>}` |
| `POST /checkout` `{plan_id, method, phone?}` | `{"checkout_url"?, "message"?}` ; `method` ∈ `card`, `google_pay`, `apple_pay`, `paypal`, `phone_topup` |

Chaque requête porte `X-Account-Id` (identifiant anonyme : à remplacer par une vraie connexion).
Prestataires possibles : Stripe Checkout (carte + Google Pay ; Apple Pay sur Safari Apple), PayPal Orders API, agrégateur mobile money / recharge selon le pays (Flutterwave, CinetPay, Paystack…). Le serveur doit vérifier les webhooks (signature) avant d'activer un abonnement.

## Règles à respecter
- **Google Play** : les abonnements à du contenu numérique doivent en principe passer par Google Play Billing (qui propose carte, Google Pay, PayPal et facturation opérateur). Les paiements externes ne sont autorisés que dans certains cas/régions : vérifiez la politique en vigueur avant publication.
- **Apple Pay** n'existe pas nativement sur Android : l'appli envoie un lien de paiement à ouvrir sur un appareil Apple.
- Ne vendez l'accès qu'à des contenus dont vous détenez les droits.

## Limites connues
- Chromecast : le récepteur par défaut lit surtout HLS/MP4 ; les flux MPEG-TS bruts peuvent échouer. Les enregistrements locaux ne se castent pas.
- Enregistrement HLS : segments TS non chiffrés uniquement (pas de fMP4 ni AES-128).
- Code parental : protection locale simple (un code à 4 chiffres reste devinable) ; il n'empêche pas de désinstaller l'appli.
- La bannière et l'icône sont des vecteurs génériques : à remplacer par vos visuels (banner 320x180 px) avant publication.

## Serveur de paiement et catalogue mondial de chaînes

Le dossier `server/` contient une API Node.js/Express avec Stripe Checkout récurrent, souscriptions PayPal, validation de signatures des webhooks, PostgreSQL, idempotence des événements et endpoints de catalogue par pays. Consultez `server/README.md` et `server/.env.example`.

- API pays : `GET /api/channels/ma` (Maroc), `GET /api/channels/fr` (France), etc.
- Playlist publique internationale classée par pays à ajouter dans l'application comme URL M3U : `https://iptv-org.github.io/iptv/index.country.m3u`.
- Liste par pays : `https://iptv-org.github.io/iptv/countries/ma.m3u` (remplacer `ma` par le code pays ISO alpha-2).

**Important :** aucun catalogue communautaire ne peut garantir « toutes les chaînes libres de droit ». « Accessible publiquement sur Internet » ne signifie pas nécessairement « libre de droits de rediffusion ou de monétisation ». Le catalogue IPTV-org recense des liens de flux publics et précise qu'il n'héberge pas les vidéos; vérifiez la licence et les droits de chaque chaîne dans votre pays avant de l'intégrer à une application commerciale. Source : https://github.com/iptv-org/iptv
