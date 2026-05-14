# PortalKids Hub

The hub is the LAN source of truth for PortalKids. It serves the admin page and API, and stores state in SQLite.

## Run Manually

```bash
JAVA_HOME=/Users/davidedicillo/Projects/PortalKids/.toolchains/jdk-17.0.19+10/Contents/Home \
PORTALKIDS_PUBLIC_URL=http://192.168.4.29:8080 \
./hub/build/install/hub/bin/hub
```

The default database is `~/.portalkids/portal-kids.db`.

## Install As A Mac User Service

```bash
./hub/scripts/install-launch-agent.sh
```

Optional environment overrides:

```bash
PORTALKIDS_PORT=8080 \
PORTALKIDS_PUBLIC_URL=http://192.168.4.29:8080 \
PORTALKIDS_DB="$HOME/.portalkids/portal-kids.db" \
./hub/scripts/install-launch-agent.sh
```

Logs are written to `~/Library/Logs/PortalKids/`.
