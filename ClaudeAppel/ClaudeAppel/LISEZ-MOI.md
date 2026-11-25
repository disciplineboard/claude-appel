# 📞 Claude t'appelle — appli Android

Quand Claude a fini une tâche, ton téléphone sonne comme un vrai appel.
Tu décroches, et une voix française lit son message. 100 % gratuit, sans compte.

## Comment ça marche
1. Claude ouvre une adresse ntfy (service gratuit) :
   `https://ntfy.sh/claude-remi-id04cdc0yg/send?message=Ton%20message`
2. L'appli, qui écoute ce « sujet » en arrière-plan, reçoit le message.
3. Elle affiche un écran d'appel plein écran + sonnerie + vibration.
4. Tu décroches → la voix lit le message (deux fois). Personne ne répond en 45 s → « Appel manqué » avec le texte.

## Installer
1. Ouvre **Android Studio** → **File → Open** → choisis le dossier `ClaudeAppel`.
2. Attends la synchronisation Gradle (la première fois, quelques minutes : il télécharge ce qu'il faut).
   S'il propose de mettre à jour le plugin Android Gradle, tu peux accepter.
3. Sur ton téléphone : **Options pour les développeurs → Débogage USB** activé, branche-le au PC.
4. Clique sur ▶️ **Run**. L'appli s'installe et s'ouvre.

## Régler l'appli (une seule fois)
- Appuie sur **Autoriser** pour chaque ligne avec ❌ (notifications, écran plein écran, batterie).
- Appuie sur **Activer l'écoute**.
- Appuie sur **Tester** puis verrouille ton téléphone : ça doit sonner au bout de 5 secondes.

## Bon à savoir
- Le sujet `claude-remi-id04cdc0yg` est ton « numéro secret » : quiconque le connaît peut te faire sonner.
  Tu peux le changer dans l'appli (il faut arrêter l'écoute d'abord) — dis-le alors à Claude.
- Mode « Ne pas déranger » : la sonnerie peut être coupée. Ajoute l'appli aux exceptions si besoin.
- Certaines marques (Xiaomi, Huawei, Oppo…) tuent les applis en arrière-plan : autorise le
  « démarrage automatique » et retire les restrictions de batterie pour l'appli.
- Les messages passent par ntfy.sh, un serveur public : n'y mets rien de confidentiel.
