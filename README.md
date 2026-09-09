# MagicDuels

Paper 26.2 duel plugin for MagicSMP.

## Features
- `/duel <player>` duel requests
- `/duel <player> <wager>` optional Vault wagers
- `/duel accept <player>` and `/duel deny <player>`
- Configurable request timeout
- Two configurable arena spawns
- Countdown before PvP enables
- Freezes players during countdown
- Inventory-safe: original inventory, armor, offhand, XP, health, food, gamemode and location are restored
- No item dropping/picking up, block breaking/placing, or container access during duels
- Outsiders cannot damage duel players and duel players cannot damage outsiders
- Commands are blocked during duels except configured whitelist
- Disconnect during active duel = forfeit
- Disconnect during countdown = duel cancelled and wagers refunded
- Plugin/server shutdown = safe restore + wager refund

## Setup
1. Build with JDK 25 using `./gradlew build` (or import into IntelliJ and use Gradle).
2. Put the generated JAR in your server's `plugins` folder.
3. Restart the server.
4. Stand at the first arena position and run `/duel setspawn 1`.
5. Stand at the second arena position and run `/duel setspawn 2`.
6. Players can now use `/duel <player>`.

## Wagers
Wagers require Vault and a Vault-compatible economy provider. If Vault isn't present, regular duels still work.

## Permissions
- `magicduels.use` - default: everyone
- `magicduels.admin` - default: OP

## Build with GitHub Actions

This project includes `.github/workflows/build.yml`.

1. Upload the contents of this project to a GitHub repository.
2. Open the repository's **Actions** tab.
3. Select **Build MagicDuels**.
4. Click **Run workflow** (or push a commit to `main`/`master`).
5. After the build succeeds, open the workflow run and download the **MagicDuels-1.0.0** artifact.
6. Extract the artifact ZIP and place `MagicDuels-1.0.0.jar` in your Paper server's `plugins` folder.

The workflow uses Java 25 and Gradle 9.1.0.
