# Seed data - the sample organisation

A repeatable 31-person organisation across 4 departments, for manual testing.

```powershell
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--altrium.seed.enabled=true"
```

Off by default, and idempotent - it checks for a known subject and skips if the organisation is already there, so restarting does not produce two of everybody.

Built through `OrgService`, not raw SQL, so the seed exercises the same loop rejection (P-1.4) and role normalisation (P-0.1) the API does. If it runs clean, those rules work.

## Who can actually log in

Seven people have real Asgardeo accounts. Everyone else carries a synthetic subject and exists only to give the hierarchy shape - nothing below the login screen can tell the difference. Passwords are in `users.md`, outside the repo.

Identity is matched on the Asgardeo **subject**, never on the email address, so adding somebody takes two steps and not one. An account created in the console alone authenticates perfectly and then meets "your account is not set up in Altrium" - the token is valid and names nobody this system knows. The subject has to be recorded against their row as well, in `SeedData` for a fresh database and by hand for one that has already been seeded.

| Person | Email | Role | Sits |
|---|---|---|---|
| Richard Hale | `richard@altrium.test` | LEADERSHIP | above all departments |
| Jane Okafor | `jane@altrium.test` | MANAGER | Engineering, reports to Elena |
| John Alvarez | `john@altrium.test` | EMPLOYEE | Engineering, reports to Jane |
| Aisha Khan | `aisha@altrium.test` | EMPLOYEE | Engineering, reports to Jane |
| Diego Santos | `diego@altrium.test` | EMPLOYEE | Engineering, reports to Jane |
| Kevin Doyle | `kevin@altrium.test` | HR + MANAGER (**HR Head**) | People Operations, reports to Richard |
| Devin Marsh | `devin@altrium.test` | SUPER_ADMIN + EMPLOYEE | No department, no manager - a dedicated platform account (P-9.5) |

## Shape

```
Richard Hale (LEADERSHIP)   Priya Raman (LEADERSHIP)   Marcus Webb (LEADERSHIP)
      │                            │                          │
      ├─ Grace Mwangi ── Sales     ├─ Elena Vasquez ── Eng     └─ Hassan Ali ── Finance
      │   ├─ Ben Carter            │   ├─ Jane Okafor              ├─ Ingrid Larsen
      │   │   ├─ Nadia Petrov      │   │   ├─ John Alvarez         ├─ Paolo Bianchi
      │   │   ├─ Carlos Mendes     │   │   ├─ Aisha Khan           ├─ Fatima Zahra
      │   │   └─ Ruth Adeyemi      │   │   ├─ Diego Santos         └─ Wei Chen
      │   ├─ Yuki Tanaka           │   │   └─ Mei Lin
      │   └─ Adam Novak            │   ├─ Tom Byrne
      │                            │   │   ├─ Omar Haddad
      └─ Kevin Doyle ── People Ops │   │   ├─ Sofia Rossi
          ├─ Hana Iqbal (HR)       │   │   ├─ Liam O'Connor
          ├─ Rosa Delgado (HR)     │   │   └─ Tara Fields  ← DEACTIVATED
          └─ Samuel Boateng        │   └─ Devin Marsh (SUPER_ADMIN)
```

## Why it is shaped this way

Each of these exists to give a specific rule something to bite on. A three-person test organisation would let several later denial tests pass because there is nothing to catch.

| Feature of the seed | What it tests |
|---|---|
| **Leadership have no manager and no department** | P-7.2 - never reviewees, no PDP or PIP. The review chain terminates just below them. |
| **John → Jane → Elena → Priya** | P-1.1 - direct reports only. A query that walked the chain would hand Elena access to John. |
| **Kevin (HR Head) reports to Richard (Leadership)** | P-2.6 - the HR Head's own review is conducted from outside HR, which is what keeps the own-review block absolute (P-2.2) without leaving him unreviewed. |
| **Hana and Rosa are HR *inside* People Operations** | P-2.3 - must be blocked in their own department without an explicit grant, and blocked from their own review whatever grant they hold (P-2.2). |
| **Tara Fields is deactivated** | P-0.7 - must vanish from peer selection, manager lists and new cycles, while her row and history survive. |
| **Devin is a dedicated platform account** | P-9.5 - held alone, outside the hierarchy, and never a reviewee. He still reads no review content (P-9.4); now he has none of his own either. Reverses the earlier seeding, where he was an ordinary engineer who also administered. |
| **Four departments of unequal size** | HR department scoping (P-2.1) has something asymmetric to scope. |

## Resetting

The seed only ever adds. To start over, drop and re-migrate:

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -u altrium -p -e "DROP DATABASE altrium; CREATE DATABASE altrium CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
cd backend
.\mvnw.cmd flyway:migrate
```
