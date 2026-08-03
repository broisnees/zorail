package cfsetup

import (
	"context"
	"strings"
	"testing"
)

// Connect refuses incomplete input before touching the network, so the UI gets a
// useful message instead of a Cloudflare error about something unrelated.
func TestConnectRejectsIncompleteOptions(t *testing.T) {
	full := ConnectOptions{
		Token:       "tok",
		Domain:      "example.com",
		Hostname:    "ingest.example.com",
		IngestToken: "zorail-token",
	}

	tests := []struct {
		name   string
		mutate func(*ConnectOptions)
		want   string
	}{
		{"no token", func(o *ConnectOptions) { o.Token = "" }, "API token is required"},
		{"no domain", func(o *ConnectOptions) { o.Domain = "" }, "domain is required"},
		{"no hostname", func(o *ConnectOptions) { o.Hostname = "" }, "hostname"},
		// A server with auth disabled cannot be connected: the Worker would have
		// no credential to POST with.
		{"no ingest token", func(o *ConnectOptions) { o.IngestToken = "" }, "ZORAIL_API_TOKEN"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			o := full
			tc.mutate(&o)
			_, err := Connect(context.Background(), o)
			if err == nil {
				t.Fatal("expected an error, got nil")
			}
			if !strings.Contains(err.Error(), tc.want) {
				t.Errorf("error = %q, want it to mention %q", err, tc.want)
			}
		})
	}
}

// Pasting a full URL where a hostname is asked for is the obvious mistake, so
// Connect normalises rather than failing. Verified through the exported surface
// by way of the ingest URL it builds.
func TestConnectNormalizesHostname(t *testing.T) {
	for _, in := range []string{
		"ingest.example.com",
		"https://ingest.example.com",
		"http://ingest.example.com",
		"https://ingest.example.com/",
		"https://ingest.example.com/api/ingest",
		"  ingest.example.com  ",
	} {
		t.Run(in, func(t *testing.T) {
			// A bogus token fails at VerifyToken, which is *after* normalisation,
			// so reaching a token error proves the hostname check passed.
			_, err := Connect(context.Background(), ConnectOptions{
				Token:       "definitely-not-a-real-token",
				Domain:      "example.com",
				Hostname:    in,
				IngestToken: "zorail-token",
			})
			if err == nil {
				t.Fatal("expected the invalid Cloudflare token to fail")
			}
			if strings.Contains(err.Error(), "hostname") {
				t.Errorf("hostname %q was rejected: %v", in, err)
			}
		})
	}
}

// Zones needs a token before it can list anything.
func TestZonesRequiresToken(t *testing.T) {
	if _, err := Zones(context.Background(), "   "); err == nil {
		t.Error("expected an error for a blank token")
	}
}
