# Credentials for the two remaining Sprint 2 features

Email deadline reminders need a **Resend** API key. Google Calendar scheduling needs a **Google
Cloud** project with an OAuth client and a list of test users. Neither can be created from code,
so this is the manual half, written out once.

Nothing here is built yet. Getting these first means the features can be written against a real
service instead of a mock that agrees with itself.

## Read this part first

Two things will stop the features working even with perfect credentials. **Both were decided by
the Product Owner on 2026-09-06** and the decisions are recorded inline below, but the reasoning
is kept because it is what the code has to satisfy.

### 1. The seeded people have fake email addresses

Everyone in the database has an address at `altrium.test`, which is a reserved domain that
accepts no mail. `jane@altrium.test` is not a mailbox and never will be. Resend will accept the
send and the message will bounce or vanish.

So a reminder cannot reach a seeded person as things stand. Three ways out, in order of how much
work they are:

| Option | What it costs | What the demo shows |
|---|---|---|
| **Send everything to one real inbox** in non-production, ignoring the stored address **(CHOSEN)** | One config flag and a line of code | Reminders arriving, correctly worded, all in your own inbox |
| **Give the seven sign-in accounts real addresses** | A migration and new seed values | Reminders arriving at the right person, for the seven who matter |
| **Leave it** | Nothing | The scheduling and suppression logic, proved by tests only |

**Decided: send everything to one real inbox in non-production.** The usual staging answer, and
it pairs with the `onboarding@resend.dev` sender below, which can only deliver to the account
owner anyway. The two constraints cancel out.

Implemented as a redirect rather than as a change to anybody's stored address, so the
application still resolves the right recipient and then substitutes the destination at the last
moment. The real address is written into the subject line, so a demo inbox full of reminders
still shows who each one was for. Unset the redirect and production behaviour is the default,
which is the direction that failure should point.

### 2. Google will not redirect back to a plain HTTP host

Google's OAuth rules allow `http://localhost` and require `https://` for everything else. The
EC2 demo was served over plain HTTP, so a redirect URI of
`http://ec2-....compute-1.amazonaws.com/...` **will be rejected when you try to register it**.

That leaves two honest options:

- **Demo the calendar feature from `localhost` only.** Nothing else about Altrium needs to
  change. This is the cheap answer.
- **Put TLS in front of the instance (CHOSEN).** An `sslip.io` hostname against the Elastic IP,
  a Let's Encrypt certificate, and the new origin re-registered in Asgardeo.

**Decided: TLS, via `sslip.io`.** It also fixes the larger problem it sits inside, which is that
on plain HTTP the bearer token reaches the API in clear text, and it lets the ID-token signature
check turn itself back on.

**The instance does not exist right now.** It was imaged and terminated on 2026-09-01, and the
Elastic IP was released. So TLS is not a task to do today: it is part of bringing the instance
back, and the steps are in [deploy-ec2.md](deploy-ec2.md) under *Bringing it back up*, where
they now sit between attaching the address and rebuilding the frontend. Doing it in that order
matters, because the certificate hostname is what the frontend bundle and both consoles are then
registered against, and getting it wrong means rebuilding and re-registering twice.

The Resend half is unaffected. Sending mail involves no redirect URI and no certificate, so
reminders can be built and demonstrated from `localhost` today, before the instance comes back.

---

# Part A: Resend

You do not have an account yet, so start at the beginning. Free tier is 3,000 emails a month and
100 a day, which is far beyond what roughly 100 employees generate in deadline reminders.

## A1. Create the account

1. Go to **https://resend.com** and choose **Sign up**.
2. Sign up with GitHub or with an email address and password. Either is fine; GitHub is one step
   fewer.
3. Confirm your email address from the message they send. Nothing works until you do.

## A2. Decide what the "from" address will be

This is the one decision in the Resend setup, and it is worth thirty seconds of thought.

**Option 1, no domain: `onboarding@resend.dev`.** Available immediately, no DNS, no waiting.
The catch is severe and is the thing people miss: **Resend will only deliver from that address
to the email address of the account owner.** Send to anyone else and the API returns an error.
For a demo where every reminder lands in your own inbox, that is fine and even convenient. For
anything else it is not.

**Option 2, your own domain.** Needed if reminders must reach anybody but you. Requires a domain
you control and three DNS records. If the group has a domain already, this takes about ten
minutes plus DNS propagation.

Given the `altrium.test` problem above, **option 1 plus "send everything to one inbox" is the
consistent pair**, and is what to pick unless somebody already owns a domain.

### If you chose option 2

1. In the Resend dashboard, open **Domains**, then **Add Domain**.
2. Enter the domain and pick a region.
3. Resend shows three records to create at your DNS provider: an **MX** record and two **TXT**
   records (DKIM and SPF).
4. Add them at the registrar. Do not change the names or values.
5. Back in Resend, press **Verify**. It may take a few minutes and occasionally an hour.
6. Once it reads **Verified**, your from address is anything `@yourdomain`, for example
   `altrium@yourdomain`.

## A3. Create the API key

1. In the dashboard, open **API Keys**.
2. Choose **Create API Key**.
3. Name it something that says where it is used, for example `altrium-local-dev`. Create a
   separate key later for the deployed instance, so one can be revoked without breaking the
   other.
4. Permission: choose **Sending access**. Not full access. The application only sends; a key
   that can also read and delete domains is a key that can do more damage if it leaks.
5. If it offers a domain restriction and you completed option 2, restrict it to your domain.
6. Press **Add**.

**The key is shown once.** It starts with `re_`. Copy it now; there is no way to see it again,
only to delete it and make another.

## A4. Put the key where the application will find it

**Done on 2026-09-06.** The written record of every value is `local.md`, which sits at the
project root **one level above the repository**, beside `users.md` and `altrium-demo.pem`. Git
cannot see it: `git rev-parse --show-toplevel` returns `.../altrium`, and `local.md` is outside
that, so there is no path by which it reaches a commit. It is a record, not a mechanism; the
application reads the environment variables below, which were set from it.

**Never in the repository.** `pasindubalasooriya/altrium` is public, and a committed API key is a
key somebody else is using within the hour. Treat a key that reaches a commit as burnt: delete it
in Resend and create another, because removing it from the file does not remove it from the
history.

Set it as a user-level environment variable on Windows, the same way the MySQL password is
already handled:

```powershell
[Environment]::SetEnvironmentVariable('RESEND_API_KEY', 're_your_key_here', 'User')
```

Close and reopen the terminal afterwards. A shell started before the variable existed will not
see it.

On the EC2 instance it belongs in `/etc/altrium/altrium.env`, next to the database password,
mode 640 and owned `root:altrium`. That file is already outside the repository and already
excluded from the bootstrap log.

## A5. Check it works before writing any code

**Done on 2026-09-06.** Resend accepted the send and returned a message id.

Worth knowing before somebody repeats this: **the status endpoint answers 401 with this key**,
and that is correct rather than broken. The key was created with *Sending access* per A3, so it
can send and cannot read. Confirming delivery means looking in the inbox, not asking the API.

```bash
curl -X POST https://api.resend.com/emails \
  -H "Authorization: Bearer $RESEND_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
        "from": "onboarding@resend.dev",
        "to": "your-own-address@example.com",
        "subject": "Altrium reminder test",
        "text": "If this arrives, the key works."
      }'
```

A JSON body with an `id` means it was accepted. Use **your own account address** as the
recipient if you took option 1, or the send is refused.

Worth doing now. A failure here is a key problem, and a failure after the feature is written
looks like a code problem.

---

# Part B: Google Cloud

You already have a Google account, so this is configuration rather than sign-up. The whole thing
is free; Calendar API usage at this scale costs nothing.

Console wording moves around. The names below were current at the time of writing; if a menu has
been renamed, the search box at the top of the console finds it faster than hunting.

## B1. Create a project

1. Go to **https://console.cloud.google.com**.
2. Click the project selector in the top bar, then **New Project**.
3. Name it `altrium`. Leave the organisation as **No organisation** unless your university
   account forces one.
4. **Create**, then make sure the selector at the top now says `altrium`. Everything after this
   applies to the selected project, and configuring the wrong one is the classic wasted hour.

## B2. Enable the Calendar API

1. Search for **Google Calendar API**, or go to **APIs and Services**, then **Library**.
2. Open **Google Calendar API** and press **Enable**.

Nothing else needs enabling. The application reads free/busy and creates events, both of which
are this one API.

## B3. Configure the consent screen

This is what a person sees when Altrium asks for access to their calendar.

1. **APIs and Services**, then **OAuth consent screen**.
2. User type: **External**. Internal is only available to Google Workspace organisations, and
   Altrium's people have personal Gmail accounts, which is exactly the situation scenario section
   12 describes.
3. Fill in the required fields:
   - **App name**: `Altrium`
   - **User support email**: your address
   - **Developer contact email**: your address
   Leave logo and links empty. They are only needed for verification, which you are deliberately
   not doing.
4. Save and continue.

## B4. Add the scopes

Still in the consent screen flow, on the **Scopes** step:

1. **Add or remove scopes**.
2. Add these two:

   | Scope | Why |
   |---|---|
   | `https://www.googleapis.com/auth/calendar.events` | Create the meeting on the organiser's calendar and invite the other person |
   | `https://www.googleapis.com/auth/calendar.readonly` | Query free/busy to propose a mutual slot |

   If the list offers `https://www.googleapis.com/auth/calendar.freebusy`, take that instead of
   `calendar.readonly`. It permits free/busy queries and nothing else, which is exactly what the
   feature needs and considerably less than read access to everybody's calendar contents.

3. **Do not** add `https://www.googleapis.com/auth/calendar`. It is full read and write over
   every calendar the person has, and the feature needs neither.
4. Update, then save and continue.

Google marks these as sensitive. That is expected and is why the next step matters.

## B5. Add test users, and stay in Testing

On the **Test users** step:

1. **Add users**, and enter the Gmail address of every person who will connect a calendar during
   the demo. Include your own.
2. Save.

**Leave the app in Testing. Do not press "Publish app".** Testing mode allows up to 100 test
users, which covers Altrium's whole organisation, and needs no verification review. Publishing
would send sensitive scopes into a verification process that takes weeks and asks for a privacy
policy and a demo video, for no benefit here.

Anyone not on the test-user list gets "Altrium has not completed the Google verification
process" and is refused. If a group member cannot connect their calendar, this list is the first
thing to check.

Test-mode refresh tokens expire after **seven days**. Expect to reconnect calendars if the demo
is more than a week after you set this up, and do not treat it as a bug.

## B6. Create the OAuth client

1. **APIs and Services**, then **Credentials**.
2. **Create Credentials**, then **OAuth client ID**.
3. Application type: **Web application**. Not Desktop. The token exchange happens in the Spring
   backend, because the refresh token must be stored server-side rather than reaching a browser.
4. Name: `altrium-backend`.
5. **Authorised redirect URIs**, press **Add URI** and enter:

   ```
   http://localhost:8080/api/integrations/google/callback
   ```

   Google accepts `http` for `localhost` and only for `localhost`. Add the deployed URI here as
   well **only once the instance is served over HTTPS**; see the note at the top.

   The path must match what the backend registers, character for character, including whether
   there is a trailing slash. Mismatches surface as `redirect_uri_mismatch`, which names the
   problem clearly enough once you know to read it.

6. **Create**. A dialog shows the **Client ID** and **Client secret**.
7. Copy both. The secret can be viewed again later from the Credentials page, unlike the Resend
   key, but copy it now anyway.

## B7. Store the client credentials

**Done on 2026-09-06**, from `local.md`, into the environment variables below.

The **client ID is not a secret** and is fine in the repository; the Asgardeo one already is, for
the same reason. **The client secret is a secret** and must not be committed.

```powershell
[Environment]::SetEnvironmentVariable('GOOGLE_OAUTH_CLIENT_ID', 'xxxx.apps.googleusercontent.com', 'User')
[Environment]::SetEnvironmentVariable('GOOGLE_OAUTH_CLIENT_SECRET', 'GOCSPX-xxxx', 'User')
```

On EC2, both belong in `/etc/altrium/altrium.env` with the others.

If the secret ever reaches a commit, **reset it** from the Credentials page rather than deleting
the file. Resetting invalidates the old value; deleting the file does not.

---

## What you should have at the end

| Item | Looks like | Where it lives | State |
|---|---|---|---|
| Resend API key | `re_...` | `local.md`, and `RESEND_API_KEY` | **done** |
| Resend from address | `onboarding@resend.dev` | Application config, not secret | **done**, no domain |
| Google client ID | `...apps.googleusercontent.com` | `local.md`, and `GOOGLE_OAUTH_CLIENT_ID` | **done** |
| Google client secret | `GOCSPX-...` | `local.md`, and `GOOGLE_OAUTH_CLIENT_SECRET` | **done** |
| Redirect URI | `http://localhost:8080/api/integrations/google/callback` | Registered in Google, and `GOOGLE_OAUTH_REDIRECT_URI` | **done**, matches the built endpoint |
| Test users | Every demo participant's Gmail address | Google consent screen | check before the demo |
| Reminder redirect inbox | one real address | `ALTRIUM_REMINDER_REDIRECT_TO` | **done** |

`local.md` is the written record and lives at the project root, outside the repository. The
environment variables are what the application actually reads; if the two ever disagree, the
file is the one to correct, because a variable set months ago on one laptop is not a record of
anything.

And two decisions recorded, because the code depends on them:

- **Where reminders are sent** while the database holds `altrium.test` addresses.
- **Whether the calendar feature is demonstrated from localhost**, or the instance gets TLS.

### What the Calendar feature does with these, now that it is built

The client secret does **two** jobs, and the second is not obvious. Besides exchanging codes
with Google, it is the input the refresh-token encryption key is derived from (`TokenCipher`).
So **rotating the client secret makes every stored connection unreadable and everybody has to
click Connect again.** That is the correct behaviour, since rotating it invalidates those
tokens at Google's end anyway, but it is worth knowing before rotating one casually.

Nothing else needs a new secret. If `GOOGLE_OAUTH_CLIENT_SECRET` is unset the integration
switches itself off cleanly: no key, so no token could be stored safely, so the connect
endpoint refuses before the flow starts rather than halfway through it.

## A note on the public repository

`altrium/` pushes to a public GitHub repository. Everything in this file that is marked secret
stays in an environment variable or in `/etc/altrium/altrium.env`, and neither is in the
repository. The local MySQL development password in [local-setup.md](local-setup.md) is a
deliberate, Product-Owner-confirmed exception and is not a precedent for these: a database
reachable only from one laptop is not an email service anybody on the internet can spend your
quota on.
