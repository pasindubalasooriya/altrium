# User directory

Everyone in the `altrium` development database, read straight from it on 2026-08-27.

This is a **snapshot for reference while testing**, not a source of truth. The database is, and it moves: roles change, people are deactivated, grants are revoked. [seed-data.md](seed-data.md) is the description of what the seeder builds and why; this is what the rows actually say today.

**No passwords are recorded here.** The sign-in passwords live in `users.md`, outside this repository, and stay there.

## Everyone

31 people. `Reports` counts direct reports.

| # | Name | Email | Roles | Department | Manager | Cohort | Reports | Active |
|---|---|---|---|---|---|---|---|---|
| 1 | Richard Hale | richard@altrium.test | EMPLOYEE, LEADERSHIP | - | - | - | 2 | yes |
| 2 | Priya Raman | priya@altrium.test | EMPLOYEE, LEADERSHIP | - | - | - | 1 | yes |
| 3 | Marcus Webb | marcus@altrium.test | EMPLOYEE, LEADERSHIP | - | - | - | 1 | yes |
| 4 | Elena Vasquez | elena@altrium.test | EMPLOYEE, MANAGER | Engineering | Priya Raman | Q3 Autumn | 2 | yes |
| 5 | Jane Okafor | jane@altrium.test | EMPLOYEE, MANAGER | Engineering | Elena Vasquez | Q2 Summer | 4 | yes |
| 6 | Tom Byrne | tom@altrium.test | EMPLOYEE, MANAGER | Engineering | Elena Vasquez | Q2 Summer | 4 | yes |
| 7 | John Alvarez | john@altrium.test | EMPLOYEE | Engineering | Jane Okafor | Q1 Spring | 0 | yes |
| 8 | Aisha Khan | aisha@altrium.test | EMPLOYEE | Engineering | Jane Okafor | Q1 Spring | 0 | yes |
| 9 | Diego Santos | diego@altrium.test | EMPLOYEE | Engineering | Jane Okafor | Q1 Spring | 0 | yes |
| 10 | Mei Lin | mei@altrium.test | EMPLOYEE | Engineering | Jane Okafor | Q2 Summer | 0 | yes |
| 11 | Omar Haddad | omar@altrium.test | EMPLOYEE | Engineering | Tom Byrne | Q2 Summer | 0 | yes |
| 12 | Sofia Rossi | sofia@altrium.test | EMPLOYEE | Engineering | Tom Byrne | Q3 Autumn | 0 | yes |
| 13 | Liam O'Connor | liam@altrium.test | EMPLOYEE | Engineering | Tom Byrne | Q3 Autumn | 0 | yes |
| 14 | Devin Marsh | devin@altrium.test | EMPLOYEE, SUPER_ADMIN | - | - | - | 0 | yes |
| 15 | Tara Fields | tara@altrium.test | EMPLOYEE | Engineering | Tom Byrne | - | 0 | **no** |
| 16 | Grace Mwangi | grace@altrium.test | EMPLOYEE, MANAGER | Sales | Richard Hale | Q2 Summer | 3 | yes |
| 17 | Ben Carter | ben@altrium.test | EMPLOYEE, MANAGER | Sales | Grace Mwangi | Q1 Spring | 3 | yes |
| 18 | Nadia Petrov | nadia@altrium.test | EMPLOYEE | Sales | Ben Carter | Q2 Summer | 0 | yes |
| 19 | Carlos Mendes | carlos@altrium.test | EMPLOYEE | Sales | Ben Carter | Q3 Autumn | 0 | yes |
| 20 | Ruth Adeyemi | ruth@altrium.test | EMPLOYEE | Sales | Ben Carter | Q3 Autumn | 0 | yes |
| 21 | Yuki Tanaka | yuki@altrium.test | EMPLOYEE | Sales | Grace Mwangi | Q3 Autumn | 0 | yes |
| 22 | Adam Novak | adam@altrium.test | EMPLOYEE | Sales | Grace Mwangi | Q3 Autumn | 0 | yes |
| 23 | Hassan Ali | hassan@altrium.test | EMPLOYEE, MANAGER | Finance | Marcus Webb | Q1 Spring | 4 | yes |
| 24 | Ingrid Larsen | ingrid@altrium.test | EMPLOYEE | Finance | Hassan Ali | Q1 Spring | 0 | yes |
| 25 | Paolo Bianchi | paolo@altrium.test | EMPLOYEE | Finance | Hassan Ali | Q2 Summer | 0 | yes |
| 26 | Fatima Zahra | fatima@altrium.test | EMPLOYEE | Finance | Hassan Ali | Q2 Summer | 0 | yes |
| 27 | Wei Chen | wei@altrium.test | EMPLOYEE | Finance | Hassan Ali | Q3 Autumn | 0 | yes |
| 28 | Kevin Doyle | kevin@altrium.test | EMPLOYEE, HR, MANAGER | People Operations | Richard Hale | Q1 Spring | 3 | yes |
| 29 | Hana Iqbal | hana@altrium.test | EMPLOYEE, HR | People Operations | Kevin Doyle | Q1 Spring | 0 | yes |
| 30 | Rosa Delgado | rosa@altrium.test | EMPLOYEE, HR | People Operations | Kevin Doyle | Q2 Summer | 0 | yes |
| 31 | Samuel Boateng | samuel@altrium.test | EMPLOYEE | People Operations | Kevin Doyle | Q1 Spring | 0 | yes |

## The seven who can actually sign in

Everyone else has a placeholder subject (`seed-*`) and exists only to give the queries something to page and scope over. These have real Asgardeo subjects behind them.

Identity is matched on `asgardeo_subject`, **never** on the email address. An account created in the Asgardeo console alone therefore authenticates perfectly and then meets "your account is not set up in Altrium": the token is valid and names nobody this system knows. Adding somebody takes both halves - the console account, and the subject recorded against their row in `SeedData` and in the database.

| Name | Email | What they demonstrate | Asgardeo subject |
|---|---|---|---|
| Devin Marsh | devin@altrium.test | Super Admin: users, departments, grants, cycles, cohorts. Reads no review content anywhere (P-9.4). | `f891d297-d2c7-4161-9650-ff00cdfc1cae` |
| Richard Hale | richard@altrium.test | Leadership: aggregate totals only, with nothing clickable through to a person (P-7.1). | `9eebabd1-064e-4e06-8e39-cf9be834f4b1` |
| Jane Okafor | jane@altrium.test | Manager of four, three of them in the open Q1 cycle. The whole manager path. | `626a2c84-f344-426b-a369-68645cabcf5c` |
| John Alvarez | john@altrium.test | Employee under Jane, in Q1. Self-review, own rating, PDP agreement. | `cdb99960-4f43-4892-81ac-92f46d8ee260` |
| Aisha Khan | aisha@altrium.test | Employee under Jane, in Q1. The second reviewee, so a peer can be somebody who can log in. | `03b015cb-179b-44ed-904c-8d53f5db902a` |
| Diego Santos | diego@altrium.test | Employee under Jane, in Q1. Added because P-3.12 and P-3.13 left John only one eligible peer who could sign in, and a subject needs two. | `d93fb3c8-b623-4064-b815-87dac7b7cf97` |
| Kevin Doyle | kevin@altrium.test | HR Head **and** a manager **and** a reviewee in Q1. The rule-ordering case. | `4ac3f0d9-3af4-4b0c-a4da-369b683f7ea1` |

A subject identifier is not a credential - it names the account, it does not open it.

## HR grants

`Explicit` is the P-2.4 override, and it lifts **only** the own-department block. It never reaches the caller's own record: Kevin holds an explicit grant over People Operations and still cannot read, calibrate or co-sign anything of his own.

| HR user | Department | Own department | Explicit |
|---|---|---|---|
| Kevin Doyle | Engineering | no | no |
| Kevin Doyle | People Operations | **yes** | **yes** |
| Hana Iqbal | Engineering | no | no |
| Hana Iqbal | Sales | no | no |
| Rosa Delgado | Finance | no | no |
| Rosa Delgado | People Operations | **yes** | no |

Rosa is the contrast that makes the rule visible: same department as Kevin, no explicit grant, so People Operations is excluded from everything she sees.

## Departments

| # | Name | People |
|---|---|---|
| 1 | Engineering | 11 |
| 2 | Sales | 7 |
| 3 | Finance | 5 |
| 4 | People Operations | 4 |

Engineering's 11 includes Tara Fields, who is deactivated - the row stays and stays counted here, which is the point of deactivating rather than deleting.

Four people hold no department at all: the three Leadership, and the Super Admin, who has none by design (P-9.5).

## Things worth knowing before testing

- **Tara Fields (15) is deactivated.** She keeps her row, her manager and her history, because deactivation sets a flag and never deletes. She is in no cohort and cannot be assigned as a peer.
- **Devin Marsh holds `SUPER_ADMIN` and `EMPLOYEE` and nothing else.** MANAGER, HR and LEADERSHIP are refused alongside it. `EMPLOYEE` stays because it is the provisioned-and-active marker every endpoint requires, not a job (P-9.5).
- **Nobody is reviewed in every cycle.** Cohorts are per quadrimester, so the open Q1 cycle covers nine people. Jane is in Q2 and correctly has no review of her own in Q1 - her console is for reviewing her reports.
- **The three Leadership hold no cohort and are never reviewed.** Neither is the Super Admin.
