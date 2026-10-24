package xraybridge

import (
	"fmt"
	"os"
	"strings"
	"sync"
	"syscall"

	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
	"github.com/xtls/xray-core/transport/internet"
)

// Protector is implemented by Android and excludes Xray's own sockets from the TUN.
// Without this callback the proxy's upstream sockets can loop back into their own tunnel.
type Protector interface {
	Protect(fd int) bool
}

var (
	protectorMu      sync.Mutex
	currentProtector Protector
	registerOnce     sync.Once
	registerError    error

	coreMu     sync.Mutex
	activeCore *core.Instance
)

// RegisterProtector installs one dial hook per process. The callback is replaceable on
// later VPN sessions because Android can recreate the service while keeping the process alive.
func RegisterProtector(p Protector) string {
	protectorMu.Lock()
	currentProtector = p
	protectorMu.Unlock()

	registerOnce.Do(func() {
		registerError = internet.RegisterDialerController(func(network, address string, raw syscall.RawConn) error {
			protectorMu.Lock()
			callback := currentProtector
			protectorMu.Unlock()
			if callback == nil {
				return nil
			}
			var protectErr error
			if err := raw.Control(func(fd uintptr) {
				if !callback.Protect(int(fd)) {
					protectErr = fmt.Errorf("VpnService.protect(%d) returned false", fd)
				}
			}); err != nil {
				return err
			}
			return protectErr
		})
	})
	if registerError != nil {
		return fmt.Sprintf("Unable to register socket protection: %v", registerError)
	}
	return ""
}

// StartXray loads an Xray JSON config whose inbound is the Android TUN, and starts the core.
// An empty result means success; a non-empty result is shown to the user for diagnostics.
func StartXray(configJSON string, tunFD int, assetDir string) string {
	if strings.TrimSpace(configJSON) == "" {
		return "Xray config is empty"
	}
	if tunFD <= 0 {
		return fmt.Sprintf("Invalid Android TUN file descriptor: %d", tunFD)
	}

	coreMu.Lock()
	defer coreMu.Unlock()

	if activeCore != nil {
		if err := activeCore.Close(); err != nil {
			return fmt.Sprintf("Unable to stop previous Xray instance: %v", err)
		}
		activeCore = nil
	}

	fd := fmt.Sprintf("%d", tunFD)
	if err := os.Setenv("xray.tun.fd", fd); err != nil {
		return fmt.Sprintf("Unable to set Xray TUN descriptor: %v", err)
	}
	_ = os.Setenv("XRAY_TUN_FD", fd)
	if strings.TrimSpace(assetDir) != "" {
		_ = os.Setenv("xray.location.asset", assetDir)
		_ = os.Setenv("XRAY_LOCATION_ASSET", assetDir)
		_ = os.Setenv("xray.location.cert", assetDir)
		_ = os.Setenv("XRAY_LOCATION_CERT", assetDir)
	}

	cfg, err := core.LoadConfig("json", strings.NewReader(configJSON))
	if err != nil {
		return fmt.Sprintf("Xray config parse failed: %v", err)
	}
	instance, err := core.New(cfg)
	if err != nil {
		return fmt.Sprintf("Xray initialization failed: %v", err)
	}
	if err := instance.Start(); err != nil {
		_ = instance.Close()
		return fmt.Sprintf("Xray startup failed: %v", err)
	}
	activeCore = instance
	return ""
}

// StopXray stops and releases the one active core instance.
func StopXray() string {
	coreMu.Lock()
	defer coreMu.Unlock()
	if activeCore == nil {
		return ""
	}
	err := activeCore.Close()
	activeCore = nil
	if err != nil {
		return fmt.Sprintf("Xray shutdown failed: %v", err)
	}
	return ""
}

// XrayVersion helps support/debug builds without exposing private config contents.
func XrayVersion() string { return core.Version() }
