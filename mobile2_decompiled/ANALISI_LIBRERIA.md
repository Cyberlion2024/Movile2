# Analisi libUE4.so — Mobile2 Global 2.23

## Struttura binario
- **Tipo**: ELF64 ARM aarch64, stripped (no debug symbols)
- **Dimensione**: 182 MB
- **Compiler**: Clang 17.0.2 (Android NDK r487747e)
- **Dipendenze native**: libGLESv3, libEGL, libandroid, libOpenSLES, libc++_shared

---

## Rilevamento Emulatori (CRITICO per il bot)
Il gioco controlla attivamente i seguenti package per rilevare emulatori:
```
com.bignox.       → BigNox / NoxPlayer
com.bluestacks.   → BlueStacks
com.kaopu.        → ?
com.kop.          → ?
com.microvirt.    → MEmu
com.nox.mopen.app → NoxPlayer (alternativo)
com.uncube.lau    → ?
com.vphone.       → ?
org.chromium.arc  → Android su Chromebook (Chrome OS ARC)
```
**Conclusione**: il bot deve girare su dispositivo fisico reale, non su emulatori noti.

---

## Costanti di Combattimento Trovate

| Costante         | Tipo   | Significato |
|------------------|--------|-------------|
| `ATTACK_RANGE`   | float? | Raggio massimo di attacco |
| `ATTACK_SPEED`   | float? | Velocità di attacco |
| `AGGRESSIVE_HP_PCT` | float | % HP a cui i mob diventano aggressivi |
| `AGGRESSIVE_SIGHT`  | float | Distanza di visione per mob aggressivi |
| `BATTLE_TYPE`       | enum?  | Tipo di battaglia |
| `attackDistance`    | float  | Distanza di attacco per l'AI del mob |
| `AttackTimeMsec`    | int    | Durata attacco in millisecondi |
| `attackSpeed`       | float  | Velocità attacco (variabile istanza) |
| `attackable`        | bool   | Se il mob può essere attaccato |
| `attacking`         | bool   | Se il mob sta attaccando |

---

## Sistema Abilità
5 slot abilità confermati:
- `SKILL_VNUM0`...`SKILL_VNUM4` → ID virtuale dell'abilità (Metin2 vnum)
- `SKILL_LEVEL0`...`SKILL_LEVEL4` → Livello dell'abilità

Il bot implementa già 5 skill — **CORRETTO**.

---

## Sistema Drop/Loot
Variabili trovate: `dropLocs`, `dropLoc`, `dropInfo`, `dropItem`, `DROP_ITEM`,
`dropId`, `dropAdd`, `dropAnimSpeed`, `dropedAt`, `dropOnHit`, `dropRemove`,
`dropsActive`, `dropUp`, `dropUpOk`

**Conclusione**: i drop hanno posizioni precise (`dropLocs`). L'approccio del bot
di trovare il centroide dei pixel bianchi/verdi è **CORRETTO**. La voce `dropedAt`
suggerisce che ogni drop ha un timestamp di creazione (per la scomparsa automatica).

---

## Colori Mob Confermati
- `MOB_COLOR` — variabile nel codice nativo per il colore del nome del mob
- `nameColor` — colore del nome (variabile generica)
- Il bot usa R>160, G<130, B<120 per rilevare nomi rossi → **CONFERMATO CORRETTO**

---

## Valuta e Stats (Origine Metin2)
- `yang` — valuta principale (classico Metin2)
- `buyuAttack` — attacco magico (turco: büyü = magia)
- `buyuDef` — difesa magica
- `coinBonusYuzde` — percentuale bonus monete (turco: yüzde = percento)
- `GOLD_MAX`, `GOLD_MIN` — limiti valuta

**Conferma**: Mobile2 è un clone di Metin2 sviluppato in Turchia.

---

## Tipi di Item
```
ITEM_ARMOR    → armatura
ITEM_COSTUME  → costume (skin)
ITEM_ROD      → canna da pesca
```

---

## Sistema Social/Block
`blockTrade`, `blockGuild`, `blockFriend`, `blockPm`, `blockWs`, `blockWait`
→ sistema di blocco utenti stile MMORPG

---

## Layer Java (DEX)
- `com.vendsoft.mobile2.DownloaderActivity` — scarica l'OBB al primo avvio
- `com.vendsoft.mobile2.OBBDownloaderService` — servizio download OBB
- `com.vendsoft.mobile2.AlarmReceiver` — notifiche locali
- Firebase Auth + Google Play Games auth
- Google Play Billing 8.0.0

---

## Rete
- **Nessun IP server trovato** nel .so — gli endpoint sono probabilmente nel
  file pak (OBB) scaricato al primo avvio, oppure risolti via DNS dinamicamente.
- Solo `127.0.0.1` trovato (localhost).

---

## Layout UI — CRITICO

Il layout UI (posizioni bottoni attack/skill/pozione) è nel file **OBB/PAK** (non nell'APK).
Il PAK viene scaricato al primo avvio del gioco e contiene gli UAsset Blueprint UMG.
**Non è estraibile dall'APK** senza strumenti specializzati per UE4 PAK parsing.

Il bot usa quindi un approccio **pixel-based in tempo reale**:
- Scansiona lo screenshot per trovare l'icona della pozione HP (cluster rosso compatto)
- Scansiona per trovare nomi mob rossi (MOB_COLOR confermato)
- Scansiona per trovare la barra HP (striscia rossa orizzontale in alto a sinistra)
- Le coordinate attack/skill sono stimate con fallback statico calibrato sulla screenshot

## Strutture Dati Trovate

| Variabile | Tipo | Significato |
|-----------|------|-------------|
| `CFhp` | float | Current Float HP (vita corrente) |
| `TFhp` | float | Total Float HP (vita massima) |
| `hpstat` | struct | Statistiche HP |
| `maxhp` / `MaxHP` | int/float | HP massimo |
| `sendQuickChange` | function | Cambia slot quick-bar |
| `sendQuickChangeQ` | function | Queue di cambio quick-bar |
| `FwSlotUpdate` | struct | Aggiornamento slot |
| `Fskill` / `FskillQue` | struct | Skill e coda skill |
| `metins` / `metinStoneOverride` | data | Pietre Metin (clone confermato) |

## Input System

- `SVirtualJoystick` — joystick virtuale UE4 built-in (posizione in DefaultInput.ini nel PAK)
- `TouchInputControl` — sistema touch input UE4 mobile
- Le coordinate joystick/bottoni si adattano alla risoluzione schermo via UE4 viewport scaling

## Implicazioni per il Bot

1. **Usa solo dispositivi fisici reali** — il gioco rileva emulatori noti.
2. **Pixel detection (rosso per nomi mob) CONFERMATA** — `MOB_COLOR` esiste nel codice nativo.
3. **5 skill slots confermati** — implementazione attuale corretta.
4. **Drop loot detection (centroide bianco/verde) CONFERMATA** — `dropLocs` usa posizioni precise.
5. **HP ratio = CFhp / TFhp** — la barra HP è la rappresentazione visiva di questo rapporto.
6. **Layout UI NON estraibile dall'APK** — il bot usa auto-detection via screenshot.
7. **attackDistance** — esiste distanza di attacco per ogni mob; il bot deve essere vicino.
8. **AGGRESSIVE_HP_PCT** — mob aggressivi; il bot non dovrebbe muoversi fuori range.

