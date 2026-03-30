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
</style>
</head>
<body>
<div class="wrap">
  <h1>&#129302; Movile2 Bot</h1>
  <p class="sub">Bot Android per MMORPG &mdash; Kotlin + Accessibility Service</p>

  <div class="card">
    <h2>&#10024; Funzionalit&agrave; v2</h2>
    <ul>
      <li><strong>Mappa area di ricerca</strong> &mdash; imposta i due angoli della stanza toccan do lo schermo direttamente</li>
      <li><strong>Ricerca per nome mostro</strong> &mdash; scrivi il nome e il bot lo cerca nell'albero UI (funziona se il gioco usa viste Android standard)</li>
      <li><strong>Griglia di ricerca 5x4</strong> &mdash; il PG percorre sistematicamente tutta l'area in pattern a serpentina</li>
      <li><strong>Attacco continuo + abilit&agrave; con cooldown</strong> &mdash; configurabili in secondi</li>
      <li><strong>Pozioni automatiche</strong> &mdash; tap sullo slot, e se finiscono, trascina dall'inventario allo slot</li>
      <li><strong>Limite massimo uccisioni</strong> &mdash; il bot si ferma automaticamente raggiunto il limite</li>
      <li><strong>Joystick virtuale</strong> &mdash; imposta il centro del joystick e il bot pattuglia la stanza in automatico (N&rarr;E&rarr;S&rarr;W)</li>
      <li><strong>Rotazione visuale</strong> &mdash; imposta un punto nella zona centrale dello schermo; ogni 4 cicli il bot swipe destra&harr;sinistra per ruotare la telecamera e cercare i mostri</li>
      <li><strong>Timing sicuro</strong> &mdash; ciclo fisso 800ms: joystick (350ms) &rarr; attacco (+400ms) &rarr; abilit&agrave; (+520/+640ms) &rarr; ciclo successivo (+800ms), nessun conflitto tra gesti</li>
      <li><strong>Overlay draggabile</strong> &mdash; pannello flottante con contatore kills e pulsanti Start/Stop</li>
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
    </table>
  </div>

  <div class="card">
    <h2>&#128274; Prima configurazione sull'Android</h2>
    <ol>
      <li>Installa l'APK &rarr; apri l'app</li>
      <li>Tocca <strong>1. Permesso Overlay</strong> &rarr; abilita</li>
      <li>Tocca <strong>2. Accessibilit&agrave;</strong> &rarr; trova <em>Movile2 Bot</em> e abilita</li>
      <li>Configura le coordinate toccando <strong>Imposta</strong> accanto ad ogni voce</li>
      <li>Tocca <strong>SALVA IMPOSTAZIONI</strong></li>
      <li>Tocca <strong>Avvia Overlay</strong> &rarr; passa al gioco</li>
      <li>Usa il pannello flottante per START/STOP</li>
    </ol>
    <div class="warn">
      ⚠ <strong>MIUI / Samsung:</strong> Se l'accessibilit&agrave; dice "impostazione non disponibile",
      vai in <em>Impostazioni &rsaquo; Accessibilit&agrave; &rsaquo; App installate</em> (non il menu principale)
      e abilita Movile2 Bot da l&igrave;.
    </div>
  </div>

  <div class="card">
    <h2>&#128640; Build APK via GitHub Actions</h2>
    <ol>
      <li>Fai push su GitHub</li>
      <li>GitHub Actions esegue <code>gradle assembleDebug</code> automaticamente</li>
      <li>Scarica l'APK da <strong>Actions &rsaquo; Artifacts</strong></li>
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
