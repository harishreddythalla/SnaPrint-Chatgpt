package main

import (
	"testing"
	"time"
)

func TestEventFolderNameDeterministicAndSafe(t *testing.T) {
	p := Photo{SessionID: "abcdef01-2222-3333-4444-555555555555", EventName: "Guests / VIP night!", SessionStartedAtMS: time.Date(2026, 10, 22, 14, 0, 0, 0, time.Local).UnixMilli()}
	got := eventFolderName(p)
	if got == "" || got != eventFolderName(p) {
		t.Fatalf("folder name not deterministic: %q", got)
	}
	for _, bad := range []string{"/", `\\`, ":", ".."} {
		if contains(got, bad) {
			t.Fatalf("unsafe folder component %q in %q", bad, got)
		}
	}
}

func TestSafeImageNamePreservesSupportedExtension(t *testing.T) {
	p := Photo{Sequence: 9, FileName: "IMG_20261022.HEIC", CaptureTimeMS: 1792677600000}
	if got := safeImageName(p); got == "" || got[len(got)-5:] != ".heic" {
		t.Fatalf("unexpected name %q", got)
	}
	p.FileName = "payload.exe"
	if got := safeImageName(p); got[len(got)-4:] != ".bin" {
		t.Fatalf("unrecognized extension should be sanitized, got %q", got)
	}
}

func TestNormalizeBaseURL(t *testing.T) {
	cases := map[string]string{"192.168.43.1": "http://192.168.43.1:8765", "http://192.168.43.1:8765/": "http://192.168.43.1:8765", "": ""}
	for in, want := range cases {
		if got := normalizeBaseURL(in); got != want {
			t.Errorf("normalizeBaseURL(%q)=%q want %q", in, got, want)
		}
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
