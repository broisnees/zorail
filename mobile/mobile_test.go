package mobile

import (
	"net/smtp"
	"path/filepath"
	"testing"
	"time"
)

// recorder is a Listener that records callbacks, standing in for the Kotlin
// foreground service.
type recorder struct {
	mail    chan [2]string
	errs    chan string
	stopped chan struct{}
}

func newRecorder() *recorder {
	return &recorder{
		mail:    make(chan [2]string, 8),
		errs:    make(chan string, 8),
		stopped: make(chan struct{}, 8),
	}
}

func (r *recorder) OnMail(inbox, msgID string) { r.mail <- [2]string{inbox, msgID} }
func (r *recorder) OnError(message string)     { r.errs <- message }
func (r *recorder) OnStopped()                 { r.stopped <- struct{}{} }

// start brings up an embedded server on OS-chosen loopback ports.
func start(t *testing.T) (*Server, *recorder) {
	t.Helper()

	opts := NewOptions()
	opts.DBPath = filepath.Join(t.TempDir(), "zorail.db")
	opts.Domain = "phone.test"
	opts.SMTPPort = 0 // let the OS pick, so parallel tests never collide
	opts.HTTPPort = 0

	srv, err := NewServer(opts)
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	t.Cleanup(func() { _ = srv.Close() })

	rec := newRecorder()
	if err := srv.Start(rec); err != nil {
		t.Fatalf("Start: %v", err)
	}
	return srv, rec
}

// TestEmbeddedServerReceivesMail is the whole premise of the mobile build: a
// real SMTP transaction against the in-process server lands in storage and wakes
// the listener the UI uses to post a notification.
func TestEmbeddedServerReceivesMail(t *testing.T) {
	srv, rec := start(t)

	if !srv.Running() {
		t.Fatal("server should be running after Start")
	}

	body := "From: sender@example.com\r\n" +
		"To: hello@phone.test\r\n" +
		"Subject: from the handset\r\n" +
		"\r\n" +
		"it works\r\n"
	if err := smtp.SendMail(srv.SMTPAddr(), nil, "sender@example.com",
		[]string{"hello@phone.test"}, []byte(body)); err != nil {
		t.Fatalf("SendMail to %s: %v", srv.SMTPAddr(), err)
	}

	select {
	case got := <-rec.mail:
		if got[0] != "hello@phone.test" {
			t.Errorf("inbox = %q, want hello@phone.test", got[0])
		}
		if got[1] == "" {
			t.Error("message ID should not be empty")
		}
	case err := <-rec.errs:
		t.Fatalf("OnError: %s", err)
	case <-time.After(10 * time.Second):
		t.Fatal("no OnMail callback within 10s")
	}
}

// TestStartStopIsIdempotent covers the Android lifecycle, where a service can be
// stopped and restarted, and onDestroy may fire more than once.
func TestStartStopIsIdempotent(t *testing.T) {
	srv, rec := start(t)

	if err := srv.Start(rec); err == nil {
		t.Error("second Start should report already running")
	}
	if err := srv.Stop(); err != nil {
		t.Fatalf("Stop: %v", err)
	}
	if srv.Running() {
		t.Error("Running should be false after Stop")
	}
	select {
	case <-rec.stopped:
	case <-time.After(5 * time.Second):
		t.Fatal("no OnStopped callback")
	}

	// A clean Stop must not surface as a listener failure.
	select {
	case err := <-rec.errs:
		t.Fatalf("Stop reported an error: %s", err)
	case <-time.After(200 * time.Millisecond):
	}

	if err := srv.Stop(); err != nil {
		t.Errorf("second Stop should be a no-op, got %v", err)
	}

	// Restart on the same database — the phone re-enabling the service.
	if err := srv.Start(rec); err != nil {
		t.Fatalf("restart: %v", err)
	}
	if !srv.Running() {
		t.Error("server should be running after restart")
	}
}

// TestNewServerRequiresDBPath guards the one option Android cannot default:
// scoped storage means the app must supply its own files dir.
func TestNewServerRequiresDBPath(t *testing.T) {
	if _, err := NewServer(NewOptions()); err == nil {
		t.Error("NewServer should reject empty DBPath")
	}
	if _, err := NewServer(nil); err == nil {
		t.Error("NewServer should reject nil options")
	}
}
