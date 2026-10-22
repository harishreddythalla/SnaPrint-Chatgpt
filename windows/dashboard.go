package main

import (
	"encoding/json"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"
)

type dashboardStatus struct {
	Connected    bool          `json:"connected"`
	LastSeen     string        `json:"lastSeen,omitempty"`
	LastPhoto    string        `json:"lastPhoto,omitempty"`
	LastError    string        `json:"lastError,omitempty"`
	EventName    string        `json:"eventName,omitempty"`
	EventActive  bool          `json:"eventActive"`
	EventStarted string        `json:"eventStarted,omitempty"`
	Destination  string        `json:"destination"`
	PhotoCount   int           `json:"photoCount"`
	EventFolders []eventFolder `json:"events"`
}
type eventFolder struct {
	Name     string `json:"name"`
	Photos   int    `json:"photos"`
	Path     string `json:"path"`
	Modified string `json:"modified"`
}

func (r *Receiver) startDashboard() {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, req *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(dashboardHTML))
	})
	mux.HandleFunc("/api/status", func(w http.ResponseWriter, req *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(r.dashboardSnapshot())
	})
	mux.HandleFunc("/api/open-folder", func(w http.ResponseWriter, req *http.Request) {
		if req.Method != http.MethodPost {
			http.Error(w, "POST required", 405)
			return
		}
		if runtime.GOOS == "windows" {
			_ = exec.Command("explorer.exe", r.cfg.DestDir).Start()
		}
		w.WriteHeader(http.StatusNoContent)
	})
	_ = http.ListenAndServe("127.0.0.1:8766", mux)
}
func openDashboard() {
	if runtime.GOOS == "windows" {
		_ = exec.Command("rundll32.exe", "url.dll,FileProtocolHandler", "http://127.0.0.1:8766").Start()
	}
}
func (r *Receiver) dashboardSnapshot() dashboardStatus {
	r.uiMu.RLock()
	s := r.uiSession
	connected := r.uiConnected
	seen := r.uiLastSeen
	lastPhoto := r.uiLastPhoto
	lastErr := r.uiLastError
	r.uiMu.RUnlock()
	out := dashboardStatus{Connected: connected, LastPhoto: lastPhoto, LastError: lastErr, Destination: r.cfg.DestDir, EventName: s.Name, EventActive: s.Active}
	if !seen.IsZero() {
		out.LastSeen = seen.Format(time.RFC3339)
	}
	if s.StartedAt > 0 {
		out.EventStarted = time.UnixMilli(s.StartedAt).Format(time.RFC3339)
	}
	r.folderScanMu.Lock()
	if time.Since(r.folderScanAt) >= 15*time.Second || r.folderScanAt.IsZero() {
		entries, _ := os.ReadDir(r.cfg.DestDir)
		var folders []eventFolder
		total := 0
		for _, e := range entries {
			if !e.IsDir() {
				continue
			}
			p := filepath.Join(r.cfg.DestDir, e.Name())
			files := 0
			var newest time.Time
			_ = filepath.WalkDir(p, func(path string, d os.DirEntry, err error) error {
				if err != nil {
					return nil
				}
				if d.IsDir() {
					return nil
				}
				ext := strings.ToLower(filepath.Ext(path))
				if ext == ".jpg" || ext == ".jpeg" || ext == ".png" || ext == ".webp" {
					files++
					if info, x := d.Info(); x == nil && info.ModTime().After(newest) {
						newest = info.ModTime()
					}
				}
				return nil
			})
			total += files
			ef := eventFolder{Name: e.Name(), Photos: files, Path: p}
			if !newest.IsZero() {
				ef.Modified = newest.Format(time.RFC3339)
			}
			folders = append(folders, ef)
		}
		sort.Slice(folders, func(i, j int) bool { return folders[i].Name > folders[j].Name })
		r.folderCache = folders
		r.folderCountCache = total
		r.folderScanAt = time.Now()
	}
	out.EventFolders = append([]eventFolder(nil), r.folderCache...)
	out.PhotoCount = r.folderCountCache
	r.folderScanMu.Unlock()
	return out
}

const dashboardHTML = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>SnapToPrint</title><style>
:root{color-scheme:light;--ink:#17233c;--muted:#66748b;--blue:#2563eb;--line:#e4eaf3;--bg:#f4f7fc}*{box-sizing:border-box}body{margin:0;background:linear-gradient(135deg,#f4f8ff,#f8f7ff 60%,#fff7f1);font:15px/1.5 'Segoe UI',Arial,sans-serif;color:var(--ink)}header{background:#fff;border-bottom:1px solid var(--line);padding:20px 28px;display:flex;align-items:center;gap:14px;position:sticky;top:0} .logo{width:42px;height:42px;border-radius:14px;background:linear-gradient(135deg,#2563eb,#6d28d9);display:grid;place-items:center;color:white;font-size:23px}h1{font-size:21px;margin:0}header p{margin:0;color:var(--muted);font-size:12px}.wrap{max-width:1200px;margin:26px auto;padding:0 20px}.hero{display:flex;justify-content:space-between;align-items:center;margin-bottom:18px;gap:16px}.hero h2{font-size:27px;margin:0}.hero p{color:var(--muted);margin:4px 0}.pill{padding:7px 12px;border-radius:99px;background:#e8f8ef;color:#14804a;font-weight:700;font-size:13px}.grid{display:grid;grid-template-columns:1.25fr .75fr;gap:16px}.card{background:rgba(255,255,255,.92);border:1px solid var(--line);border-radius:20px;padding:21px;box-shadow:0 8px 28px #24395b0a}.card h3{margin:0 0 14px;font-size:16px}.event{font-size:23px;font-weight:750;margin:3px 0 8px}.muted{color:var(--muted)}.metrics{display:grid;grid-template-columns:repeat(2,1fr);gap:10px;margin:20px 0}.metric{padding:14px;border-radius:14px;background:#f5f8ff}.metric strong{display:block;font-size:20px}.metric span{font-size:12px;color:var(--muted)}.btn{border:0;border-radius:12px;padding:12px 15px;font-weight:650;cursor:pointer;background:var(--blue);color:white}.btn.secondary{background:#edf3ff;color:#1d4ed8}.btn:active{transform:translateY(1px)}.path{font-size:12px;word-break:break-all;background:#f8fafc;border-radius:10px;padding:12px;margin:10px 0 14px}.events{grid-column:1/-1}.event-row{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:13px 0;border-top:1px solid #eef2f7}.event-row strong{display:block}.event-row small{color:var(--muted)}.dot{display:inline-block;width:9px;height:9px;border-radius:50%;background:#16a34a;margin-right:7px}.warn{color:#b45309;background:#fffbeb;border-radius:12px;padding:12px;margin-top:12px;font-size:13px}.help{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.help div{background:#f7f9fd;border-radius:12px;padding:13px;font-size:13px}.help b{display:block;margin-bottom:5px}.foot{font-size:12px;color:var(--muted);text-align:center;padding:22px}@media(max-width:760px){.grid{grid-template-columns:1fr}.events{grid-column:auto}.help{grid-template-columns:1fr}.hero{align-items:flex-start;flex-direction:column}.wrap{padding:0 12px}.card{padding:16px}}
</style></head><body><header><div class="logo">📷</div><div><h1>SnapToPrint</h1><p>Instant photo transfer · Works without internet</p></div></header><main class="wrap"><div class="hero"><div><h2>Your event, in sync.</h2><p>Photos are verified before they're saved to your laptop.</p></div><span id="connection" class="pill">Checking connection…</span></div><div class="grid"><section class="card"><h3>Current event</h3><div id="eventName" class="event">Waiting for phone…</div><div id="eventInfo" class="muted">Start an event in the Android app.</div><div class="metrics"><div class="metric"><strong id="photoCount">0</strong><span>Photos saved in event folders</span></div><div class="metric"><strong id="lastPhoto">—</strong><span>Most recent photo</span></div></div><h3>Save location</h3><div id="destination" class="path">Loading…</div><button class="btn" onclick="openFolder()">▣ &nbsp; Open photos folder</button><div id="error" class="warn" hidden></div></section><section class="card"><h3>Connection health</h3><div id="healthTitle" class="event" style="font-size:19px">Checking…</div><p id="healthText" class="muted">The receiver checks the phone continuously and retries if the connection drops.</p><div class="path">Local-only dashboard: 127.0.0.1<br>No internet connection required.</div><h3>Quick fixes</h3><div class="help" style="grid-template-columns:1fr"><div><b>Phone not found</b>Connect the laptop to the phone's hotspot and start an event in the Android app.</div><div><b>Connection dropped</b>Keep the event active. Pending photos remain queued on the phone and retry automatically.</div><div><b>Pairing issue</b>Reset pairing on both devices, restart the receiver, then enter the new six-digit code.</div></div></section><section class="card events"><h3>Previous events <span class="muted" style="font-size:12px;font-weight:400">· folders found on this laptop</span></h3><div id="events"><p class="muted">Looking for event folders…</p></div></section></div><div class="foot">SnapToPrint · Local event dashboard · Transfer engine unchanged</div></main><script>
async function refresh(){try{const r=await fetch('/api/status',{cache:'no-store'});const s=await r.json();document.getElementById('connection').textContent=s.connected?'● Phone connected':'○ Waiting for phone';document.getElementById('connection').style.background=s.connected?'#e8f8ef':'#fff4e5';document.getElementById('connection').style.color=s.connected?'#14804a':'#9a5b12';document.getElementById('eventName').textContent=s.eventName||'No event detected yet';document.getElementById('eventInfo').textContent=s.eventActive?'Event active · Started '+new Date(s.eventStarted).toLocaleString():'Start or finish events in the Android app. '+(s.eventName?'Last event: '+s.eventName:'');document.getElementById('photoCount').textContent=s.photoCount;document.getElementById('lastPhoto').textContent=s.lastPhoto||'—';document.getElementById('destination').textContent=s.destination;document.getElementById('healthTitle').textContent=s.connected?'Connected and monitoring':'Reconnecting automatically';document.getElementById('healthText').textContent=s.connected?'Last phone response: '+(s.lastSeen?new Date(s.lastSeen).toLocaleTimeString():'just now')+'. New photos are checked automatically.':'The receiver will keep trying. Check that the phone hotspot is on and the event is active.';const er=document.getElementById('error');er.hidden=!s.lastError;er.textContent=s.lastError?'Latest connection detail: '+s.lastError:'';document.getElementById('events').innerHTML=s.events.length?s.events.map(e=>'<div class="event-row"><div><strong>'+escapeHtml(e.name)+'</strong><small>'+(e.modified?'Last photo: '+new Date(e.modified).toLocaleString():'No photos detected in folder')+'</small><div class="muted" style="font-size:12px">'+escapeHtml(e.path)+'</div></div><div style="text-align:right;white-space:nowrap"><strong>'+e.photos+'</strong><small style="display:block">photos</small></div></div>').join(''):'<p class="muted">No event folders yet. Start an event on your phone and take a photo.</p>'}catch(e){document.getElementById('connection').textContent='Dashboard reconnecting';}}function escapeHtml(s){return String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}async function openFolder(){await fetch('/api/open-folder',{method:'POST'})}refresh();setInterval(refresh,2500);
</script></body></html>`
