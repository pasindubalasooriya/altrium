# Local development setup

What you need on a machine before the backend will run.

| Tool | Version here | Notes |
|---|---|---|
| JDK | 21 (Corretto 21.0.11) | Java 21 is fixed by the stack |
| Maven | 3.9.16, via `mvnw` | Use the wrapper - do not install Maven separately |
| MySQL Server | 8.4 | See below |

## 1. MySQL Server

MySQL Workbench is **only a GUI client** - installing it does not give you a server. Check whether you actually have one:

```powershell
Get-Service -Name '*mysql*'          # a service, or nothing
Test-NetConnection 127.0.0.1 -Port 3306
```

### Install

```powershell
winget install --id Oracle.MySQL --exact --silent --accept-package-agreements
```

This installs the binaries to `C:\Program Files\MySQL\MySQL Server 8.4` but does **not** create a data directory or a service.

### Initialise the data directory

```powershell
$base = 'C:\Program Files\MySQL\MySQL Server 8.4'
$data = "$env:LOCALAPPDATA\Altrium\mysql-data"
& "$base\bin\mysqld.exe" --initialize-insecure --basedir="$base" --datadir="$data" --console
```

`--initialize-insecure` creates `root@localhost` with **no password**. The next step sets one, so do not stop in between.

### Configuration

Create `%LOCALAPPDATA%\Altrium\my.ini`, adjusting the two paths for your username:

```ini
[mysqld]
basedir = C:/Program Files/MySQL/MySQL Server 8.4
datadir = C:/Users/<you>/AppData/Local/Altrium/mysql-data
port    = 3306
bind-address = 127.0.0.1
character-set-server = utf8mb4
collation-server     = utf8mb4_0900_ai_ci
default-time-zone    = '+00:00'
log-error = C:/Users/<you>/AppData/Local/Altrium/mysql-error.log

[client]
port = 3306
default-character-set = utf8mb4
```

`bind-address = 127.0.0.1` keeps the development database off the network. Leave it that way.

### Start it

```powershell
.\scripts\start-mysql.ps1
```

> **The server is not a Windows service.** Installing one needs administrator rights the dev account does not have, so MySQL runs as a plain user process and **does not survive a reboot**. Run the start script again after restarting. If someone on the team has admin rights and wants a service instead, `mysqld --install` from an elevated prompt is the upgrade path - nothing else changes.

### Create the schemas and application user

Connect as root (no password on a fresh initialise) and run:

```sql
CREATE DATABASE IF NOT EXISTS altrium
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS altrium_test
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'altrium'@'localhost' IDENTIFIED BY '<pick-a-dev-password>';
GRANT ALL PRIVILEGES ON altrium.*      TO 'altrium'@'localhost';
GRANT ALL PRIVILEGES ON altrium_test.* TO 'altrium'@'localhost';

ALTER USER 'root'@'localhost' IDENTIFIED BY '<pick-a-root-password>';
FLUSH PRIVILEGES;
```

Two schemas, deliberately: `altrium` for development and `altrium_test` for the test suite. Tests run against **real MySQL** rather than H2 so that Flyway migrations and the DB-level plan-exclusivity constraint (P-5.7) are genuinely exercised - an in-memory substitute would let both silently pass.

## 2. Environment variables

The backend reads all credentials from the environment, so none are ever committed. Copy `backend/.env.example` to `backend/.env` and fill it in, or set them in your shell:

```powershell
$env:ALTRIUM_DB_URL      = 'jdbc:mysql://localhost:3306/altrium?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
$env:ALTRIUM_DB_USER     = 'altrium'
$env:ALTRIUM_DB_PASSWORD = '<your-dev-password>'
$env:ASGARDEO_ISSUER_URI = 'https://api.asgardeo.io/t/<tenant>/oauth2/token'
```

`.env` is gitignored. Never commit a real password or an Asgardeo client secret.

## 3. Run it

```powershell
cd backend
.\mvnw.cmd flyway:info        # what has been applied
.\mvnw.cmd flyway:migrate     # apply pending migrations
.\mvnw.cmd test               # unit + denial tests
.\mvnw.cmd spring-boot:run    # start the API on :8080
```

## 4. Run the frontend

Node 20 or newer. In a second terminal, with the backend already running:

```powershell
cd frontend
npm install
npm run dev        # http://localhost:5173
npm test           # Vitest
npm run build      # typecheck and production build
```

**The port is not negotiable.** `http://localhost:5173` is registered as the redirect URL in the Asgardeo console and is the origin the backend's CORS configuration allows, so all three have to agree. Vite is set to `strictPort`, which turns a clash into a startup failure rather than a mystery in the browser.

No `.env` is needed. The tenant defaults live in `src/config.ts`, exactly as the backend commits its Asgardeo issuer URI - the client ID is a public identifier for a PKCE single-page app, not a credential. Copy `.env.example` to `.env` only to point somewhere else.

There is deliberately **no Vite dev proxy** for `/api`. The browser makes real cross-origin calls in development, which is what it will do once deployed, so a CORS misconfiguration shows up now rather than at the first deployment.

| Symptom | Cause |
|---|---|
| Every screen says the API is unreachable | Backend not running, or `ALTRIUM_DB_PASSWORD` missing so it failed to start |
| "Your account is not set up in Altrium" | Signed in as an Asgardeo user with no `app_user` row, or a deactivated one. Only five seeded people can log in - see [seed-data.md](seed-data.md) |
| Login redirects back and immediately signs you out again | The redirect URL in the Asgardeo console does not match `http://localhost:5173` exactly |
| Sign-out appears not to work | Only if the code stops calling `signOut()`. Clearing tokens locally leaves the Asgardeo session alive, so the next sign-in reuses it silently |

## Troubleshooting

| Symptom | Cause |
|---|---|
| `Communications link failure` | MySQL isn't running - `.\scripts\start-mysql.ps1` |
| `Access denied for user 'altrium'` | `ALTRIUM_DB_PASSWORD` not set, or set in a different shell than the one running Maven |
| `Schema-validation: missing table` | Migrations not applied to that schema - run `flyway:migrate` against it |
| MySQL won't start after a reboot | Expected; it isn't a service. Run the start script |
MySQL password AltriumDev!2026