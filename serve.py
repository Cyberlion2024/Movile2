import http.server
import socketserver

PORT = 5000
HOST = "0.0.0.0"

PAGE = """<!DOCTYPE html>
<html lang="it">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Movile2 Bot - Android APK</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'Segoe UI',sans-serif;background:#0f0f1a;color:#e0e0e0;min-height:100vh;padding:32px 20px}
.wrap{max-width:900px;margin:0 auto}
h1{font-size:2rem;color:#4fc3f7;margin-bottom:6px}
.sub{color:#888;margin-bottom:24px;font-size:.95rem}
.card{background:#1a1a2e;border:1px solid #1e3a5f;border-radius:10px;padding:22px;margin-bottom:18px}
.card h2{color:#4fc3f7;font-size:1.1rem;margin-bottom:14px}
table{width:100%;border-collapse:collapse;font-size:.9rem}
td,th{padding:8px 10px;border-bottom:1px solid #1e3a5f;text-align:left}
th{color:#4fc3f7;font-weight:600}
code,pre{background:#0a0a14;border-radius:6px;padding:3px 8px;font-size:.88rem;color:#a5d6a7}
pre{display:block;padding:14px;overflow-x:auto;margin-top:8px}
ol,ul{padding-left:18px}
li{margin-bottom:8px;line-height:1.6;color:#ccc}
.badge{display:inline-block;background:#0d3b6e;color:#4fc3f7;border-radius:4px;padding:2px 8px;font-size:.8rem;margin:2px}
.warn{background:#1a1400;border:1px solid #665500;border-radius:8px;padding:12px 16px;color:#ffcc44;font-size:.9rem;margin-top:10px}
.danger{background:#1a0505;border:1px solid #660000;border-radius:8px;padding:12px 16px;color:#ff6666;font-size:.9rem;margin-top:10px}
.ok{background:#051a0d;border:1px solid #006622;border-radius:8px;padding:12px 16px;color:#69f0ae;font-size:.9rem;margin-top:8px}
.new{background:#0d3b1e;border:1px solid #1b5e20;border-radius:4px;padding:2px 8px;font-size:.78rem;color:#69f0ae;margin-left:6px;vertical-align:middle}
.tag-ok{background:#0d3b1e;color:#69f0ae;border-radius:4px;padding:1px 7px;font-size:.78rem}
.tag-warn{background:#3b2800;color:#ffcc44;border-radius:4px;padding:1px 7px;font-size:.78rem}
.tag-bad{background:#3b0000;color:#ff6666;border-radius:4px;padding:1px 7px;font-size:.78rem}
</style>
</head>
<body>
<div class="wrap">
  <h1>&#129302; Movile2 Bot</h1>
  <p class="sub">Bot Android per MMORPG &mdash; Kotlin + Accessibility Service &mdash; <strong>v10</strong></p>

  <div class="card">
    <h2>&#10024; Novit&agrave; v10 &mdash; Fix Pozze Solo + Rilevamento Mostri Rossi + Slot Abilit&agrave;</h2>
    <ul>
      <li><strong>Fix modalit&agrave; solo pozze</strong> <span class="new">FIX</span> &mdash; quando l&apos;attacco &egrave; OFF e le pozze sono ON, il bot preme <em>solo</em> le pozze, senza nessun tap di attacco aggiuntivo</li>
      <li><strong>Attacco con rilevamento mostri</strong> <span class="new">NEW</span> &mdash; quando ATT &egrave; ON, il bot scansiona lo schermo ogni 500ms cercando nomi di mostri rossi (R&gt;160, G&lt;130, B&lt;120) vicini al personaggio. Attacca solo se trova un mostro; se non ce ne sono si mette in attesa. Lo stato mostra &#x2694;&#xFE0F; ATT&#x1F534; quando c&apos;&egrave; un mostro, &#x2694;&#xFE0F; ATT&hellip; quando cerca</li>
      <li><strong>Slot abilit&agrave; configurabili</strong> <span class="new">NEW</span> &mdash; premi &#x1F3AF; IMPOSTA SKILL nell&apos;overlay e tocca fino a 5 posizioni sullo schermo per impostare i tuoi slot abilit&agrave;. Premi &#x2728; SKILL: ON/OFF per attivarle. Il bot le usa in sequenza ogni N secondi (configurabile nell&apos;app)</li>
      <li><strong>Intervallo abilit&agrave; in app</strong> <span class="new">NEW</span> &mdash; nella schermata principale ora c&apos;&egrave; il campo &ldquo;Intervallo abilit&agrave; (secondi)&rdquo; separato dall&apos;intervallo pozione</li>
    </ul>
    <div class="ok">&#9989; Pozze ON senza Attacco = solo pozze, nessun attacco. Attacco ON = attacca solo i mostri con nome rosso vicini al personaggio.</div>
  </div>

  <div class="card">
    <h2>&#10024; Novit&agrave; v9 &mdash; Joystick via ACTION_OUTSIDE (nessun overlay)</h2>
    <ul>
      <li><strong>Nessun overlay sul joystick</strong> <span class="new">FIX DEFINITIVO</span> &mdash; rimosso completamente l&#39;overlay trasparente sul joystick. Non c&#39;&egrave; pi&ugrave; nessun layer che intercetta il tocco &rarr; nessun blocco schermo, nessun riavvio telefono necessario. Il gioco riceve i tocchi nativamente, esattamente come senza il bot</li>
      <li><strong>Rilevamento joystick tramite ACTION_OUTSIDE</strong> <span class="new">NEW</span> &mdash; il pannello bot ha il flag <code>FLAG_WATCH_OUTSIDE_TOUCH</code>: ogni tocco fuori dal pannello (incluso il joystick) genera un evento <code>ACTION_OUTSIDE</code> con le coordinate. Se cadono nella zona joystick (raggio 140dp dal centro impostato) &rarr; <code>joystickActive=true</code>. Timer da 1.5s senza tocchi nella zona &rarr; bot riprendono automaticamente</li>
      <li><strong>Re-impostazione sempre funzionante</strong> <span class="new">FIX</span> &mdash; non esiste pi&ugrave; nessun overlay che interferisce con le catture. Premi IMPOSTA ATT/POZ/JOYSTICK in qualsiasi momento e funziona</li>
    </ul>
    <div class="ok">&#9989; Come funziona: imposta il JOYSTICK una volta &rarr; ogni volta che usi il joystick i bot si fermano &rarr; 1.5s dopo che smetti di usarlo i bot riprendono. Il gioco riceve tutti i tocchi normalmente senza alcun ritardo artificiale.</div>
  </div>

  <div class="card">
    <h2>&#10024; Novit&agrave; v7 &mdash; Loot Migliorato + Hide Pannello + Fix Attacco Manuale</h2>
    <ul>
      <li><strong>Hide pannello</strong> &mdash; premi <strong>▼</strong> nella barra del pannello per collassarlo a una sola riga; premi <strong>▶</strong> per espandere</li>
      <li><strong>Fix attacco manuale + pozione</strong> &mdash; re-tap automatico sull&#39;attacco 70ms dopo ogni pozione</li>
      <li><strong>Tap attacco pi&ugrave; breve</strong> &mdash; ridotto a 40ms per meno interferenza col joystick</li>
    </ul>
  </div>

  <div class="card">
    <h2>&#10024; Novit&agrave; v6 &mdash; Movimento Libero + Loot di Prossimit&agrave;</h2>
    <ul>
      <li><strong>Movimento completamente libero</strong> <span class="new">NEW</span> &mdash; il bot NON interferisce pi&ugrave; con il joystick o il movimento. Il giocatore si muove liberamente e il bot raccoglie solo ci&ograve; che &egrave; vicino al personaggio</li>
      <li><strong>Loot di prossimit&agrave;</strong> <span class="new">NEW</span> &mdash; raccoglie oggetti e yang SOLO entro il 30% della larghezza schermo dal centro (posizione personaggio). Nessun tap lontano che blocca il movimento</li>
      <li><strong>Pozione multi-slot</strong> <span class="new">NEW</span> &mdash; supporta fino a 3 slot pozione configurabili. Ogni slot viene premuto automaticamente ogni 3 secondi</li>
      <li><strong>Pozione auto-start</strong> <span class="new">NEW</span> &mdash; dopo aver impostato gli slot, la pozione parte automaticamente senza bisogno di premere ON/OFF</li>
      <li><strong>Configurazione slot con tocco</strong> <span class="new">NEW</span> &mdash; premi IMPOSTA POZ e tocca fino a 3 posizioni sullo schermo per impostare tutti gli slot pozione. Timeout 5s automatico</li>
      <li><strong>Scanner pi&ugrave; reattivo</strong> <span class="new">NEW</span> &mdash; scan ogni 400ms (prima 500ms) per risposta pi&ugrave; veloce agli item vicini</li>
    </ul>
    <div class="ok">
      &#9989; <strong>Come usare v6:</strong>
      <ol style="margin-top:8px">
        <li>Premi <strong>IMPOSTA POZ</strong> &rarr; tocca 1, 2 o 3 slot pozione sullo schermo &rarr; la pozione parte da sola</li>
        <li>Premi <strong>LOOT: ON</strong> &rarr; muoviti liberamente nel gioco, il bot raccoglie gli item quando ci passi vicino</li>
      </ol>
    </div>
  </div>

  <div class="card">
    <h2>&#128241; Info App</h2>
    <table>
      <tr><th>Campo</th><th>Valore</th></tr>
      <tr><td>Package</td><td><code>com.movile2.bot</code></td></tr>
      <tr><td>Linguaggio</td><td>Kotlin</td></tr>
      <tr><td>Min SDK</td><td>26 (Android 8.0)</td></tr>
      <tr><td>Target SDK</td><td>34 (Android 14)</td></tr>
      <tr><td>Build System</td><td>Gradle Kotlin DSL</td></tr>
      <tr><td>Gioco target</td><td><code>com.vendsoft.mobile2</code> (Unreal Engine 4 &mdash; clone Metin2)</td></tr>
    </table>
  </div>

  <div class="card">
    <h2>&#128270; Analisi libUE4.so &mdash; Risultati</h2>
    <p style="color:#aaa;font-size:.9rem;margin-bottom:14px">Reverse engineering del binario nativo ARM64 (182 MB) — 821.971 stringhe estratte.</p>

    <div class="danger">
      &#128683; <strong>EMULATOR DETECTION ATTIVO</strong><br>
      Il gioco rileva ed esclude i seguenti emulatori. Usa <strong>solo dispositivo fisico reale</strong>:
      <pre style="margin-top:8px">com.bluestacks.*   &rarr; BlueStacks
com.bignox.*       &rarr; BigNox / NoxPlayer
com.microvirt.*    &rarr; MEmu
com.nox.mopen.app  &rarr; NoxPlayer (alt)
com.vphone.*       &rarr; vPhone
org.chromium.arc   &rarr; Android su ChromeOS (ARC)</pre>
    </div>

    <div style="margin-top:14px">
      <strong style="color:#4fc3f7">Costanti di combattimento trovate nel codice nativo:</strong>
      <table style="margin-top:10px">
        <tr><th>Costante</th><th>Significato</th><th>Impatto sul bot</th></tr>
        <tr><td><code>ATTACK_RANGE</code></td><td>Raggio massimo attacco</td><td><span class="tag-ok">Confermato</span> il bot si avvicina prima di attaccare</td></tr>
        <tr><td><code>ATTACK_SPEED</code></td><td>Velocit&agrave; attacco mob</td><td><span class="tag-ok">TAP_MS=60ms allineato</span></td></tr>
        <tr><td><code>AttackTimeMsec</code></td><td>Durata gesto attacco (ms)</td><td><span class="tag-ok">60ms confermato corretto</span></td></tr>
        <tr><td><code>AGGRESSIVE_HP_PCT</code></td><td>% HP che rende mob aggressivo</td><td><span class="tag-warn">Difesa attiva gi&agrave; al primo drop HP</span></td></tr>
        <tr><td><code>AGGRESSIVE_SIGHT</code></td><td>Raggio visione mob aggressivi</td><td><span class="tag-warn">Patrol raggio deve essere contenuto</span></td></tr>
        <tr><td><code>MOB_COLOR</code></td><td>Colore nome mob (rosso)</td><td><span class="tag-ok">R&gt;160,G&lt;130,B&lt;120 confermato</span></td></tr>
        <tr><td><code>attackable</code></td><td>Bool: mob pu&ograve; essere attaccato</td><td><span class="tag-ok">Pixel detection filtra gi&agrave; correttamente</span></td></tr>
        <tr><td><code>attackDistance</code></td><td>Distanza attacco AI mob</td><td><span class="tag-ok">Joystick avvicina il personaggio</span></td></tr>
        <tr><td><code>dropLocs</code> / <code>DROP_ITEM</code></td><td>Posizioni drop loot</td><td><span class="tag-ok">Centroide pixel bianco/verde corretto</span></td></tr>
        <tr><td><code>SKILL_VNUM0&ndash;4</code></td><td>5 slot abilit&agrave;</td><td><span class="tag-ok">Bot implementa gi&agrave; 5 skill</span></td></tr>
      </table>
    </div>

    <div class="ok" style="margin-top:14px">
      &#9989; <strong>Origine gioco confermata</strong>: Mobile2 Global &egrave; un clone di <strong>Metin2</strong> sviluppato in Turchia
      (evidenza: valuta <code>yang</code>, stat <code>buyuAttack</code>/<code>buyuDef</code> = magia attacco/difesa in turco,
      <code>SKILL_VNUM</code> = numerazione virtuale abilit&agrave; tipica Metin2, <code>coinBonusYuzde</code> = "yüzde" = percento in turco).
    </div>

    <div class="warn" style="margin-top:10px">
      &#128161; <strong>Server IP non trovato</strong> nel .so &mdash; gli endpoint sono nel file PAK scaricato all&#39;avvio via OBBDownloaderService (non reversibile facilmente senza il file OBB).
    </div>
  </div>

  <div class="card">
    <h2>&#128274; Prima configurazione sull&#39;Android</h2>
    <ol>
      <li>Installa l&#39;APK &rarr; apri l&#39;app</li>
      <li>Tocca <strong>1. Permesso Overlay</strong> &rarr; abilita</li>
      <li>Tocca <strong>2. Accessibilit&agrave;</strong> &rarr; trova <em>Movile2 Bot</em> e abilita</li>
      <li>Configura le coordinate toccando <strong>Imposta</strong> accanto ad ogni voce</li>
      <li>Tocca <em>Bordo Sinistro Barra HP</em> per abilitare il monitor vita (barra rossa in alto a sinistra)</li>
      <li>Imposta la larghezza della barra a vita piena in pixel e la soglia pozione (es. 85%)</li>
      <li>Tocca <strong>SALVA IMPOSTAZIONI</strong></li>
      <li>Tocca <strong>Avvia Overlay</strong> &rarr; passa al gioco</li>
      <li>Usa il pannello flottante per START/STOP</li>
    </ol>
    <div class="warn">
      &#9888; <strong>MIUI / Samsung:</strong> Se l&#39;accessibilit&agrave; dice &ldquo;impostazione non disponibile&rdquo;,
      vai in <em>Impostazioni &rsaquo; Accessibilit&agrave; &rsaquo; App installate</em> (non il menu principale)
      e abilita Movile2 Bot da l&igrave;.
    </div>
  </div>

  <div class="card">
    <h2>&#128293; Logica Modalit&agrave; Difesa</h2>
    <ul>
      <li>Il bot confronta la lettura HP tra uno screenshot e il successivo (ogni ~1.2s)</li>
      <li>Se l&#39;HP cala per <strong>1+ ciclo</strong> &rarr; entra in <strong>DIFESA</strong>: spam attacco + abilit&agrave;</li>
      <li>Se durante la difesa l&#39;HP scende sotto soglia &rarr; usa pozione immediatamente</li>
      <li>Quando l&#39;HP si stabilizza per <strong>4 cicli</strong> &rarr; torna alla caccia normale</li>
      <li>L&#39;overlay mostra &#x1F6E1; <strong>DIFESA</strong> in giallo durante questa fase</li>
    </ul>
  </div>

  <div class="card">
    <h2>&#128202; State Machine v4</h2>
    <table>
      <tr><th>Fase</th><th>Ciclo</th><th>Logica</th></tr>
      <tr><td><strong>HUNT</strong></td><td>~800ms</td><td>Patrol joystick, rileva mob via pixel rossi, attacca + abilit&agrave;</td></tr>
      <tr><td><strong>DEFEND</strong></td><td>~400ms</td><td>Spam attacco circolare attorno al personaggio, nessun patrol</td></tr>
      <tr><td><strong>POTION</strong></td><td>1 tap</td><td>Usa slot pozione, torna a HUNT o DEFEND</td></tr>
      <tr><td><strong>REFILL</strong></td><td>1 swipe</td><td>Ricarica slot pozione dall&#39;inventario</td></tr>
    </table>
  </div>

  <div class="card">
    <h2>&#128640; Build APK via GitHub Actions</h2>
    <ol>
      <li>Fai push su GitHub</li>
      <li>GitHub Actions esegue <code>gradle assembleDebug</code> automaticamente</li>
      <li>Scarica l&#39;APK da <strong>Actions &rsaquo; Artifacts</strong></li>
    </ol>
    <pre>gradle assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk</pre>
  </div>
</div>
</body>
</html>"""

class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-type","text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(PAGE.encode())
    def log_message(self, *a): pass

print(f"Server avviato su http://{HOST}:{PORT}")
with socketserver.TCPServer((HOST, PORT), H) as s:
    s.serve_forever()
