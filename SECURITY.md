# Security

## Revoking a phone

Each device holds an SSH key generated on that device and never leaves it. To
revoke one, delete its line from the server's authorized_keys:

```bash
grep -n 'opencode-android' ~/.ssh/authorized_keys
# then delete the matching line
```

Keys installed by this app are prefixed with `restrict`, so they can run commands
but cannot write to `authorized_keys`, forward ports, or escalate. That is a
meaningful limit but not a substitute for revocation.

## What the pairing code is worth

A pairing code is single-use and expires in five minutes. It is exchanged for a
session token, and **that token is the credential** — the server password is never
sent to the phone and is never stored there. Anyone holding an unredeemed code can
pair as you, so treat a printed code the way you would treat a password until it
has been used.

The session token does not expire on its own. If you believe it leaked, rotate the
server password:

```bash
sudo rm /etc/opencode/server.env
sudo sh install.sh
```

That invalidates every outstanding token and forces each phone to pair again.

## Tailscale and the public internet

The installer binds to your Tailscale address when there is one. Tailscale
addresses are not routable from the internet, so that default is safe. If it falls
back to a LAN address it is only reachable on your local network.

It will never bind `0.0.0.0` without telling you. If you ever run this on a host
with a public IP and ask for that, put a firewall in front of it — this server has
no authentication of its own beyond the session token.

## Reporting a vulnerability

Open a private security advisory on the repository rather than a public issue.