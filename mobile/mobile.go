// Package mobile is the gomobile-bound facade over the Zorail server, letting
// an Android (or iOS) app run the real thing — inbound SMTP, the JSON API, the
// web UI, SQLite storage — in-process instead of talking to a remote host.
//
// It exists because `gomobile bind` understands only a narrow slice of Go: the
// numeric types, string, bool, []byte, error, and exported structs/interfaces
// from the bound package. No maps, no slices of anything but bytes, no
// context.Context, no time.Time, no variadics. So this package hands Kotlin a
// deliberately boring surface (NewOptions → NewServer → Start/Stop) and keeps
// every Go-shaped type on the Go side.
//
// Build:
//
//	gomobile bind -target=android/arm64,android/arm -javapkg ai.zorail -o zorail.aar ./mobile
package mobile

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/nees/zorail/internal/api"
	"github.com/nees/zorail/internal/config"
	"github.com/nees/zorail/internal/forward"
	"github.com/nees/zorail/internal/ingest"
	"github.com/nees/zorail/internal/notify"
	zsmtp "github.com/nees/zorail/internal/smtp"
	"github.com/nees/zorail/internal/storage/sqlite"
)

// Options configures an embedded server. Fields are plain scalars because
// gomobile cannot bind []string or map — AllowedDomains is therefore a
// comma-separated list rather than a slice.
//
// Construct with NewOptions so the defaults apply; Java/Kotlin cannot use Go
// struct literals, and a zero Options would bind SMTP to port 0.
type Options struct {
	// DBPath is where SQLite lives. On Android pass context.filesDir — scoped
	// storage means anywhere else is either unwritable or not yours.
	DBPath string

	// Domain is the SMTP greeting domain and the domain new addresses are
	// minted under.
	Domain string

	// SMTPPort is the inbound listener port. Android forbids binding below
	// 1024 without root, so this defaults to 2525 rather than 25.
	SMTPPort int

	// HTTPPort serves the JSON API and the bundled web UI.
	HTTPPort int

	// BindPublic exposes both listeners on 0.0.0.0 instead of 127.0.0.1 —
	// needed to receive mail from other machines on the LAN or over a mesh VPN,
	// and worth a confirmation prompt in the UI before you switch it on.
	BindPublic bool

	// APIToken, when set, is required as a bearer token on /api. Always set it
	// alongside BindPublic: an open API on a phone that roams onto untrusted
	// Wi-Fi is a data leak waiting to happen.
	APIToken string

	// AllowedDomains is a comma-separated list of recipient domains to accept
	// mail for. Empty accepts everything, which is fine on localhost and a bad
	// idea with BindPublic.
	AllowedDomains string

	// RetentionDays sweeps disposable inboxes older than this. Zero disables
	// sweeping — reasonable on a phone where storage is finite but so is the
	// volume of mail.
	RetentionDays int

	// LogLevel is "debug" | "info" | "warn" | "error". Go's stderr is
	// redirected to logcat by gomobile, so these land under the GoLog tag.
	LogLevel string
}

// NewOptions returns Options with phone-appropriate defaults: loopback-only,
// unprivileged ports, 14-day retention.
func NewOptions() *Options {
	return &Options{
		Domain:        "localhost",
		SMTPPort:      2525,
		HTTPPort:      8080,
		RetentionDays: 14,
		LogLevel:      "info",
	}
}

// Listener receives server events on a Go goroutine. Implementations are called
// from outside the Android main thread, so post to a Handler/coroutine before
// touching UI or posting a notification.
type Listener interface {
	// OnMail fires once per message per recipient inbox.
	OnMail(inbox string, msgID string)
	// OnError reports a fatal listener error; the server is stopped by then.
	OnError(message string)
	// OnStopped fires after a clean Stop, so the service can drop its
	// foreground notification.
	OnStopped()
}

// Server is a running Zorail instance. It is safe to call from any thread.
type Server struct {
	mu       sync.Mutex
	cfg      *config.Config
	log      *slog.Logger
	store    *sqlite.Store
	smtp     *zsmtp.Server
	http     *api.Server
	cancel   context.CancelFunc
	unwatch  func()
	listener Listener
	running  bool

	// Bound addresses, captured after Listen so a configured port of 0 reports
	// the port the OS actually picked — which is what the UI must display.
	smtpAddr string
	httpAddr string
}

// NewServer validates opts and opens the database, but binds nothing — call
// Start for that. Splitting the two lets the UI surface a config error before
// it shows a "running" state.
func NewServer(opts *Options) (*Server, error) {
	if opts == nil {
		return nil, errors.New("options required")
	}
	if opts.DBPath == "" {
		return nil, errors.New("DBPath required (use context.getFilesDir())")
	}

	// Round-trip through config.Load rather than building a Config here, so
	// defaults and validation stay single-sourced in internal/config.
	host := "127.0.0.1"
	if opts.BindPublic {
		host = "0.0.0.0"
	}
	env := map[string]string{
		"ZORAIL_ENV_FILE":        os.DevNull, // no .env on a phone; don't probe cwd
		"ZORAIL_DB_PATH":         opts.DBPath,
		"ZORAIL_DOMAIN":          opts.Domain,
		"ZORAIL_SMTP_ADDR":       net.JoinHostPort(host, strconv.Itoa(opts.SMTPPort)),
		"ZORAIL_HTTP_ADDR":       net.JoinHostPort(host, strconv.Itoa(opts.HTTPPort)),
		"ZORAIL_API_TOKEN":       opts.APIToken,
		"ZORAIL_ALLOWED_DOMAINS": opts.AllowedDomains,
		"ZORAIL_RETENTION_DAYS":  strconv.Itoa(opts.RetentionDays),
		"ZORAIL_LOG_LEVEL":       opts.LogLevel,
	}
	for k, v := range env {
		if err := os.Setenv(k, v); err != nil {
			return nil, fmt.Errorf("set %s: %w", k, err)
		}
	}

	cfg, err := config.Load()
	if err != nil {
		return nil, err
	}

	level := slog.LevelInfo
	if err := level.UnmarshalText([]byte(cfg.LogLevel)); err != nil {
		level = slog.LevelInfo
	}
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level}))

	store, err := sqlite.Open(cfg.DBPath)
	if err != nil {
		return nil, err
	}

	return &Server{cfg: cfg, log: log, store: store}, nil
}

// Start binds the listeners and returns immediately — gomobile calls block the
// calling Java thread, so a blocking Start would freeze the caller forever.
// Errors after this point arrive via Listener.OnError.
func (s *Server) Start(l Listener) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running {
		return errors.New("already running")
	}

	bg, cancel := context.WithCancel(context.Background())
	hub := notify.NewHub()
	ing := ingest.New(s.cfg, s.store, s.log, hub)

	// Outbound relay for forwarding. Nil unless a smarthost is configured; a
	// phone has no business talking port 25 to the world directly.
	relay := forward.NewSMTPRelay(s.cfg)
	var mailer api.Mailer
	if relay != nil {
		mailer = relay
	}
	go forward.NewWorker(s.cfg, s.store, relay, s.log).Run(bg)

	if s.cfg.RetentionDays > 0 {
		go s.sweep(bg)
	}

	httpSrv, err := api.New(s.cfg, s.store, s.log, &api.Deps{
		Ingest: ing,
		Hub:    hub,
		Mailer: mailer,
	})
	if err != nil {
		cancel()
		return err
	}
	smtpSrv := zsmtp.New(s.cfg, ing, s.log, nil)

	// Bind eagerly so a port conflict surfaces as a Start error the UI can show,
	// instead of an OnError callback moments after we reported success.
	smtpLn, err := net.Listen("tcp", s.cfg.SMTPAddr)
	if err != nil {
		cancel()
		return fmt.Errorf("bind smtp %s: %w", s.cfg.SMTPAddr, err)
	}
	httpLn, err := net.Listen("tcp", s.cfg.HTTPAddr)
	if err != nil {
		_ = smtpLn.Close()
		cancel()
		return fmt.Errorf("bind http %s: %w", s.cfg.HTTPAddr, err)
	}

	s.smtp, s.http, s.cancel, s.running = smtpSrv, httpSrv, cancel, true
	s.smtpAddr, s.httpAddr = smtpLn.Addr().String(), httpLn.Addr().String()

	if l != nil {
		events, unwatch := hub.SubscribeAll()
		s.unwatch = unwatch
		go func() {
			for {
				select {
				case <-bg.Done():
					return
				case ev := <-events:
					l.OnMail(ev.Inbox, ev.MsgID)
				}
			}
		}()
	}

	fail := func(err error) {
		// A closed listener during Stop is the expected path, not a failure.
		if err == nil || errors.Is(err, net.ErrClosed) || bg.Err() != nil {
			return
		}
		s.log.Error("listener failed", "err", err)
		_ = s.Stop()
		if l != nil {
			l.OnError(err.Error())
		}
	}
	// The closures matter: `go fail(srv.Serve(ln))` would evaluate Serve on this
	// goroutine and block Start forever.
	go func() { fail(smtpSrv.Serve(smtpLn)) }()
	go func() { fail(httpSrv.Serve(httpLn)) }()

	s.listener = l
	s.log.Info("zorail started", "smtp", s.cfg.SMTPAddr, "http", s.cfg.HTTPAddr, "db", s.cfg.DBPath)
	return nil
}

// Stop shuts the listeners down and stops the background workers. The database
// stays open so Start can be called again; use Close to release it.
func (s *Server) Stop() error {
	s.mu.Lock()
	if !s.running {
		s.mu.Unlock()
		return nil
	}
	smtpSrv, httpSrv, cancel, unwatch, l := s.smtp, s.http, s.cancel, s.unwatch, s.listener
	s.running, s.smtp, s.http, s.cancel, s.unwatch, s.listener = false, nil, nil, nil, nil, nil
	s.mu.Unlock()

	cancel()
	if unwatch != nil {
		unwatch()
	}

	ctx, done := context.WithTimeout(context.Background(), 5*time.Second)
	defer done()
	var err error
	if httpSrv != nil {
		err = httpSrv.Shutdown(ctx)
	}
	if smtpSrv != nil {
		if cerr := smtpSrv.Close(); err == nil {
			err = cerr
		}
	}
	if l != nil {
		l.OnStopped()
	}
	return err
}

// Close stops the server if needed and releases the database. The Server is
// unusable afterwards.
func (s *Server) Close() error {
	err := s.Stop()
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.store != nil {
		if cerr := s.store.Close(); err == nil {
			err = cerr
		}
		s.store = nil
	}
	return err
}

// Running reports whether the listeners are bound.
func (s *Server) Running() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.running
}

// SMTPAddr returns the SMTP address actually bound, falling back to the
// configured one before Start.
func (s *Server) SMTPAddr() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.smtpAddr != "" {
		return s.smtpAddr
	}
	return s.cfg.SMTPAddr
}

// HTTPAddr returns the HTTP address actually bound, falling back to the
// configured one before Start.
func (s *Server) HTTPAddr() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.httpAddr != "" {
		return s.httpAddr
	}
	return s.cfg.HTTPAddr
}

// LANAddress returns a host:port another device on the same network can reach
// the SMTP listener on, or "" when the phone has no usable non-loopback IPv4
// (mobile data, airplane mode) or the server is bound to loopback only. Purely
// for display — "point your test client here".
func (s *Server) LANAddress() string {
	_, port, err := net.SplitHostPort(s.SMTPAddr())
	if err != nil {
		return ""
	}
	ip := lanIPv4()
	if ip == "" {
		return ""
	}
	return net.JoinHostPort(ip, port)
}

// sweep deletes expired disposable mail hourly, mirroring the server's
// retention worker.
func (s *Server) sweep(ctx context.Context) {
	t := time.NewTicker(time.Hour)
	defer t.Stop()
	for {
		cutoff := time.Now().UTC().AddDate(0, 0, -s.cfg.RetentionDays)
		if n, err := s.store.ExpireMessages(ctx, cutoff); err != nil {
			s.log.Error("retention sweep", "err", err)
		} else if n > 0 {
			s.log.Info("retention sweep", "deleted", n)
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// lanIPv4 picks the first non-loopback IPv4 on an up interface.
func lanIPv4() string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return ""
	}
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			n, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			if ip4 := n.IP.To4(); ip4 != nil && !ip4.IsLoopback() {
				return ip4.String()
			}
		}
	}
	return ""
}
