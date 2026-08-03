package api

import (
	"context"
	"net/http"
	"time"

	"github.com/nees/zorail/internal/cfsetup"
)

// Cloudflare provisioning over HTTP, so a domain can be connected from the web
// UI — and therefore from the Android app — rather than only from a terminal
// running `zorail setup`.
//
// The Cloudflare token arrives in the request body, is used for that request,
// and is never written to disk or the database. It is a broad account
// credential, and the whole point of the mobile build is that the server may be
// a phone, which is the last thing that should hold one at rest.

// cfZonesReq is the domain-picker request: a token, nothing else.
type cfZonesReq struct {
	Token string `json:"token"`
}

type cfZone struct {
	Name string `json:"name"`
	ID   string `json:"id"`
}

// handleCFZones verifies a Cloudflare token and lists the zones it can reach.
func (s *Server) handleCFZones(w http.ResponseWriter, r *http.Request) {
	var req cfZonesReq
	if !decodeJSON(w, r, &req) {
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()

	zones, err := cfsetup.Zones(ctx, req.Token)
	if err != nil {
		// A bad or under-permissioned token is user error, not a server fault,
		// and the Cloudflare message is the useful part.
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	out := make([]cfZone, 0, len(zones))
	for _, z := range zones {
		out = append(out, cfZone{Name: z.Name, ID: z.ID})
	}
	writeJSON(w, http.StatusOK, out)
}

// cfConnectReq provisions one domain.
type cfConnectReq struct {
	Token    string `json:"token"`
	Domain   string `json:"domain"`
	Hostname string `json:"hostname"` // public host that reaches this server
}

// handleCFConnect deploys the Email Worker, ensures Email Routing plus its
// MX/SPF records, and points the catch-all at the Worker.
func (s *Server) handleCFConnect(w http.ResponseWriter, r *http.Request) {
	var req cfConnectReq
	if !decodeJSON(w, r, &req) {
		return
	}

	// Cloudflare provisioning is a chain of API calls against several services;
	// give it room without letting a hung request pin a connection forever.
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Minute)
	defer cancel()

	res, err := cfsetup.Connect(ctx, cfsetup.ConnectOptions{
		Token:    req.Token,
		Domain:   req.Domain,
		Hostname: req.Hostname,
		// The Worker authenticates back to us with our own API token, so a
		// server with auth disabled cannot be connected — Connect rejects it.
		IngestToken: s.cfg.APIToken,
	})
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	s.log.Info("cloudflare connected",
		"domain", res.Domain, "worker", res.Worker, "ingest", res.Ingest,
		"warnings", len(res.Warnings))
	writeJSON(w, http.StatusOK, res)
}
