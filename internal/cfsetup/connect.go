package cfsetup

import (
	"context"
	"fmt"
	"strings"
)

// This file is the non-interactive twin of Run: same Cloudflare provisioning,
// but driven by arguments instead of terminal prompts and returning structured
// results instead of printing. That is what lets the web UI (and therefore the
// Android app) connect a domain from a phone, where there is no stdin to prompt
// on and no cloudflared to shell out to.
//
// The tunnel steps Run performs are deliberately absent. A tunnel needs a
// long-lived local daemon, which a phone cannot provide, so the caller supplies
// a Hostname that already reaches this server by some other means.

// ConnectOptions configures a Connect run. Every field is required.
type ConnectOptions struct {
	// Token is a user-owned Cloudflare API token. It is used for this call only
	// and never persisted — see the note on Connect.
	Token string

	// Domain is the mail domain, which must be a zone on the token's account.
	Domain string

	// Hostname is the public host that already routes to this Zorail's
	// /api/ingest — a tunnel, a Funnel, or a reverse proxy. The Email Worker
	// will POST every inbound message to https://<Hostname>/api/ingest.
	Hostname string

	// IngestToken authenticates the Worker's POSTs; it is the server's own API
	// token, stored as a Cloudflare secret binding rather than in the script.
	IngestToken string
}

// Step records one provisioning action that succeeded, so the UI can show what
// actually happened rather than a bare "done".
type Step struct {
	Name   string `json:"name"`
	Detail string `json:"detail,omitempty"`
}

// ConnectResult reports everything a run did. Warnings hold non-fatal problems
// the user may still need to act on — most often Email Routing needing a manual
// toggle in the dashboard, since Cloudflare's settings endpoints reject scoped
// tokens regardless of their permissions.
type ConnectResult struct {
	Domain   string   `json:"domain"`
	Zone     string   `json:"zone_id"`
	Worker   string   `json:"worker"`
	Ingest   string   `json:"ingest_url"`
	Steps    []Step   `json:"steps"`
	Warnings []string `json:"warnings,omitempty"`
}

// Zones verifies a token and lists the zones it can reach, so the UI can offer
// a domain picker instead of asking the user to type a domain exactly right.
func Zones(ctx context.Context, token string) ([]Zone, error) {
	if strings.TrimSpace(token) == "" {
		return nil, fmt.Errorf("a Cloudflare API token is required")
	}
	cf := NewCF(token)
	if err := cf.VerifyToken(ctx); err != nil {
		return nil, err
	}
	return cf.ListZones(ctx)
}

// Connect points a domain's inbound mail at this server: it deploys an Email
// Worker that POSTs to o.Hostname, ensures Email Routing and its MX/SPF records
// exist, and sets the catch-all rule to that Worker.
//
// The Cloudflare token is used for the duration of this call and deliberately
// not stored. It grants broad account access, and a self-hosted mailserver on a
// phone is the last place that should be holding it at rest; the caller re-sends
// it if another run is needed.
//
// Safe to run repeatedly — every underlying operation is an upsert.
func Connect(ctx context.Context, o ConnectOptions) (*ConnectResult, error) {
	o.Domain = strings.ToLower(strings.TrimSpace(o.Domain))
	o.Hostname = strings.TrimSpace(strings.TrimSuffix(o.Hostname, "/"))

	switch {
	case strings.TrimSpace(o.Token) == "":
		return nil, fmt.Errorf("a Cloudflare API token is required")
	case o.Domain == "":
		return nil, fmt.Errorf("a domain is required")
	case o.Hostname == "":
		return nil, fmt.Errorf("a public hostname reaching this server is required")
	case o.IngestToken == "":
		return nil, fmt.Errorf("this server has no API token set, so the Email Worker could not authenticate; set ZORAIL_API_TOKEN")
	}
	// A pasted URL is the obvious mistake here; take the host and move on.
	o.Hostname = strings.TrimPrefix(strings.TrimPrefix(o.Hostname, "https://"), "http://")
	if i := strings.IndexByte(o.Hostname, '/'); i >= 0 {
		o.Hostname = o.Hostname[:i]
	}

	cf := NewCF(o.Token)
	if err := cf.VerifyToken(ctx); err != nil {
		return nil, err
	}

	zone, err := cf.Zone(ctx, o.Domain)
	if err != nil {
		return nil, err
	}

	res := &ConnectResult{
		Domain: o.Domain,
		Zone:   zone.ID,
		Worker: "zorail-ingest-" + slug(o.Domain),
		Ingest: "https://" + o.Hostname + "/api/ingest",
	}
	res.Steps = append(res.Steps, Step{Name: "Verified token", Detail: "zone " + zone.Name})

	if err := cf.DeployEmailWorker(ctx, zone.Account.ID, res.Worker, workerJS, []workerVar{
		{Name: "INGEST_URL", Value: res.Ingest},
		{Name: "INGEST_TOKEN", Value: o.IngestToken, Secret: true},
	}); err != nil {
		return nil, fmt.Errorf("deploy email worker: %w", err)
	}
	res.Steps = append(res.Steps, Step{Name: "Deployed Email Worker", Detail: res.Worker + " → " + res.Ingest})

	// Best-effort, exactly as in Run: Cloudflare's Email Routing settings
	// endpoints reject scoped tokens with error 10000 whatever permissions they
	// carry, so a failure here must not undo work already done.
	enabled := false
	if er, err := cf.GetEmailRouting(ctx, zone.ID); err == nil {
		enabled = er.Enabled
	}
	if !enabled {
		if err := cf.EnableEmailRouting(ctx, zone.ID); err != nil {
			res.Warnings = append(res.Warnings, fmt.Sprintf(
				"Could not enable Email Routing via the API (%v). Enable it once by hand: Cloudflare dashboard → zone %s → Email → Email Routing.",
				err, zone.Name))
		} else {
			res.Steps = append(res.Steps, Step{Name: "Enabled Email Routing"})
		}
	} else {
		res.Steps = append(res.Steps, Step{Name: "Email Routing already enabled"})
	}

	// Written directly rather than read back from the token-rejecting
	// GET /email/routing/dns; they are identical for every zone and idempotent.
	recs := StandardEmailRoutingDNS(o.Domain)
	for _, r := range recs {
		if err := cf.UpsertDNS(ctx, zone.ID, r); err != nil {
			return nil, fmt.Errorf("add routing record %s %s: %w", r.Type, r.Name, err)
		}
	}
	res.Steps = append(res.Steps, Step{
		Name:   "MX + SPF records in place",
		Detail: fmt.Sprintf("%d records", len(recs)),
	})

	if err := cf.SetCatchAllToWorker(ctx, zone.ID, res.Worker); err != nil {
		return nil, fmt.Errorf("set catch-all rule: %w", err)
	}
	res.Steps = append(res.Steps, Step{
		Name:   "Catch-all active",
		Detail: "*@" + o.Domain + " → " + res.Worker,
	})

	return res, nil
}
