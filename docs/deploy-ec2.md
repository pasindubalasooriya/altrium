# Deploying the demo to EC2

One free-tier instance running the backend, the database and the frontend, served over plain
HTTP. **Demo-grade on purpose:** no load balancer, no RDS, no TLS, no autoscaling. Nothing in
this project is demonstrated by infrastructure, and the graded core is the authorization model.

**Currently torn down.** The demo was delivered on 2026-09-01 and the instance was terminated
the same day. What survives is a machine image, `ami-0385d6c57ee804b77`, taken from the box
after a clean shutdown. Bringing it back is a `run-instances` and a frontend rebuild; see
[Bringing it back up](#bringing-it-back-up). Everything below still describes the deployment
accurately, because the image is that deployment.

## What is on the box

```
Ubuntu 24.04 LTS, t3.micro, 1 GB, 8 GB gp3, us-east-1

  nginx        :80    public     SPA from /var/www/altrium
                                 /api/ proxied to 8080
                                 swagger paths return 404
  altrium.jar  :8080  loopback   systemd unit, SERVER_ADDRESS=127.0.0.1
  mysql 8.0    :3306  loopback   bind-address 127.0.0.1

  security group  sg-01f589131b0ff0c55       KEPT
                  80 from 0.0.0.0/0
                  22 from the operator's IP    stale, see below
  key pair        altrium-demo (ed25519)      KEPT
  machine image   ami-0385d6c57ee804b77       snap-070d61394991db19d

  elastic ip      eipalloc-0f226f3c5f5e5572b  35.173.168.105   RELEASED
  instance        i-08359c29d12ebe1e2                          TERMINATED
  root volume     vol-0b1152c7006ac0436                        DELETED with it
```

The group and the key pair are kept because neither is billed and both are named by the restore
command. The SSH rule in the group still names whichever address the operator had on
2026-08-30, which will not be the address they have next time; the restore steps below replace
it rather than assuming it.

Two decisions carry most of the weight.

**One nginx serves both halves**, so the SPA and the API are the **same origin**. The backend's
CORS configuration therefore never comes into play in the deployment, and only port 80 is open.
Development is deliberately the opposite - two origins, real cross-origin calls, no Vite proxy -
because that is what keeps the CORS path honest rather than hiding a misconfiguration until the
first deploy.

**Nothing is compiled on the instance.** The jar and the SPA bundle are built on a workstation
and copied up. Maven and Vite on a 1 GB box is how a demo gets lost, and it also means the
instance needs no Node, no Maven and no git.

## The Elastic IP, and why it was still released

Asgardeo refuses a login whose redirect URL it has not registered, and an auto-assigned public
address changes on every stop/start. Each change would silently break login until somebody
edited the Asgardeo console again. An Elastic IP fixes the hostname, so within one deployment
the registration is done once and stays done.

That argument holds **while the instance exists**. It stops holding once the instance is gone:
the address was then costing 3.65 USD a month to reserve a hostname for an application that was
not running, which is most of the bill for none of the benefit. So it was released at teardown.

The price of releasing it is one manual step on restore, not a broken deployment. A new
instance gets a new address and therefore a new `ec2-...compute-1.amazonaws.com` hostname, and
that hostname has to be registered in Asgardeo and compiled into the frontend bundle before
login works. Both are in the restore steps, and the Asgardeo edit is two fields in a console.

## Asgardeo configuration

Registered on the application in the Asgardeo console, in **both** fields:

| Field | Values |
|---|---|
| Authorized redirect URLs | `http://localhost:5173` and `http://ec2-35-173-168-105.compute-1.amazonaws.com` |
| Allowed origins | the same two |

Both were kept. Removing the localhost entry would break local development; removing the
instance entry breaks the demo. Neither interferes with the other.

**The instance entry is now dead**, because that address has been released. It is harmless
where it is: an unregistered host is refused, but a registered host that no longer answers is
simply never visited. Leave it or replace it on restore, as convenient. Note that AWS hands
released addresses back out, so in principle somebody else could end up holding
`35.173.168.105`; they still could not use this registration without the Asgardeo client
configuration, but replacing the entry rather than accumulating entries is the tidier habit.

## Living inside 1 GB

MySQL 8 and a JVM do not fit in 1 GB without being told to behave.

**Swap**, 2 GB at `/swapfile`, with `vm.swappiness=10` in `/etc/sysctl.d/99-altrium.conf`.
Created before anything is installed, because `apt` alone will otherwise get the box OOM-killed.

**MySQL**, in `/etc/mysql/mysql.conf.d/99-altrium.cnf`:

```
bind-address            = 127.0.0.1
performance_schema      = OFF          # worth about 200 MB on its own
innodb_buffer_pool_size = 128M
innodb_log_buffer_size  = 8M
max_connections         = 30
table_open_cache        = 200
```

**The JVM**, in the systemd unit: `-Xmx350m -Xss512k -XX:MaxMetaspaceSize=180m -XX:+UseSerialGC`.
The heap cap is the important one. Left alone the JVM claims a quarter of the machine, and
MySQL is what the kernel then kills.

At idle after startup the box sits around 700 MB used with roughly 200 MB available and only a
few MB of swap touched. That is tight but stable. `free -m` is the first thing to look at if
the demo feels slow.

## Provisioning, as it was actually run

```bash
# 1. The address, first, because it determines the Asgardeo redirect URL
aws ec2 allocate-address --domain vpc \
  --tag-specifications 'ResourceType=elastic-ip,Tags=[{Key=Name,Value=altrium-demo}]'

# 2. Key pair. The .pem is a private key and lives OUTSIDE the repository.
aws ec2 create-key-pair --key-name altrium-demo --key-type ed25519 \
  --query KeyMaterial --output text > ../altrium-demo.pem
chmod 600 ../altrium-demo.pem

# 3. Security group. SSH is scoped to one address, not 0.0.0.0/0.
MYIP=$(curl -s https://checkip.amazonaws.com | tr -d '\r\n')
SG=$(aws ec2 create-security-group --group-name altrium-demo \
       --description "Altrium demo" --query GroupId --output text)
aws ec2 authorize-security-group-ingress --group-id "$SG" --ip-permissions \
  "IpProtocol=tcp,FromPort=80,ToPort=80,IpRanges=[{CidrIp=0.0.0.0/0}]" \
  "IpProtocol=tcp,FromPort=22,ToPort=22,IpRanges=[{CidrIp=$MYIP/32}]"

# 4. Current Ubuntu 24.04 image, resolved rather than pinned
aws ec2 describe-images --owners 099720109477 \
  --filters 'Name=name,Values=ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*' \
            'Name=state,Values=available' \
  --query 'sort_by(Images,&CreationDate)[-1].ImageId' --output text

# 5. Launch with the bootstrap as user-data, then attach the address
aws ec2 run-instances --image-id "$AMI" --instance-type t3.micro \
  --key-name altrium-demo --security-group-ids "$SG" \
  --block-device-mappings 'DeviceName=/dev/sda1,Ebs={VolumeSize=8,VolumeType=gp3}' \
  --user-data fileb://user-data.sh \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=altrium-demo}]'

aws ec2 associate-address --instance-id "$ID" --allocation-id "$ALLOC"
```

The bootstrap script creates the swap file, installs `nginx`, `mysql-server` and
`openjdk-21-jre-headless`, writes the MySQL tuning, creates the `altrium` schema and database
user with a **generated** password, and writes `/etc/altrium/altrium.env` (mode 640,
`root:altrium`).

### Two Git Bash traps

Both cost time on the first run, both are environment rather than AWS:

- `aws ec2 create-key-pair ... > file.pem` writes **CRLF** line endings and the key is then
  rejected with `error in libcrypto`. Pipe it through `tr -d '\r'`.
- Git Bash rewrites anything that looks like a Unix path, so `DeviceName=/dev/sda1` reaches AWS
  as `C:/Program Files/Git/dev/sda1`. Prefix the command with `MSYS_NO_PATHCONV=1`.

## Bringing it back up

From the image, not from `user-data.sh`. The image already holds nginx, MySQL with the seeded
database, the jar, the systemd unit and `/etc/altrium/altrium.env` with the generated database
password, so none of the bootstrap runs again and nothing needs reseeding. What it does **not**
hold is a correct frontend bundle, for the reason in step 5.

```bash
# 1. A fresh address. Cheaper to hold one than to re-register a hostname mid-demo.
ALLOC=$(aws ec2 allocate-address --domain vpc --tag-specifications 'ResourceType=elastic-ip,Tags=[{Key=Name,Value=altrium-demo}]' --query AllocationId --output text)

# 2. The group's SSH rule names an address the operator no longer has. Look, revoke, re-add.
aws ec2 describe-security-groups --group-ids sg-01f589131b0ff0c55 --query 'SecurityGroups[0].IpPermissions[?FromPort==`22`].IpRanges[].CidrIp' --output text
MYIP=$(curl -s https://checkip.amazonaws.com | tr -d '[:space:]')
aws ec2 authorize-security-group-ingress --group-id sg-01f589131b0ff0c55 --ip-permissions "IpProtocol=tcp,FromPort=22,ToPort=22,IpRanges=[{CidrIp=$MYIP/32}]"

# 3. Launch from the image. Same type, same key, same group.
ID=$(aws ec2 run-instances --image-id ami-0385d6c57ee804b77 --instance-type t3.micro --key-name altrium-demo --security-group-ids sg-01f589131b0ff0c55 --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=altrium-demo}]' --query 'Instances[0].InstanceId' --output text)
aws ec2 wait instance-running --instance-ids "$ID"

# 4. Attach the address, then read off the hostname everything else depends on.
aws ec2 associate-address --instance-id "$ID" --allocation-id "$ALLOC"
IP=$(aws ec2 describe-addresses --allocation-ids "$ALLOC" --query 'Addresses[0].PublicIp' --output text)
HOST="ec2-$(echo $IP | tr . -).compute-1.amazonaws.com"
echo "$IP  $HOST"
```

Revoking the stale `/32` matters more than it looks: leave it and the group accumulates one
dead operator address per demo, each a standing permit to port 22 for whoever holds that
address next.

### 5. TLS, before anything is registered anywhere

Decided by the Product Owner on 2026-09-06, and it changes the hostname, so it comes **before**
the Asgardeo registration and the frontend rebuild rather than after. Getting the order wrong
means registering one origin, building a bundle for it, and then doing both again.

It buys three things at once. The bearer token stops travelling to the API in clear text, which
is the real problem the demo has been carrying. `validateIDToken` turns itself back on, because
`crypto.subtle` exists in a secure context. And Google will accept a redirect URI on this host,
which it refuses for plain `http` on anything but `localhost` and which is what the Calendar
feature needs.

The hostname comes from the address, with no DNS to configure: `sslip.io` resolves
`52-1-2-3.sslip.io` to `52.1.2.3`.

```bash
TLS_HOST="$(echo $IP | tr . -).sslip.io"

# 443 was never opened; only 80 and 22 are in the group.
aws ec2 authorize-security-group-ingress --group-id sg-01f589131b0ff0c55 --ip-permissions "IpProtocol=tcp,FromPort=443,ToPort=443,IpRanges=[{CidrIp=0.0.0.0/0}]"

# certbot matches on server_name, and the shipped config says `_`. It has to name the host.
ssh -i altrium-demo.pem ubuntu@$IP "sudo sed -i \"s/server_name _;/server_name $TLS_HOST;/\" /etc/nginx/sites-available/altrium && sudo nginx -t && sudo systemctl reload nginx"

ssh -i altrium-demo.pem ubuntu@$IP "sudo apt-get update && sudo apt-get install -y certbot python3-certbot-nginx"
ssh -i altrium-demo.pem ubuntu@$IP "sudo certbot --nginx -d $TLS_HOST --non-interactive --agree-tos --redirect -m YOUR_EMAIL"

echo "https://$TLS_HOST"
```

`--redirect` makes certbot add the 80-to-443 rewrite, so the plain-HTTP address stops being a
second way in rather than remaining one nobody remembers to close. Renewal is installed as a
systemd timer by the package and needs nothing further, though on a demo instance that is
terminated between sessions the certificate is usually younger than its 90 days anyway.

**The one thing that can fail here is the rate limit.** Let's Encrypt counts certificates per
registered domain, and `sslip.io` is one registered domain shared by everybody using it. A
"too many certificates already issued" error is not your mistake and no amount of retrying
fixes it within the week. If it happens, either use `nip.io`, which is the same trick under a
different domain, or fall back to plain HTTP and demonstrate the Calendar feature from
`localhost`, which was the alternative decision and costs nothing else.

From here on the host is `https://$TLS_HOST`, and `$HOST` in the steps below means that.

### 6. Register the origin, then rebuild the frontend

**Register `https://$TLS_HOST` in Asgardeo**, in both *Authorized redirect URLs* and *Allowed
origins*, before touching the frontend. Then **rebuild and copy up the SPA**, because the bundle
inside the image has the old, now dead hostname compiled into it:

```bash
cd altrium/frontend
VITE_APP_ORIGIN="http://$HOST" VITE_API_BASE_URL="http://$HOST" npm run build
tar czf /tmp/dist.tgz -C dist .
scp -i ../../altrium-demo.pem /tmp/dist.tgz ubuntu@$IP:/tmp/
ssh -i ../../altrium-demo.pem ubuntu@$IP 'sudo rm -rf /var/www/altrium/assets && sudo tar xzf /tmp/dist.tgz -C /var/www/altrium && sudo chown -R www-data:www-data /var/www/altrium'
```

This is the step that is easy to skip and hard to diagnose, because the site loads perfectly and
only login fails. `VITE_APP_ORIGIN` is **build-time** configuration, so a bundle built for the
old host sends the browser to a redirect URL that no longer resolves, and the error surfaces at
Asgardeo rather than anywhere near the mistake. The backend needs no equivalent change: it is
reached same-origin through nginx and never names a host.

The jar only needs redeploying if the code has moved on since 2026-09-01. Check rather than
assume:

```bash
ssh -i altrium-demo.pem ubuntu@$IP 'md5sum /opt/altrium/altrium.jar'
```

### 7. Verify

The [Verification](#verification) checks below and, above all, a real sign-in as Jane. That is
the check that proves the previous two steps were done in the right order.

Three extra checks now that there is TLS: `http://$TLS_HOST` redirects to `https`, the browser
shows no certificate warning, and the sign-in completes without the ID-token failure recorded
further down, which should no longer occur at all.

If the Calendar feature is in by then, register
`https://$TLS_HOST/api/integrations/google/callback` in the Google Cloud console as well; see
[sprint2-credentials.md](sprint2-credentials.md).

### Shutting it down again

```bash
aws ec2 stop-instances --instance-ids "$ID"
aws ec2 wait instance-stopped --instance-ids "$ID"
NEW=$(aws ec2 create-image --instance-id "$ID" --name "altrium-demo-$(date +%F)" --tag-specifications 'ResourceType=image,Tags=[{Key=Name,Value=altrium-demo}]' 'ResourceType=snapshot,Tags=[{Key=Name,Value=altrium-demo}]' --query ImageId --output text)
aws ec2 wait image-available --image-ids "$NEW"
aws ec2 terminate-instances --instance-ids "$ID"
aws ec2 release-address --allocation-id "$ALLOC"
```

Two orderings matter and both are one-way.

**Stop before imaging.** Imaging a running box catches MySQL between writes, and the snapshot
then holds a database that may need recovery on first boot. A clean ACPI shutdown lets systemd
stop `altrium` and then `mysql` properly, so the image holds a database that was closed rather
than interrupted.

**Wait for the image before terminating.** The image is built from the root volume, and that
volume has `DeleteOnTermination=true`. Terminating while the image is still `pending` destroys
the thing being copied.

Then delete the image it replaces, and the snapshot underneath it:

```bash
aws ec2 deregister-image --image-id ami-0385d6c57ee804b77
aws ec2 delete-snapshot --snapshot-id snap-070d61394991db19d
```

Deregistering an image does **not** delete its snapshot, and the snapshot is the part that is
billed. Both lines, or the storage quietly stays and accumulates one image per demo.

## Building and deploying

Both artifacts are built locally. The frontend needs two variables at **build** time - they are
compiled into the bundle, not read at runtime:

```bash
cd altrium/backend
./mvnw.cmd -DskipTests package          # target/altrium-backend-0.1.0-SNAPSHOT.jar

cd ../frontend
VITE_APP_ORIGIN="http://$HOST" \
VITE_API_BASE_URL="http://$HOST" \
npm run build                           # dist/
```

`VITE_APP_ORIGIN` is the Asgardeo redirect origin. It is build-time configuration and is
**never** derived from `window.location`: Asgardeo has to have registered the exact origin, so
a value guessed from whatever host served the page would fail at the redirect with an error
pointing at the identity provider rather than at the build. With no variable set the default is
`http://localhost:5173`, which is why `npm run dev` needs no `.env`.

Then copy up and install:

```bash
tar czf /tmp/dist.tgz -C altrium/frontend/dist .
scp -i altrium-demo.pem altrium/backend/target/altrium-backend-0.1.0-SNAPSHOT.jar \
    /tmp/dist.tgz altrium.service altrium.nginx ubuntu@$IP:/tmp/

ssh -i altrium-demo.pem ubuntu@$IP '
  sudo install -o altrium -g altrium -m 644 /tmp/altrium-backend-0.1.0-SNAPSHOT.jar /opt/altrium/altrium.jar
  sudo rm -rf /var/www/altrium/assets
  sudo tar xzf /tmp/dist.tgz -C /var/www/altrium
  sudo chown -R www-data:www-data /var/www/altrium
  sudo systemctl restart altrium'
```

The `rm -rf` on `assets` is not tidiness. Bundle filenames carry a content hash, so `tar` adds
the new ones beside the old rather than replacing them, and every past deploy stays on disk
being served `immutable` forever. On an 8 GB volume that is a slow leak; more to the point, it
leaves earlier versions of the application publicly reachable at their old URLs. `index.html`
is overwritten in place and is the only file that names the current bundle.

### Redeploying a code change

The same three lines. Build, copy, `systemctl restart altrium`. Frontend-only changes need no
restart at all, just the `tar`, `scp` and `chown` - and `index.html` is served `no-store` while
hashed assets are `immutable`, so a new bundle is picked up on the next page load rather than
after a cache argument.

## The database the demo starts from

**The seeded structure only.** On first boot Flyway applied V1 to V8, then `SeedData` ran once
with `ALTRIUM_SEED_ENABLED=true` and built:

- 31 people across 4 departments
- 5 HR department grants, including Kevin's explicit grant over his own department

and nothing else. No cycles, no cohorts, no reviews, no ratings, no plans. The local demo
content described in [demo-data.md](demo-data.md) stays local and was not copied up.

The seed is built through `OrgService` rather than raw SQL, so it exercises the same loop
rejection (P-1.4) and role normalisation (P-0.1) the API does. It carries the seven real
Asgardeo subjects, which is what makes the demo logins work at all.

The flag was then **removed** from `/etc/altrium/altrium.env`, so a restart does not re-run it.
To reseed from nothing:

```bash
sudo systemctl stop altrium
sudo mysql -e 'DROP DATABASE altrium; CREATE DATABASE altrium CHARACTER SET utf8mb4;'
sudo sed -i '1i ALTRIUM_SEED_ENABLED=true' /etc/altrium/altrium.env
sudo systemctl start altrium          # Flyway rebuilds, SeedData repopulates
sudo sed -i '/^ALTRIUM_SEED_ENABLED=/d' /etc/altrium/altrium.env
```

**The demo is therefore driven live and in order:** Devin creates cohorts and a cycle and opens
it, Jane assigns peers and writes a review, the peers write theirs, Kevin signs the rating off,
Jane shares it. Worth rehearsing once against the instance, because there is no pre-built state
to fall back on if a step is skipped.

## Plain HTTP breaks ID token validation, and how that is handled

The first login attempt against the deployed host failed in a way worth recording, because
everything about it looked correct. The HAR showed the full Asgardeo round trip succeeding: a
302 to `/oauth2/authorize`, the login form, `POST /oauth2/token` returning **200** with a valid
access token for the right subject, then `GET /oauth2/jwks` returning **200**. And then nothing
at all - no call to `/api/me`, and the app back on the login screen.

The SDK fetches JWKS for exactly one reason: to verify the ID token signature. It fetched the
keys and then died on the verify.

`@asgardeo/auth-spa` verifies through `jose`
(`src/utils/crypto-utils.ts`, `verifyJwt` calling `jwtVerify`), and `jose` verifies through
**WebCrypto**. `crypto.subtle` is defined **only in a secure context** - HTTPS, or localhost.
This host is neither, so the call throws and `signIn()` rejects while holding perfectly good
tokens.

PKCE is unaffected and that is what makes the failure confusing: the same file hashes the code
challenge with `fast-sha256`, a pure-JS implementation needing no WebCrypto. Everything works
right up to the last step.

`config.ts` therefore sets `validateIDToken` from whether WebCrypto exists, rather than turning
it off by hand:

```ts
const canVerifyTokenSignature = typeof crypto !== 'undefined' && crypto.subtle !== undefined
```

The check stays on locally and behind any TLS, and turns itself back on the day this is served
over HTTPS.

**What is given up.** The ID token still arrives over TLS from Asgardeo's token endpoint in
response to a PKCE exchange this app started, so it is not unauthenticated - the signature check
is defence in depth against a compromised transport, not the thing keeping anyone out. The
access control is untouched: the backend validates every access token against Asgardeo's JWKS on
every request, and a browser cannot grant itself anything by believing a token.

**The real fix is HTTPS**, which also fixes the larger problem this sits inside: on a plain HTTP
host the bearer token travels to the API in clear text, and anyone on the path can read it. That
is acceptable for a demo with fictional data on a throwaway instance and would not be acceptable
for anything else.

**Decided on 2026-09-06: the instance comes back with TLS**, via an `sslip.io` hostname against
the Elastic IP. The steps are step 5 of [Bringing it back up](#5-tls-before-anything-is-registered-anywhere),
and they run before the Asgardeo registration because they change the hostname. Everything above
therefore describes how the demo behaved between 2026-08-30 and 2026-09-01, and is kept because
the failure is worth being able to recognise, not because it is still the situation.

## Swagger is refused here

`/swagger-ui`, `/swagger-ui.html` and `/v3/api-docs` are `permitAll` in `SecurityConfig`, which
is right in development and wrong on a public host: it hands a stranger the entire endpoint
list on a box wired to real Asgardeo logins. nginx returns 404 for all three.

Refused at the edge rather than in code, deliberately, so local development keeps Swagger and
the security configuration stays the one thing it is: a statement about authorization, not
about deployment topology.

## Verification

On the instance:

```bash
systemctl is-active altrium mysql nginx     # active, active, active
sudo ss -tlnp | grep -E ':80 |:8080|:3306'  # 8080 and 3306 on 127.0.0.1 only
free -m                                      # confirm the box is not already swapping at idle
sudo journalctl -u altrium | grep -E 'Successfully applied|Seeded'
```

From outside:

| Check | Expected | Why it matters |
|---|---|---|
| `GET /` | 200 | SPA is served |
| `GET /manager/team` | 200 | client-side routing falls through to `index.html` |
| `GET /api/me` | **401** | the API is reachable and refusing an unidentified caller |
| `GET /swagger-ui.html` | **404** | blocked at the edge |
| `GET /v3/api-docs` | **404** | the same |
| `nc -z <ip> 3306`, `8080` | refused | neither is exposed |

Then in a browser, and this is the check that matters: **sign in as Jane** and confirm the
Asgardeo round trip returns to the instance rather than to localhost. That is the step that
proves the redirect registration, and it is the one most likely to need a second pass in the
console.

All of the above passed on 2026-08-30. Local development was re-checked at the same time: 35
frontend tests pass and a build with no environment set still bakes in `localhost:5173`.

## Where the secrets are

- The **database password** is generated on the instance and exists only in
  `/etc/altrium/altrium.env` (640, `root:altrium`). It is in no repository and on no
  workstation. `set -x` in the bootstrap echoed it into `/var/log/altrium-bootstrap.log`, so
  that file is `chmod 600`.
- The **`.pem`** lives at the project root, outside the repository, beside `users.md`.
- The Asgardeo **client ID** and issuer are public identifiers for a PKCE application and are
  committed on purpose. There is no client secret anywhere, by design.
- **`ami-0385d6c57ee804b77` contains that database password**, because it is a copy of the whole
  filesystem including `/etc/altrium/altrium.env`. Both the image and `snap-070d61394991db19d`
  are private, checked with `describe-image-attribute` and `describe-snapshot-attribute`, which
  return empty permission lists. Never share either, and never make either public: an AMI is a
  disk, and sharing one hands over every secret on it. If the image ever has to be shared, rotate
  the database password on the running instance first and take a new one.

## Cost

**This account is not on the 12-month free tier**, so the deployment is billed. Checked with
`aws freetier get-free-tier-usage`, which returned only the always-free items (Glue, KMS) and no
EC2, EBS or IPv4 allowance, while a `t3.micro` was running. Confirm in Billing and Cost
Management if it matters.

List prices, `us-east-1`, read from the AWS pricing API.

### Now, torn down

| Item | Rate | Per month |
|---|---|---|
| `snap-070d61394991db19d`, under `ami-0385d6c57ee804b77` | 0.05 USD/GB-month | **0.40 or less** |
| Security group `altrium-demo` | not billed | 0.00 |
| Key pair `altrium-demo` | not billed | 0.00 |
| **Total** | | **about 0.40 USD/month** |

0.40 is a ceiling, not a reading. It is 8 GB at the snapshot rate, 8 GB being the whole volume:
`aws ebs list-snapshot-blocks` returns all 16384 of its 512 KiB blocks, so nothing is sparse.
But AWS bills snapshots on data **after its own compression**, which the API does not expose,
and a mostly-empty Ubuntu root filesystem compresses well. Expect somewhere between 0.10 and
0.40 USD a month and read the real figure off the bill. Either way it is a few percent of what
the running deployment cost, which is the comparison the decision turned on.

### Running, for comparison

| Item | Rate | 730 hours |
|---|---|---|
| `t3.micro` on demand, Linux | 0.0104 USD/hr | 7.59 |
| 8 GB gp3 volume | 0.08 USD/GB-month | 0.64 |
| In-use public IPv4 | 0.005 USD/hr | 3.65 |
| Data transfer out | first 100 GB/month free | 0.00 |
| **Total, running continuously** | | **about 11.90 USD/month** |

Egress rounds to nothing: the whole SPA bundle is 1.3 MB, so a thousand cold page loads is
roughly 1.3 GB against a 100 GB monthly allowance.

### Why terminating beat stopping

**Stopping would not have made it free.** A stopped instance still carries its volume, and an
Elastic IP is billed whether it is attached to a stopped instance or held detached. Stopped for
a month with a few hours of demo use is roughly **4.50 USD**: 3.65 for the address, 0.64 for
the volume, and pennies of compute.

The three states are therefore about **11.90** running, **4.50** stopped, and **0.40** imaged
and gone. Termination is an order of magnitude cheaper than stopping, and the entire difference
in effort is one `run-instances`, one Asgardeo edit and one frontend rebuild. Under about six
weeks, stopping is the better trade. With no date set for the next demo, it is not.

### Restoring the account to nothing

The first block has been run. The second is held back deliberately: it deletes the image, and
with it the only copy of the deployed machine.

```bash
# run on 2026-09-01
aws ec2 terminate-instances --instance-ids i-08359c29d12ebe1e2
aws ec2 release-address --allocation-id eipalloc-0f226f3c5f5e5572b

# when the project is finished for good, and not before
aws ec2 deregister-image --image-id ami-0385d6c57ee804b77
aws ec2 delete-snapshot --snapshot-id snap-070d61394991db19d
aws ec2 delete-security-group --group-id sg-01f589131b0ff0c55
aws ec2 delete-key-pair --key-name altrium-demo
```

None of the second block is recoverable and none of what it removes is expensive to keep, so
there is no reason to run it before the grade is in. When it is run, delete `altrium-demo.pem`
at the project root at the same time: once the key pair is gone the file is a dead private key
sitting beside `users.md` for no reason.
