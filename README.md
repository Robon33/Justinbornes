# Pub Bornes

Affiche en boucle les vidéos et images d'un dossier Google Drive sur les bornes Sunmi T3
(portrait), hors des heures de commande. Synchro automatique, fonctionne hors ligne.

## ⚠️ Keystore de signature — À SAUVEGARDER

| Fichier | Rôle |
|---|---|
| `keystore/pub-bornes-release.jks` | clé de signature de l'app |
| `keystore.properties` | mots de passe du keystore |

**Copiez ces deux fichiers dans un endroit sûr (gestionnaire de mots de passe, clé USB, coffre cloud).**
Sans eux, impossible de publier une mise à jour : il faudrait désinstaller l'app sur chaque
borne (et perdre ses réglages). Ils ne doivent jamais être partagés publiquement.

Empreinte SHA-1 du certificat (utile pour restreindre la clé API) :
`28:C5:78:6B:D1:28:63:1F:11:71:B7:00:4F:1D:42:41:5D:C8:5B:A0`

## Construire l'APK

Prérequis (déjà installés sur ce Mac) : JDK 17 (`brew install openjdk@17`) et le SDK Android
(`brew install --cask android-commandlinetools`, chemin dans `local.properties`).

```sh
./build-release.sh        # => dist/PubBornes-<version>.apk (signé)
```

Équivalent manuel : `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.

Optionnel : copiez `bornes.properties.example` en `bornes.properties` et renseignez l'ID du dossier,
la clé API et le package de Factory POS. Ils seront intégrés à l'APK comme valeurs par défaut :
plus rien à saisir sur les bornes.

## 1. Google Cloud : API Drive et clé API

1. https://console.cloud.google.com → créer un projet (ex. « Pub Bornes »).
2. **API et services > Bibliothèque** → « Google Drive API » → **Activer**.
3. **API et services > Identifiants > Créer des identifiants > Clé API**.
4. Restreindre la clé (bouton **Modifier la clé**) :
   - *Restrictions relatives aux API* : cocher uniquement **Google Drive API** ;
   - *Restrictions relatives aux applications* (facultatif, recommandé) : **Applications Android**,
     ajouter le package `com.pubbornes.player` et l'empreinte SHA-1 ci-dessus.
5. Copier la clé (`AIza…`).

## 2. Dossier Google Drive

1. Créer un dossier, y déposer les médias, **Partager > Accès général : Tous les utilisateurs
   disposant du lien** (Lecteur).
2. L'ID est la fin du lien : `https://drive.google.com/drive/folders/`**`1AbCdEf…`**.
   Dans le menu admin, on peut aussi coller le lien complet.

**Ordre de lecture** = ordre alphabétique des noms : `01_menu.mp4`, `02_promo.jpg`, `03_…`

### Specs des contenus
- Vertical **1080 × 1920** (un média paysage est centré, jamais pivoté).
- Vidéos : **MP4, H.264**, **50 Mo max** par vidéo. Son coupé par défaut.
- Images : JPG, PNG ou WEBP, affichées 8 s (réglable).
- Les autres formats sont ignorés. Les sous-dossiers ne sont pas lus.
- Les changements sont pris en compte à la synchro suivante (≤ 30 min, ou « Synchroniser
  maintenant »), puis appliqués à la fin de la boucle en cours.

## 3. Installer l'APK sur une borne

**Par ADB (recommandé)** — activer les *Options développeur* (Paramètres > À propos > taper 7 fois
sur « Numéro de build ») puis *Débogage USB*, brancher la borne :
```sh
./install.sh              # installe sur toutes les bornes détectées par `adb devices`
```
**Par lien de téléchargement (GitHub)** — sur la borne, ouvrir dans le navigateur :
`https://github.com/Robon33/Justinbornes/releases/latest/download/PubBornes.apk`
(ce lien pointe toujours vers la dernière version publiée), autoriser **Sources inconnues**
pour ce navigateur quand Android le demande, puis **Installer**.

Vérifier la résolution : `adb shell wm size` (attendu : `1080x1920`).

## 4. Définir Pub Bornes comme écran d'accueil

Paramètres Android > **Applications > Applications par défaut > Application d'accueil** →
**Pub Bornes** (ou appuyer sur la touche Accueil et choisir *Pub Bornes* > *Toujours*).
La borne démarre alors directement sur la pub et y revient si une app se ferme.
Pour revenir au launcher d'origine : même menu, ou *Ouvrir les paramètres Android* depuis le menu admin.

## Utilisation au quotidien

- **Matin** : lancer Pub Bornes (automatique si c'est l'écran d'accueil).
- **Menu admin** : taper **8 fois en moins de 5 s dans le coin haut droit**, puis le PIN
  (**1234** par défaut — à changer dans *Réglages*). Le menu se referme seul après 60 s.
- **11h45** : menu admin > **Lancer Factory POS** (choisir l'app une première fois via
  *Réglages > Choisir…*, puis *Enregistrer*). La lecture et les téléchargements s'arrêtent.
- La touche Accueil ramène à la pub (si Pub Bornes est l'écran d'accueil).
- *Quitter* ferme l'app ; si elle est l'écran d'accueil, Android la rouvre aussitôt.

En cas de coupure internet, la borne continue avec les médias déjà en cache et réessaie
toutes les 5 min. Le menu admin affiche le nombre de fichiers, la dernière synchro et la
dernière erreur.

## 5. Mettre à jour l'app

1. Dans `app/build.gradle.kts`, incrémenter `versionCode` (ex. 1 → 2) et `versionName`.
2. `./build-release.sh`
3. `./install.sh`, ou `./publish-release.sh` pour publier la version sur GitHub (prérequis :
   `gh auth login` une fois) puis rouvrir le lien ci-dessus sur chaque borne.
   Les réglages et le cache sont conservés, à condition que l'APK soit signé avec
   **le même keystore**.

⚠️ Le dépôt et ses Releases sont publics : n'utilisez pas `bornes.properties` pour un APK
publié sur GitHub (la clé API serait lisible dans l'APK). Saisissez-la dans le menu admin.
