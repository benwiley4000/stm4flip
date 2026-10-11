# stm4flip

![logo](/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png)

Voici une appli Android qui montre, sur une flip phone, les prochaines arrivées d'autobus STM là où vous êtes (ou là où vous prévoyez être plus tard).

Here's an Android app that shows, on a flip phone, the next bus arrivals wherever you are (or wherever you plan to be later).

## Ce que c'est / What it is

Une intégration entre Open Street Map et les APIs publics du STM, avec de la navigation par bouton. Ça permet de voir la liste d'autobus qui arrivent prochainement dans la portion de carte qui est actuellement visible, ainsi que les emplacements d'arrêts d'autobus. Vous pouvez déplacer la carte pour voir des infos pour n'importe où à Montréal.

An integration between Open Street Map and the public STM APIs, with button navigation. It lets you see the list of buses that arrive soon in the area of the map that is currently visible, as well as the locations of bus stops. You can move the map around to see info pour anywhere in Montreal.

## AI Disclaimer IA

### En français

J'ai genéré la grande partie de ce code dans un jour avec la version gratuite de Claude. J'en suis pas grandement fier, mais c'est vraiment pour réduire ma dépendence sur ma téléphone intélligente. Je suis plus développeur web que Android, mais le code n'est pas très immense, alors j'ai implémenté la traduction d'interface à la main, et s'il y a des bogues, je vais les fixer à la main.

J'ai fait le logo d'app dans [Photopea](https://www.photopea.com/) (pas avec de l'IA). Le logo "STM" appartient à l'STM. Je l'ai copié... sans permission. En esperant que je serai pas poursuit en justice ! La flip phone dans l'image est un Motorola Razr, ce qui peut pas rouler cette application, car ça ne roule pas Android.

### In english

I generated most of this code in a day with the free version of Claude. I'm not super proud of that, but it's really to reduce my dependance on my smartphone. I'm more of a web than Android developer, but the code isn't huge, so I implemented the UI translation by hand, and I will fix any bugs by hand, if there are any.

I made the app logo in [Photopea](https://www.photopea.com/) (not with AI). The "STM" logo belongs to the STM. I copied it... without permission. Hopefully will not be sued! The flip phone pictured is a Motorola Razr, which cannot run this app because it doesn't run Android.

## "Screenshots" (je sais pas prendre un screenshot sur mon flip phone)

## Comment installer ? How to install

### En français

Notez que si votre flip phone n'est pas basé sur Android, elle ne roulera pas cette application.

1. Télécharger [adb](https://developer.android.com/tools/adb). Suivez les indications sur cette page pour comment utiliser adb avec votre téléphone.
2. Connecter votre flip phone à votre ordi via USB
3. Télécharger l'apk des Releases
4. Ouvrir un terminal dans le dossier de téléchargements et rouler : `adb install org.benwiley.stm4flip.apk`

### In english

Note that if your flip phone is not based on Android, it won't run this app.

1. Download [adb](https://developer.android.com/tools/adb). Follow the instructions on this page for how to use adb with your phone.
2. Connect your Android flip phone to your computer with USB
3. Download the apk from releases
4. Open a terminal in the download directory and run: `adb install org.benwiley.stm4flip.apk`

## Comment utiliser ? How to use

### En français

Attention qu'il faut télécharger les données de carte pour utiliser l'application la première fois. Ça demande à peu près 50MB de téléchargement, et quelques minutes pour finir. Je vous conseille de faire ça avant le moment où il faut déplacer.

D'autres mises à jour seront disponibles chaques quelques semaines, mais elle ne sont pas strictement nécessaire (mais les horaires peuvent devenir non à jour).

Les contrôles sont pas mal expliquées sur l'écran, mais en gros :

- Bouger sur carte : Flèches
- Zoomer : 3
- Dézoomer : 1
- Réinitialiser la position et le zoom : 5

Si la carte commence centrée sur la Gare Centrale à Montréal, ça veut dire que votre position n'était pas immédiatement disponible au moment de charger l'application.

### In english

Note that you need to download the map data to use the app the first time. It needs about 50MB of downloaded data and a couple minutes to finish. I advise you to do this before the time you need to get somewhere.

Other updates will be available every few weeks, but are not strictly necessary (but the schedules may become out of date).

The controls are mostly explained on the screen (in French), but basically:

- Move around the map: Arrows
- Zoom in: 3
- Zoom out: 1
- Recenter on your location with the default zoom level: 5

If the map starts centered on Gare Centrale in Montreal, that means your location wasn't immediately available when the application loaded.

## How to build

### En français

Il faut obtenir un clé API pour obtenir les données en live pour les arrivées d'autobus. C'est ici:
https://portail.developpeurs.stm.info/apihub

Soyez certain d'avoir accès à GTFS-Realtime (v2.0), et de publier votre application.

Après :

1. Télécharger [Android studio](https://developer.android.com/studio)
2. Git clone ce repo
3. Ouvrir le repo dans Android studio
4. Ouvrir ou créer le fichier dans la racine "local.properties". Mettez votre API Key: `STM_API_KEY=xxxxxx`
5. Une fois les choses chargées, ouvrir un terminal
6. `./gradlew assembleDebug`
7. Pour installer sur votre téléphone connectée via USB : `adb install app/build/outputs/apk/debug/app-debug.apk`

### In english

You need an API key to get live data for bus arrivals. It's here:
https://portail.developpeurs.stm.info/apihub

Be sure to have access to GTFS-Realtime (v2.0), and to publish your app.

Afterward:

1. Download [Android studio](https://developer.android.com/studio)
2. Git clone this repo
3. Open the repo in Android studio
4. Open or create the file in the root called "local.properties". Add your API key: `STM_API_KEY=xxxxxx`
5. Once things are done loading, open a terminal
6. `./gradlew assembleDebug`
7. To install the built apk on your phone connected via USB: `adb install app/build/outputs/apk/debug/app-debug.apk`

## Pull requests

SVP pas d'améliorations, c'est vraiment censé d'être un truc très simple. S'il s'agit véritablement d'une fix bogue, allez-y, mais soyez certain de bien comprendre le code que vous me donnez, s'il vous plaît.

Please no "improvements," it's really meant to be a very simple thing. If you've really got a bug fix, go ahead, but be certain to understand the code you're providing, please.
