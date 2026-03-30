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
.wrap{max-width:820px;margin:0 auto}
h1{font-size:2rem;color:#4fc3f7;margin-bottom:6px}
.sub{color:#888;margin-bottom:30px;font-size:.95rem}
.card{background:#1a1a2e;border:1px solid #1e3a5f;border-radius:10px;padding:22px;margin-bottom:18px}
.card h2{color:#4fc3f7;font-size:1.1rem;margin-bottom:14px}
table{width:100%;border-collapse:collapse;font-size:.9rem}
td,th{padding:8px 10px;border-bottom:1px solid #1e3a5f;text-align:left}
th{color:#4fc3f7;font-weight:600}
code,pre{background:#0a0a14;border-radius:6px;padding:3px 8px;font-size:.88rem;color:#a5d6a7}
pre{display:block;padding:14px;overflow-x:auto;margin-top:8px}
.badge{display:inline-block;background:#1e3a5f;color:#4fc3f7;border-radius:20px;padding:3px 12px;font-size:.8rem;margin:2px}
ol,ul{padding-left:18px}
ol li,ul li{margin-bottom:8px;line-height:1.6;color:#ccc}
.step-num{display:inline-block;background:#4fc3f7;color:#0f0f1a;border-radius:50%;width:22px;height:22px;text-align:center;line-height:22px;font-weight:bold;font-size:.8rem;margin-right:8px}
a{color:#80cbc4;text-decoration:none}
a:hover{text-decoration:underline}
</style>
</head>
<body>
<div class="wrap">
  <h1>&#129302; Movile2 Kotlin Overlay Bot</h1>
  <p class="sub">App Android per automazione con overlay e Accessibility Service</p>

  <div class="card">
    <h2>&#128241; Informazioni App</h2>
    <table>
      <tr><th>Campo</th><th>Valore</th></tr>
      <tr><td>Package</td><td><code>com.movile2.bot</code></td></tr>
      <tr><td>Linguaggio</td><td>Kotlin</td></tr>
      <tr><td>Min SDK</td><td>26 (Android 8.0 Oreo)</td></tr>
      <tr><td>Target SDK</td><td>34 (Android 14)</td></tr>
      <tr><td>Build System</td><td>Gradle (Kotlin DSL)</td></tr>
      <tr><td>Version</td><td>1.0</td></tr>
    </table>
  </div>

  <div class="card">
    <h2>&#9881; Componenti</h2>
    <ul>
      <li><strong>MainActivity</strong> &mdash; Gestione permessi: overlay, accessibility, start/stop servizio</li>
      <li><strong>OverlayService</strong> &mdash; Bottone flottante con notifica persistente (Foreground Service)</li>
      <li><strong>BotAccessibilityService</strong> &mdash; Loop gesture per attacchi, abilit&agrave; e movimento</li>
    </ul>
  </div>

  <div class="card">
    <h2>&#127919; Coordinate Gesture (calibrare per il proprio dispositivo)</h2>
    <table>
      <tr><th>Azione</th><th>Coordinate</th><th>File da modificare</th></tr>
      <tr><td>Attack</td><td>(950, 700)</td><td><code>BotAccessibilityService.kt</code></td></tr>
      <tr><td>Skill 1</td><td>(850, 650)</td><td><code>BotAccessibilityService.kt</code></td></tr>
      <tr><td>Skill 2</td><td>(780, 720)</td><td><code>BotAccessibilityService.kt</code></td></tr>
      <tr><td>Move swipe</td><td>(500,800)&rarr;(500,400)</td><td><code>BotAccessibilityService.kt</code></td></tr>
    </table>
  </div>

  <div class="card">
    <h2>&#128640; Come costruire l'APK</h2>
    <p style="margin-bottom:12px;color:#aaa">Usa <strong>GitHub Actions</strong> (file gi&agrave; configurato nel repo) per build automatico ad ogni push:</p>
    <ol>
      <li><span class="step-num">1</span>Push del codice su GitHub</li>
      <li><span class="step-num">2</span>GitHub Actions esegue automaticamente <code>gradle assembleDebug</code></li>
      <li><span class="step-num">3</span>Scarica l'APK dagli <strong>Artifacts</strong> del workflow in Actions</li>
    </ol>
    <p style="margin-top:14px;color:#aaa">Oppure su macchina locale con Android SDK:</p>
    <pre>gradle assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk</pre>
  </div>

  <div class="card">
    <h2>&#128274; Setup sull'Android</h2>
    <ol>
      <li>Installa l'APK sul dispositivo</li>
      <li>Apri l'app &rarr; tocca <strong>Grant Overlay Permission</strong></li>
      <li>Tocca <strong>Open Accessibility Settings</strong> &rarr; abilita <em>Movile2 Bot</em></li>
      <li>Tocca <strong>Start Overlay</strong> &rarr; passa al gioco</li>
      <li>Usa il bottone flottante per ON/OFF</li>
    </ol>
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
