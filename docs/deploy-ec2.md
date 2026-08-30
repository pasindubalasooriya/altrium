# Deploying the demo to EC2

One free-tier instance running the backend, the database and the frontend, served over plain
HTTP. **Demo-grade on purpose:** no load balancer, no RDS, no TLS, no autoscaling. Nothing in
this project is demonstrated by infrastructure, and the graded core is the authorization model.

Live at **http://ec2-35-173-168-105.compute-1.amazonaws.com**

## What is on the box

```
Ubuntu 24.04 LTS, t3.micro, 1 GB, 8 GB gp3, us-east-1

  nginx        :80    public     SPA from /var/www/altrium
                                 /api/ proxied to 8080
                                 swagger paths return 404
  altrium.jar  :8080  loopback   systemd unit, SERVER_ADDRESS=127.0.0.1
  mysql 8.0    :3306  loopback   bind-address 127.0.0.1

  security group  sg-01f589131b0ff0c55
                  80 from 0.0.0.0/0
                  22 from the operator's IP only
  elastic ip      eipalloc-0f226f3c5f5e5572b  35.173.168.105
  instance        i-08359c29d12ebe1e2
  key pair        altrium-demo (ed25519)
```

Two decisions carry most of the weight.

**One nginx serves both halves**, so the SPA and the API are the **same origin**. The backend's
CORS configuration therefore never comes into play in the deployment, and only port 80 is open.
Development is deliberately the opposite - two origins, real cross-origin calls, no Vite proxy -
because that is what keeps the CORS path honest rather than hiding a misconfiguration until the
first deploy.

**Nothing is compiled on the instance.** The jar and the SPA bundle are built on a workstation
and copied up. Maven and Vite on a 1 GB box is how a demo gets lost, and it also means the
instance needs no Node, no Maven and no git.

## The Elastic IP is not optional

Asgardeo refuses a login whose redirect URL it has not registered, and an auto-assigned public
address changes on every stop/start. Each change would silently break login until somebody
edited the Asgardeo console again. The Elastic IP fixes the hostname, so the registration is
done once.

The address is billed while the instance is **stopped**, so stopping between sessions does not
make the deployment free. See the cost note at the end.

## Asgardeo configuration

Registered on the application in the Asgardeo console, in **both** fields:

| Field | Values |
|---|---|
| Authorized redirect URLs | `http://localhost:5173` and `http://ec2-35-173-168-105.compute-1.amazonaws.com` |
| Allowed origins | the same two |

Both are kept. Removing the localhost entry would break local development; removing the
instance entry breaks the demo. Neither interferes with the other.

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

## Building and deploying

Both artifacts are built locally. The frontend needs two variables at **build** time - they are
compiled into the bundle, not read at runtime:

```bash
cd altrium/backend
./mvnw.cmd -DskipTests package          # target/altrium-backend-0.1.0-SNAPSHOT.jar

cd ../frontend
VITE_APP_ORIGIN="http://ec2-35-173-168-105.compute-1.amazonaws.com" \
VITE_API_BASE_URL="http://ec2-35-173-168-105.compute-1.amazonaws.com" \
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
    /tmp/dist.tgz altrium.service altrium.nginx ubuntu@35.173.168.105:/tmp/

ssh -i altrium-demo.pem ubuntu@35.173.168.105 '
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
for anything else. Moving to TLS means a hostname Let's Encrypt will issue for - an `sslip.io`
name against the Elastic IP is the cheapest - a certificate, and re-registering the new origin
in Asgardeo.

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

## Cost, and turning it off

**This account is not on the 12-month free tier**, so the deployment is billed. Checked with
`aws freetier get-free-tier-usage`, which returned only the always-free items (Glue, KMS) and no
EC2, EBS or IPv4 allowance, while a `t3.micro` was running. Confirm in Billing and Cost
Management if it matters.

List prices, `us-east-1`, read from the AWS pricing API:

| Item | Rate | 730 hours |
|---|---|---|
| `t3.micro` on demand, Linux | 0.0104 USD/hr | 7.59 |
| 8 GB gp3 volume | 0.08 USD/GB-month | 0.64 |
| In-use public IPv4 | 0.005 USD/hr | 3.65 |
| Data transfer out | first 100 GB/month free | 0.00 |
| **Total, running continuously** | | **about 11.90 USD/month** |

Egress rounds to nothing: the whole SPA bundle is 1.3 MB, so a thousand cold page loads is
roughly 1.3 GB against a 100 GB monthly allowance.

**Stopping the instance between demos does not make it free.** The volume is still billed, and
so is the address - an Elastic IP is charged whether it is attached to a stopped instance or
detached entirely. Stopped for a whole month with a few hours of demo use, expect roughly
**4.50 USD**: 3.65 for the address, 0.64 for the volume, and pennies of compute.

Releasing the address is the only way to stop that charge, and it breaks the registered
Asgardeo redirect URL. Below about six weeks it is cheaper in effort than in dollars to leave it
allocated.

Full teardown, once the assessment is done:

```bash
aws ec2 terminate-instances --instance-ids i-08359c29d12ebe1e2
aws ec2 release-address --allocation-id eipalloc-0f226f3c5f5e5572b
aws ec2 delete-security-group --group-id sg-01f589131b0ff0c55
aws ec2 delete-key-pair --key-name altrium-demo
```

Release the address last, and remember it invalidates the Asgardeo redirect URL - a rebuilt
demo gets a new hostname and needs the console updated again.
