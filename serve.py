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
.wrap{max-width:860px;margin:0 auto}
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
.new{background:#0d3b1e;border:1px solid #1b5e20;border-radius:4px;padding:2px 8px;font-size:.78rem;color:#69f0ae;margin-left:6px;vertical-align:middle}
</style>
</head>
<body>
<div class="wrap">
  <h1>&#129302; Movile2 Bot</h1>
  <p class="sub">Bot Android per MMORPG &mdash; Kotlin + Accessibility Service &mdash; <strong>v4</strong></p>

  <div class="card">
    <h2>&#10024; Funzionalit&agrave; v4</h2>
    <ul>
      <li><strong>Rilevamento mostri via pixel</strong> &mdash; screenshot ogni 1.2s, trova pixel rossi (nomi nemici), calcola centroide e tocca per selezionare il bersaglio</li>
      <li><strong>Fix rotazione camera</strong> &mdash; la camera ruota solo se NON c&#39;&egrave; gi&agrave; un bersaglio visibile, eliminando il loop di ricerca</li>
      <li><strong>Monitor barra HP (top-left)</strong> <span class="new">NEW</span> &mdash; scansiona i pixel rossi della barra vita; pozione automatica se scende sotto soglia configurabile</li>
      <li><strong>Modalit&agrave; Difesa</strong> <span class="new">NEW</span> &mdash; se l&#39;HP continua a calare (mostri che attaccano), spam attacco immediato su tutti i mostri vicini; overlay mostra &#x1F6E1; DIFESA in giallo</li>
      <li><strong>3 abilit&agrave; con cooldown separati</strong> <span class="new">NEW</span> &mdash; Abilit&agrave; 1, 2 e 3 opzionale, ognuna con il proprio timer</li>
      <li><strong>Timer di sessione</strong> <span class="new">NEW</span> &mdash; auto-stop dopo X minuti configurabili (0 = infinito)</li>
      <li><strong>Joystick virtuale</strong> &mdash; pattuglia N&rarr;E&rarr;S&rarr;W con raggio configurabile</li>
      <li><strong>Pozioni automatiche + ricarica</strong> &mdash; tap slot pozione; se finiscono, swipe dall&#39;inventario allo slot</li>
      <li><strong>Limite massimo uccisioni</strong> &mdash; il bot si ferma al raggiungimento del limite</li>
      <li><strong>Overlay draggabile</strong> &mdash; pannello flottante con contatore kills, stato RUNNING/DIFESA/STOP e pulsanti Start/Stop</li>
      <li><strong>Fix crash Android 14</strong> &mdash; <code>foregroundServiceType="specialUse"</code> nel manifest</li>
    </ul>
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
      <tr><td>Gioco target</td><td><code>com.vendsoft.mobile2</code> (Unreal Engine 4)</td></tr>
    </table>
  </div>

  <div class="card">
    <h2>&#128274; Prima configurazione sull&#39;Android</h2>
    <ol>
      <li>Installa l&#39;APK &rarr; apri l&#39;app</li>
      <li>Tocca <strong>1. Permesso Overlay</strong> &rarr; abilita</li>
      <li>Tocca <strong>2. Accessibilit&agrave;</strong> &rarr; trova <em>Movile2 Bot</em> e abilita</li>
      <li>Configura le coordinate toccando <strong>Imposta</strong> accanto ad ogni voce</li>
      <li><strong>[NUOVO]</strong> Tocca <em>Bordo Sinistro Barra HP</em> per abilitare il monitor vita (barra rossa in alto a sinistra)</li>
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
      <li>Se l&#39;HP cala per <strong>2 cicli consecutivi</strong> &rarr; entra in <strong>DIFESA</strong>: spam attacco + abilit&agrave; senza aspettare bersaglio specifico</li>
      <li>Se durante la difesa l&#39;HP scende sotto soglia &rarr; usa pozione immediatamente</li>
      <li>Quando l&#39;HP si stabilizza per <strong>3 cicli consecutivi</strong> &rarr; torna alla caccia normale</li>
      <li>L&#39;overlay mostra &#x1F6E1; <strong>DIFESA</strong> in giallo durante questa fase</li>
    </ul>
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
