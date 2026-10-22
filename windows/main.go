package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

const (
	appName         = "Snap-to-Print"
	defaultPort     = 8765
	protocolVer     = "v1"
	requestTimeout  = 8 * time.Second
	transferTimeout = 2 * time.Minute
)

type Config struct {
	PhoneURL string `json:"phoneUrl"`
	Token    string `json:"token"`
	DestDir  string `json:"destinationDirectory"`
}

type Health struct {
	OK      bool   `json:"ok"`
	Service string `json:"service"`
	Version int    `json:"version"`
}

type Photo struct {
	PhotoID            string `json:"photoId"`
	SessionID          string `json:"sessionId"`
	EventName          string `json:"eventName"`
	SessionStartedAtMS int64  `json:"sessionStartedAtMs"`
	Sequence           int    `json:"sequence"`
	FileName           string `json:"fileName"`
	MIMEType           string `json:"mimeType"`
	Size               int64  `json:"size"`
	SHA256             string `json:"sha256"`
	CaptureTimeMS      int64  `json:"captureTimeMs"`
}

type Session struct {
	SessionID string `json:"sessionId"`
	Name      string `json:"name"`
	StartedAt int64  `json:"startedAtMs"`
	Active    bool   `json:"active"`
	Status    string `json:"status"`
}

type QueueResponse struct {
	Empty bool  `json:"empty"`
	Photo Photo `json:"photo"`
}

type Ack struct {
	SHA256 string `json:"sha256"`
	Size   int64  `json:"size"`
}

type Receiver struct {
	cfg              Config
	cfgPath          string
	http             *http.Client
	stdoutMu         sync.Mutex
	seenState        string
	uiMu             sync.RWMutex
	uiSession        Session
	uiConnected      bool
	uiLastSeen       time.Time
	uiLastPhoto      string
	uiLastError      string
	folderScanMu     sync.Mutex
	folderScanAt     time.Time
	folderCache      []eventFolder
	folderCountCache int
}

func main() {
	phoneFlag := flag.String("phone", "", "Phone hotspot gateway/IP (optional; auto-detected by default)")
	destFlag := flag.String("dest", "", "Destination folder (optional)")
	resetFlag := flag.Bool("reset-pairing", false, "Forget the saved phone pairing and pair again")
	checkFlag := flag.Bool("check-network", false, "Run network discovery diagnostics and exit")
	flag.Parse()

	if runtime.GOOS != "windows" {
		fmt.Printf("%s receiver is intended to run on Windows.\n", appName)
		fmt.Println("This host build is for development/testing only.")
	}

	cfgPath := configPath()
	cfg := loadConfig(cfgPath)
	if *resetFlag {
		cfg.Token = ""
		cfg.PhoneURL = ""
	}
	if *phoneFlag != "" {
		cfg.PhoneURL = normalizeBaseURL(*phoneFlag)
	}
	if *destFlag != "" {
		cfg.DestDir = expandPath(*destFlag)
	}
	if cfg.DestDir == "" {
		cfg.DestDir = defaultDestination()
	}

	r := &Receiver{
		cfg:     cfg,
		cfgPath: cfgPath,
		http: &http.Client{
			// API calls use explicit shorter contexts; large image bodies may take longer than a few seconds.
			Timeout: 130 * time.Second,
			Transport: &http.Transport{
				Proxy:                 nil, // Never route local transfers through an environment proxy.
				DialContext:           (&net.Dialer{Timeout: 2 * time.Second, KeepAlive: 15 * time.Second}).DialContext,
				MaxIdleConns:          4,
				IdleConnTimeout:       15 * time.Second,
				TLSHandshakeTimeout:   2 * time.Second,
				ResponseHeaderTimeout: 4 * time.Second,
			},
		},
	}

	fmt.Printf("\n%s — Windows receiver\n", appName)
	fmt.Println("Local-only transfer. No internet service is used by this receiver.")
	fmt.Printf("Destination: %s\n", cfg.DestDir)
	fmt.Println("Keep this window open during the event. Press Ctrl+C to stop.")

	if *checkFlag {
		candidates := gatewayCandidates()
		fmt.Println("Detected gateway candidates:")
		for _, c := range candidates {
			fmt.Println("  ", c)
		}
		if cfg.PhoneURL != "" {
			fmt.Println("Configured phone URL:", cfg.PhoneURL)
		}
		for _, c := range candidates {
			u := "http://" + net.JoinHostPort(c, fmt.Sprint(defaultPort))
			if _, err := r.probe(u); err == nil {
				fmt.Println("Snap-to-Print service found at", u)
				return
			}
		}
		fmt.Println("No phone service responded. Start an event in the Android app and rerun this check.")
		return
	}

	if err := os.MkdirAll(cfg.DestDir, 0o755); err != nil {
		fatal("Cannot create destination folder", err)
	}
	if err := r.connectAndPair(); err != nil {
		fatal("Setup failed", err)
	}
	if err := r.saveConfig(); err != nil {
		fmt.Printf("Warning: could not save pairing configuration: %v\n", err)
		fmt.Println("You may need to pair again the next time the receiver starts.")
	}

	go r.startDashboard()
	openDashboard()
	r.runForever()
}

func (r *Receiver) runForever() {
	fmt.Println("\nReceiver running. Photos will be saved automatically.")
	fmt.Println("Open the event folder whenever you're ready to use Canva.")
	consecutiveErrors := 0
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		err := r.pollOnce(ctx)
		cancel()
		if err != nil {
			r.uiMu.Lock()
			r.uiConnected = false
			r.uiLastError = err.Error()
			r.uiMu.Unlock()
			consecutiveErrors++
			if consecutiveErrors == 1 || consecutiveErrors%5 == 0 {
				r.logState("DISCONNECTED", fmt.Sprintf("Connection unavailable (%v). Keeping phone queue intact; retrying…", err))
			}
			if consecutiveErrors%10 == 0 {
				r.tryRediscover()
			}
			time.Sleep(retryDelay(consecutiveErrors))
			continue
		}
		if consecutiveErrors > 0 {
			r.logState("CONNECTED", "Connection restored. Resuming pending transfers.")
		}
		consecutiveErrors = 0
		time.Sleep(450 * time.Millisecond)
	}
}

func retryDelay(n int) time.Duration {
	if n < 1 {
		n = 1
	}
	d := time.Duration(n) * time.Second
	if d > 8*time.Second {
		d = 8 * time.Second
	}
	return d
}

func (r *Receiver) pollOnce(ctx context.Context) error {
	var session Session
	if err := r.getJSON(ctx, "/api/v1/session/current", &session); err != nil {
		return err
	}
	r.uiMu.Lock()
	r.uiSession = session
	r.uiConnected = true
	r.uiLastSeen = time.Now()
	r.uiLastError = ""
	r.uiMu.Unlock()
	if session.SessionID != "" {
		label := session.Name
		if label == "" {
			label = "Untitled event"
		}
		if session.Active {
			r.logState("ACTIVE:"+session.SessionID, fmt.Sprintf("EVENT ACTIVE — %s", label))
		} else {
			r.logState("INACTIVE:"+session.SessionID, fmt.Sprintf("Last event: %s (%s)", label, session.Status))
		}
	}

	var q QueueResponse
	if err := r.getJSON(ctx, "/api/v1/queue/next", &q); err != nil {
		return err
	}
	if q.Empty || q.Photo.PhotoID == "" {
		return nil
	}
	transferCtx, cancel := context.WithTimeout(context.Background(), transferTimeout)
	defer cancel()
	if err := r.receiveOne(transferCtx, q.Photo); err != nil {
		return err
	}
	return nil
}

func (r *Receiver) receiveOne(ctx context.Context, p Photo) error {
	if p.PhotoID == "" || p.SessionID == "" || p.Size < 1 || len(p.SHA256) != 64 {
		return errors.New("phone returned invalid photo metadata")
	}
	folder := eventFolderName(p)
	dir := filepath.Join(r.cfg.DestDir, folder)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return fmt.Errorf("create event folder: %w", err)
	}
	name := safeImageName(p)
	finalPath := filepath.Join(dir, name)
	partPath := finalPath + ".part"

	// Recover the crash window where the image was committed but its ACK was not sent.
	if info, err := os.Stat(finalPath); err == nil && info.Size() == p.Size {
		if digest, e := hashFile(finalPath); e == nil && strings.EqualFold(digest, p.SHA256) {
			return r.ack(ctx, p, finalPath)
		}
	}
	_ = os.Remove(partPath)
	url := r.cfg.PhoneURL + "/api/v1/photos/" + p.PhotoID + "/content"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("X-Snap-Token", r.cfg.Token)
	resp, err := r.http.Do(req)
	if err != nil {
		return fmt.Errorf("download photo: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
		resp.Body.Close()
		return fmt.Errorf("download photo returned HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	f, err := os.OpenFile(partPath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		resp.Body.Close()
		return fmt.Errorf("create temporary file: %w", err)
	}
	h := sha256.New()
	n, copyErr := io.Copy(io.MultiWriter(f, h), resp.Body)
	bodyCloseErr := resp.Body.Close()
	syncErr := f.Sync()
	closeErr := f.Close()
	if copyErr != nil {
		_ = os.Remove(partPath)
		return fmt.Errorf("write image: %w", copyErr)
	}
	if bodyCloseErr != nil {
		_ = os.Remove(partPath)
		return fmt.Errorf("close response: %w", bodyCloseErr)
	}
	if syncErr != nil {
		_ = os.Remove(partPath)
		return fmt.Errorf("flush temporary image: %w", syncErr)
	}
	if closeErr != nil {
		_ = os.Remove(partPath)
		return fmt.Errorf("close temporary image: %w", closeErr)
	}
	gotHash := hex.EncodeToString(h.Sum(nil))
	if n != p.Size || !strings.EqualFold(gotHash, p.SHA256) {
		_ = os.Remove(partPath)
		return fmt.Errorf("verification failed for %s (size %d/%d, checksum match %t)", p.FileName, n, p.Size, strings.EqualFold(gotHash, p.SHA256))
	}
	if err := os.Rename(partPath, finalPath); err != nil {
		// Do not overwrite an existing final file. The recovery path on the next poll will verify it.
		_ = os.Remove(partPath)
		if info, statErr := os.Stat(finalPath); statErr == nil && info.Size() == p.Size {
			if digest, hashErr := hashFile(finalPath); hashErr == nil && strings.EqualFold(digest, p.SHA256) {
				return r.ack(ctx, p, finalPath)
			}
		}
		return fmt.Errorf("commit image atomically: %w", err)
	}
	if err := r.ack(ctx, p, finalPath); err != nil {
		return err
	}
	r.uiMu.Lock()
	r.uiLastPhoto = filepath.Base(finalPath)
	r.uiMu.Unlock()
	fmt.Printf("[%s] Saved %s (%s)\n", time.Now().Format("15:04:05"), finalPath, humanBytes(p.Size))
	return nil
}

func (r *Receiver) ack(ctx context.Context, p Photo, finalPath string) error {
	info, err := os.Stat(finalPath)
	if err != nil {
		return err
	}
	digest, err := hashFile(finalPath)
	if err != nil {
		return err
	}
	if info.Size() != p.Size || !strings.EqualFold(digest, p.SHA256) {
		return errors.New("existing final file does not match source; refusing to acknowledge")
	}
	payload, _ := json.Marshal(Ack{SHA256: digest, Size: info.Size()})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, r.cfg.PhoneURL+"/api/v1/photos/"+p.PhotoID+"/ack", bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Snap-Token", r.cfg.Token)
	resp, err := r.http.Do(req)
	if err != nil {
		return fmt.Errorf("photo is safely saved but acknowledgement will retry: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
		return fmt.Errorf("acknowledgement returned HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	return nil
}

func (r *Receiver) connectAndPair() error {
	if r.cfg.PhoneURL != "" && r.cfg.Token != "" {
		if _, err := r.probe(r.cfg.PhoneURL); err == nil {
			fmt.Println("Using saved phone pairing:", r.cfg.PhoneURL)
			return nil
		}
		fmt.Println("Saved phone address is not responding; trying automatic discovery…")
	}

	manualURL := ""
	if r.cfg.PhoneURL != "" {
		manualURL = r.cfg.PhoneURL
	}
	candidates := []string{}
	if manualURL != "" {
		candidates = append(candidates, manualURL)
	}
	for _, ip := range gatewayCandidates() {
		candidates = append(candidates, "http://"+net.JoinHostPort(ip, fmt.Sprint(defaultPort)))
	}
	candidates = uniqueStrings(candidates)

	for {
		for _, candidate := range candidates {
			if _, err := r.probe(candidate); err == nil {
				r.cfg.PhoneURL = candidate
				if r.cfg.Token != "" {
					fmt.Println("Reconnected to paired phone:", candidate)
					return nil
				}
				return r.pairAt(candidate)
			}
		}
		fmt.Println("Waiting for the Android app… Ensure the phone hotspot is on, Windows is connected, and an event has been started.")
		fmt.Println("If the phone is using an unusual hotspot address, enter it below. Leave blank to retry auto-detection.")
		fmt.Print("Phone hotspot IP (optional): ")
		scanner := bufio.NewScanner(os.Stdin)
		if !scanner.Scan() {
			return errors.New("standard input closed")
		}
		input := strings.TrimSpace(scanner.Text())
		if input != "" {
			candidate := normalizeBaseURL(input)
			if _, err := r.probe(candidate); err == nil {
				r.cfg.PhoneURL = candidate
				if r.cfg.Token != "" {
					return nil
				}
				return r.pairAt(candidate)
			}
			fmt.Println("The phone service did not respond at that address. Check the phone's connection screen and retry.")
		} else {
			fmt.Println("Retrying discovery in 5 seconds…")
			time.Sleep(5 * time.Second)
			candidates = uniqueStrings(append(candidates, gatewayURLs()...))
		}
	}
}

func (r *Receiver) pairAt(baseURL string) error {
	fmt.Println("Phone found at", baseURL)
	fmt.Println("Open Snap-to-Print on Android and enter the six-digit pairing code shown on its screen.")
	fmt.Print("Pairing code: ")
	scanner := bufio.NewScanner(os.Stdin)
	if !scanner.Scan() {
		return errors.New("pairing code not provided")
	}
	code := strings.TrimSpace(scanner.Text())
	if len(code) != 6 {
		return errors.New("pairing code must contain six digits")
	}
	for _, ch := range code {
		if ch < '0' || ch > '9' {
			return errors.New("pairing code must contain six digits")
		}
	}
	payload, _ := json.Marshal(map[string]string{"code": code})
	ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, baseURL+"/api/v1/pair", bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := r.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
		return fmt.Errorf("pairing failed (HTTP %d): %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	var result struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return err
	}
	if result.Token == "" {
		return errors.New("phone returned an empty pairing token")
	}
	r.cfg.PhoneURL = baseURL
	r.cfg.Token = result.Token
	fmt.Println("Pairing successful. It will be remembered on this laptop.")
	return nil
}

func (r *Receiver) probe(baseURL string) (Health, error) {
	var h Health
	ctx, cancel := context.WithTimeout(context.Background(), 1200*time.Millisecond)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(baseURL, "/")+"/api/v1/health", nil)
	if err != nil {
		return h, err
	}
	resp, err := r.http.Do(req)
	if err != nil {
		return h, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return h, fmt.Errorf("health returned HTTP %d", resp.StatusCode)
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 4096)).Decode(&h); err != nil {
		return h, err
	}
	if !h.OK || h.Service != "snap-to-print" || h.Version != 1 {
		return h, errors.New("service or protocol version mismatch")
	}
	return h, nil
}

func (r *Receiver) getJSON(ctx context.Context, path string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, r.cfg.PhoneURL+path, nil)
	if err != nil {
		return err
	}
	req.Header.Set("X-Snap-Token", r.cfg.Token)
	resp, err := r.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
		return fmt.Errorf("%s returned HTTP %d: %s", path, resp.StatusCode, strings.TrimSpace(string(b)))
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(out); err != nil {
		return fmt.Errorf("decode %s response: %w", path, err)
	}
	return nil
}

func (r *Receiver) tryRediscover() {
	for _, c := range gatewayURLs() {
		if _, err := r.probe(c); err == nil {
			r.cfg.PhoneURL = c
			if err := r.saveConfig(); err == nil {
				fmt.Println("Updated phone address:", c)
			}
			return
		}
	}
}

func (r *Receiver) saveConfig() error {
	if err := os.MkdirAll(filepath.Dir(r.cfgPath), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(r.cfg, "", "  ")
	if err != nil {
		return err
	}
	tmp := r.cfgPath + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	if f, err := os.OpenFile(tmp, os.O_RDWR, 0); err == nil {
		_ = f.Sync()
		_ = f.Close()
	}
	return os.Rename(tmp, r.cfgPath)
}

func (r *Receiver) logState(key, msg string) {
	r.stdoutMu.Lock()
	defer r.stdoutMu.Unlock()
	if r.seenState == key {
		return
	}
	r.seenState = key
	fmt.Printf("[%s] %s\n", time.Now().Format("15:04:05"), msg)
}

func configPath() string {
	base, err := os.UserConfigDir()
	if err != nil || base == "" {
		base = "."
	}
	return filepath.Join(base, "SnapToPrint", "config.json")
}

func loadConfig(path string) Config {
	var c Config
	b, err := os.ReadFile(path)
	if err == nil {
		_ = json.Unmarshal(b, &c)
	}
	c.PhoneURL = strings.TrimRight(strings.TrimSpace(c.PhoneURL), "/")
	c.DestDir = expandPath(strings.TrimSpace(c.DestDir))
	return c
}

func defaultDestination() string {
	if p, err := os.UserHomeDir(); err == nil {
		return filepath.Join(p, "Pictures", "SnapToPrint")
	}
	return filepath.Join(".", "SnapToPrint")
}

func expandPath(p string) string {
	if p == "" {
		return ""
	}
	if strings.HasPrefix(p, "~") {
		if h, err := os.UserHomeDir(); err == nil {
			p = filepath.Join(h, strings.TrimLeft(p[1:], `/\\`))
		}
	}
	return os.ExpandEnv(p)
}

func normalizeBaseURL(s string) string {
	s = strings.TrimSpace(strings.TrimRight(s, "/"))
	if s == "" {
		return ""
	}
	if !strings.Contains(s, "://") {
		s = "http://" + s
	}
	if !strings.Contains(s[strings.Index(s, "://")+3:], ":") {
		s += ":" + fmt.Sprint(defaultPort)
	}
	return strings.TrimRight(s, "/")
}

func gatewayCandidates() []string {
	if runtime.GOOS == "windows" {
		commands := [][]string{
			{"powershell", "-NoProfile", "-NonInteractive", "-Command", "Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' | Sort-Object RouteMetric | ForEach-Object { $_.NextHop }"},
			{"cmd", "/c", "route print -4"},
		}
		for _, args := range commands {
			out, err := exec.Command(args[0], args[1:]...).Output()
			if err != nil {
				continue
			}
			ips := extractIPv4(string(out))
			if len(ips) > 0 {
				return ips
			}
		}
	} else {
		if out, err := exec.Command("sh", "-c", "ip -4 route show default 2>/dev/null | awk '{print $3}'").Output(); err == nil {
			ips := extractIPv4(string(out))
			if len(ips) > 0 {
				return ips
			}
		}
	}
	return nil
}

func gatewayURLs() []string {
	out := make([]string, 0)
	for _, ip := range gatewayCandidates() {
		out = append(out, "http://"+net.JoinHostPort(ip, fmt.Sprint(defaultPort)))
	}
	return out
}

func extractIPv4(s string) []string {
	fields := strings.FieldsFunc(s, func(r rune) bool { return r == '\n' || r == '\r' || r == '\t' || r == ' ' })
	out := []string{}
	for _, f := range fields {
		f = strings.TrimSpace(strings.Trim(f, "*[](),"))
		ip := net.ParseIP(f)
		if ip == nil || ip.To4() == nil || ip.IsUnspecified() {
			continue
		}
		v := ip.To4().String()
		if v == "127.0.0.1" || strings.HasPrefix(v, "255.") {
			continue
		}
		out = append(out, v)
	}
	return uniqueStrings(out)
}

func uniqueStrings(xs []string) []string {
	seen := map[string]bool{}
	out := make([]string, 0, len(xs))
	for _, x := range xs {
		x = strings.TrimRight(strings.TrimSpace(x), "/")
		if x != "" && !seen[x] {
			seen[x] = true
			out = append(out, x)
		}
	}
	return out
}

func eventFolderName(p Photo) string {
	stamp := time.UnixMilli(p.SessionStartedAtMS).Local().Format("20060102_150405")
	label := slug(p.EventName)
	if label == "" {
		label = "Event"
	}
	id := p.SessionID
	if len(id) > 6 {
		id = id[:6]
	}
	return stamp + "_" + label + "_" + id
}

func slug(s string) string {
	var b strings.Builder
	lastUnderscore := false
	for _, r := range strings.TrimSpace(s) {
		ok := (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '-' || r == '_'
		if ok {
			b.WriteRune(r)
			lastUnderscore = false
		} else if !lastUnderscore {
			b.WriteByte('_')
			lastUnderscore = true
		}
	}
	return strings.Trim(b.String(), "_-")
}

func safeImageName(p Photo) string {
	ext := strings.ToLower(filepath.Ext(p.FileName))
	allowed := map[string]bool{".jpg": true, ".jpeg": true, ".png": true, ".webp": true, ".heic": true, ".heif": true, ".avif": true, ".gif": true, ".bmp": true, ".dng": true}
	if !allowed[ext] {
		ext = ".bin"
	}
	stamp := time.UnixMilli(p.CaptureTimeMS).Local().Format("150405")
	if p.CaptureTimeMS <= 0 {
		stamp = "unknown_time"
	}
	return fmt.Sprintf("%04d_%s%s", p.Sequence, stamp, ext)
}

func hashFile(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func humanBytes(n int64) string {
	if n < 1024 {
		return fmt.Sprintf("%d B", n)
	}
	v := float64(n) / 1024
	if v < 1024 {
		return fmt.Sprintf("%.1f KB", v)
	}
	return fmt.Sprintf("%.1f MB", v/1024)
}

func fatal(message string, err error) {
	fmt.Fprintf(os.Stderr, "\nERROR: %s: %v\n", message, err)
	fmt.Println("Press Enter to close this window…")
	_, _ = bufio.NewReader(os.Stdin).ReadString('\n')
	os.Exit(1)
}
