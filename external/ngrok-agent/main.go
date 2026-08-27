// DAMN embedded ngrok agent.
//
// Purpose-built forwarder using the official ngrok-go library, compiled
// with CGO for android/arm64 so DNS resolves through bionic (no
// /etc/resolv.conf needed). Protocol spoken to the DAMN app over stdout:
//
//	TUNNEL_URL <public-url>     once the tunnel is live
//	AGENT_ERROR <message>       on fatal errors
//	other lines                 progress/log chatter
package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"syscall"

	"golang.ngrok.com/ngrok"
	"golang.ngrok.com/ngrok/config"
)

func main() {
	token := flag.String("token", "", "ngrok authtoken")
	domain := flag.String("domain", "", "reserved domain (optional)")
	port := flag.Int("port", 8080, "local TCP port to forward to")
	flag.Parse()

	if *token == "" {
		fmt.Println("AGENT_ERROR missing --token")
		os.Exit(2)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigs
		cancel()
	}()

	log.SetFlags(log.Ltime | log.Lmicroseconds)

	var (
		tun ngrok.Tunnel
		err error
	)
	if *domain != "" {
		log.Printf("connecting (domain %s)...", *domain)
		tun, err = ngrok.Listen(ctx,
			config.HTTPEndpoint(config.WithDomain(*domain)),
			ngrok.WithAuthtoken(*token),
		)
	} else {
		log.Printf("connecting...")
		tun, err = ngrok.Listen(ctx,
			config.HTTPEndpoint(),
			ngrok.WithAuthtoken(*token),
		)
	}
	if err != nil {
		fmt.Printf("AGENT_ERROR %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("TUNNEL_URL %s\n", tun.URL())
	log.Printf("forwarding to 127.0.0.1:%d", *port)

	for {
		conn, err := tun.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				log.Println("shutting down")
				return
			default:
			}
			fmt.Printf("AGENT_ERROR accept: %v\n", err)
			os.Exit(1)
		}
		go forward(conn, *port)
	}
}

func forward(upstream net.Conn, port int) {
	defer upstream.Close()
	local, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", fmt.Sprint(port)))
	if err != nil {
		log.Printf("local dial failed: %v", err)
		return
	}
	defer local.Close()

	done := make(chan struct{}, 2)
	go func() { io.Copy(local, upstream); done <- struct{}{} }()
	go func() { io.Copy(upstream, local); done <- struct{}{} }()
	<-done
}
