# Demo data

What is in the local `altrium` schema, and what each row is there to show. Written 2026-08-27.

This is **local demonstration data, not a seeded fixture.** It was inserted directly into MySQL
and is not reproduced by Flyway or by `SeedData`. A fresh machine gets the org structure and the
accounts, and none of the content below. The SQL that produced it is not in the repository.

## Cycles

| Cycle | Quadrimester | Status | Role in the demo |
|---|---|---|---|
| 1 | 2026 Q3 | CLOSED | A finished cycle. Eight people, every stage complete, every rating shared. |
| 2 | 2026 Q1 | OPEN | The live cycle. Nine people, deliberately at different stages. |

## Cycle 2, the open one

| Person | Department | Manager | Self | Peers | Manager review | Rating |
|---|---|---|---|---|---|---|
| John Alvarez | Engineering | Jane Okafor | yes | 2/2 | yes | Meets, **shared** |
| Aisha Khan | Engineering | Jane Okafor | yes | 2/2 | yes | Exceeds, **shared** |
| Diego Santos | Engineering | Jane Okafor | yes | **1/2** | - | not set |
| Hana Iqbal | People Operations | Kevin Doyle | yes | 2/2 | yes | Exceeds, **awaiting sign-off** |
| Samuel Boateng | People Operations | Kevin Doyle | yes | **0/0** | - | not set |
| Kevin Doyle | People Operations | Richard Hale | yes | - | - | not set |
| Ingrid Larsen | Finance | Hassan Ali | yes | 2/2 | yes | Meets, **awaiting sign-off** |
| Hassan Ali | Finance | Marcus Webb | yes | - | - | not set |
| Ben Carter | Sales | Grace Mwangi | yes | - | - | not set |

The stages are chosen, not incidental:

- **John** is the complete story end to end, including an HR approval recorded unchanged.
- **Aisha** carries the other kind of calibration: her manager set Meets and HR moved it to
  Exceeds, so the trail shows an adjustment beside John's approval.
- **Diego** has one peer of two submitted. This is the state the manager's drafting gate keys
  on: Jane cannot write his review or set his rating until the second arrives.
- **Samuel** has nobody assigned, so the peer picker is demonstrable from the start.
- **Hana** and **Ingrid** are both waiting on HR sign-off, in two different departments, which
  is what lights the dot on **HR reviews**.
- **Kevin**, **Hassan** and **Ben** hold a self-review and nothing else. All three report to
  Leadership, and see the gap below.

## Cycle 1, the closed one

Eight people, all complete and all shared: Elena Vasquez, Sofia Rossi, Liam O'Connor (Engineering),
Wei Chen (Finance), Adam Novak, Carlos Mendes, Ruth Adeyemi, Yuki Tanaka (Sales).

Ratings are spread across all three values (2 Needs improvement, 3 Meets, 3 Exceeds) so the
Leadership distribution chart has something to show. Seven ratings were approved unchanged;
**Sofia Rossi's was moved down** from Exceeds to Meets by HR, with the reasoning recorded.

## Plans

Development goals exist for John, Aisha, Diego, Kevin, Samuel and Hana, covering every state the
two axes allow: still in draft and therefore invisible to the employee, submitted and awaiting
agreement, agreed and running with a progress note, and approved as complete.

**One improvement plan is running**, on Diego Santos. Opened by Jane on 20 August, co-signed by
Kevin on the 21st, witness recorded, deadline 30 October. Three goals, one approved as complete.
Diego's development plan is SUSPENDED, as opening a PIP does.

That plan is a clean P-5.4 demonstration: the manager opened it, and HR from **another**
department co-signed it. It also cannot be failed before 30 October, which is the fixed deadline
doing its job.

John and Aisha deliberately hold no improvement plan, so a PIP can still be opened live.

## Two things this data does not paper over

**Peer assignments were corrected.** Aisha's two peers were assigned before P-3.12 and P-3.13
existed: one was Richard Hale (Leadership) and one was Kevin Doyle (HR, and from another
department). Both are refused at assignment now, so the rows were moved onto two Engineering
colleagues. Every peer assignment in the database now satisfies the current rules, verified by
query: no Leadership peers, no HR peer outside their own department, no manager reviewing their
own report as a peer, nobody their own peer.

**Kevin, Hassan and Ben have no manager review and cannot get one through the UI.** All three
report to Leadership, who are their direct managers and hold `WRITE_MANAGER_REVIEW` on them by
`DIRECT_MANAGER` grounds. But Leadership hold no MANAGER role, so there is no **My team** link
and no route to their reports' records. The policy permits it; the console has no door.
Left as it is and recorded here rather than worked around in the data.
