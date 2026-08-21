# IoT credential rotation for long-running (>1h) storage-canary masters

## Problem

The static credential path caps a master run at **1 hour**. The Jenkins runner assumes
`Canary-STS` and passes the resulting `AWS_ACCESS_KEY_ID/SECRET/SESSION_TOKEN` to the master
as fixed-lifetime creds (the master's `createStaticCredentialProvider` is given `MAX_UINT64`
expiry — it never refreshes). Because the Jenkins host itself holds role-chained creds, the
assumed `Canary-STS` session is capped at the AWS **role-chaining hard limit of 3600s**. When
those creds expire mid-stream the master can no longer call KVS, so reconnect tests that need
to run past an hour (e.g. the 65-min camerasrc test on `002-pi`) fail.

## Fix: auto-refreshing IoT credential provider

The master now selects its credential provider **at runtime** (`Common.cpp`,
`createSampleConfiguration`): if `AWS_IOT_CORE_CREDENTIAL_ENDPOINT` is set it uses
`createLwsIotCredentialProvider` (which re-fetches creds from the IoT credentials endpoint
before they expire); otherwise it falls back to the static provider. This is opt-in per run
via the runner param `USE_IOT_CREDENTIALS=true` — the static path is unchanged for every
other scenario/node.

**Code is ready. But the IAM side below MUST be done first**, or the master gets assume-only
creds and every KVS call 401s.

## Why the code alone is not enough (current IAM state)

Inspected in account `232283333863` (`us-west-2`), read-only:

| Resource | State |
|---|---|
| IoT role-alias `rpi5-canary_role_alias` | → role `rpi5-canary-bootstrap`, `credentialDurationSeconds = 3600` |
| role `rpi5-canary-bootstrap` | one inline policy `assume-canary-sts`: `sts:AssumeRole` on `Canary-STS` **only** — no KVS/CloudWatch/logs perms |
| role `Canary-STS` | `MaxSessionDuration = 43200` (12h); managed policy `Canary-STS` v11 grants full KVS (`ConnectAsMaster`, `JoinStorageSession`, `PutMedia`, `GetDataEndpoint`, …) + CloudWatch + logs + S3 |
| `Canary-STS` trust policy | allows `SSMDefaultRoleForPVREReporting` and `rpi5-canary-bootstrap` — **not** `credentials.iot.amazonaws.com` |

The IoT credentials endpoint vends creds for **whatever role the role-alias points at**, in a
single hop — it does not chain onward. So today it would vend `rpi5-canary-bootstrap` creds,
which have no KVS permissions. (The static Jenkins path works only because Jenkins itself does
the second hop `sts:AssumeRole → Canary-STS`.)

## The exact IAM change (dedicated KVS alias)

> **Do NOT repoint the existing `rpi5-canary_role_alias`.** That alias is *also* what the Pi's
> `credhelper.sh` (an AWS CLI `credential_process` in `~/.aws/config`) uses for the node's
> *ambient* credentials, and the runner's `Fetch STS credentials` stage relies on those being
> the assume-only `bootstrap` role so it can chain `bootstrap → Canary-STS`. Pointing that alias
> at `Canary-STS` makes the ambient identity *become* `Canary-STS`, and the runner's
> `sts:AssumeRole Canary-STS` then fails with `AccessDenied` (a role cannot assume itself) —
> breaking every rpi5 canary. (Learned the hard way on 2026-08-20.) Instead, give the master's
> IoT path its **own** alias and leave the ambient alias as `bootstrap`.

**1. Let the IoT credentials service assume `Canary-STS`** — add this statement to the
`Canary-STS` trust policy (keep the existing statements):

```json
{
  "Effect": "Allow",
  "Principal": { "Service": "credentials.iot.amazonaws.com" },
  "Action": "sts:AssumeRole"
}
```

**2. Create a dedicated KVS role-alias → `Canary-STS`:**

```bash
aws iot create-role-alias \
  --role-alias rpi5-canary-kvs_role_alias \
  --role-arn arn:aws:iam::232283333863:role/Canary-STS \
  --credential-duration-seconds 43200 \
  --region us-west-2
```

**3. Allow the device cert to use the new alias** — add a new default version to the cert's
IoT policy (`rpi5-canary_policy`) listing BOTH aliases in `Resource` (the old one for the
ambient/credhelper path, the new one for the master's IoT path):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["iot:Connect", "iot:AssumeRoleWithCertificate"],
    "Resource": [
      "arn:aws:iot:us-west-2:232283333863:rolealias/rpi5-canary_role_alias",
      "arn:aws:iot:us-west-2:232283333863:rolealias/rpi5-canary-kvs_role_alias"
    ]
  }]
}
```
```bash
aws iot create-policy-version --policy-name rpi5-canary_policy \
  --policy-document file://policy.json --set-as-default --region us-west-2
```

**Result:** the ambient/credhelper alias (`rpi5-canary_role_alias` → `bootstrap`, unchanged)
keeps the runner's two-hop `bootstrap → Canary-STS` working for all existing canaries. The
master with `USE_IOT_CREDENTIALS=true` uses `rpi5-canary-kvs_role_alias`, which vends
`Canary-STS` directly (full KVS) and the SDK auto-refreshes it → no 3600s cap → reconnect
tests > 1h work. The two paths no longer share an alias.

`43200` on the new alias is just how often the provider refreshes, not a run cap (it
auto-refreshes regardless); the only hard requirement is that the alias vends a KVS-capable
role.

## Rollback

Delete the dedicated alias and revert the cert policy to its single-alias version; the ambient
alias was never changed, so nothing else needs reverting:

```bash
aws iot update-role-alias --role-alias rpi5-canary_role_alias \
  --role-arn arn:aws:iam::232283333863:role/rpi5-canary-bootstrap \
  --credential-duration-seconds 3600 --region us-west-2   # confirm it still points at bootstrap
aws iot set-default-policy-version --policy-name rpi5-canary_policy --policy-version-id 1 --region us-west-2
aws iot delete-role-alias --role-alias rpi5-canary-kvs_role_alias --region us-west-2
```
(The `credentials.iot.amazonaws.com` statement on `Canary-STS` trust is harmless to leave.)

## Running a >1h test after the IAM change

Set on the job (defaults already point at the confirmed endpoint + alias):

```
USE_IOT_CREDENTIALS=true
DURATION_IN_SECONDS=3900          # 65 min
IOT_CORE_THING_NAME=rpi5-002_thing      # REQUIRED, per-Pi (see below) -- NOT the channel name
IOT_CORE_CERT=/home/jenkins/.aws-iot/rpi5-canary_certificate.pem
IOT_CORE_PRIVATE_KEY=/home/jenkins/.aws-iot/rpi5-canary_private.key
```

### Per-Pi gotchas (learned validating on rpi5-002, 2026-08-20)

1. **Device cert filename differs per Pi.** `yuqi-pi` uses `rpi5-canary_certificate.pem` /
   `rpi5-canary_private.key`; `rpi5-002` uses `certificate.pem` / `private.key`. The runner
   `IOT_CORE_CERT`/`IOT_CORE_PRIVATE_KEY` defaults use the `rpi5-canary_*` names, so on
   `rpi5-002` we added symlinks so the default resolves (additive, does not touch the real
   files or the credhelper):
   ```bash
   sudo -u jenkins ln -sfn /home/jenkins/.aws-iot/certificate.pem /home/jenkins/.aws-iot/rpi5-canary_certificate.pem
   sudo -u jenkins ln -sfn /home/jenkins/.aws-iot/private.key     /home/jenkins/.aws-iot/rpi5-canary_private.key
   ```
   Do this on any new Pi whose cert uses the short names (or set the two params per run).

2. **`IOT_CORE_THING_NAME` is required and per-Pi.** The credentials request sends it as
   `x-amzn-iot-thingname`; it must be a thing the device cert is attached to (else the endpoint
   returns **403**). It is NOT the channel name. It differs per Pi — find it in that node's
   `~/.aws-iot/credhelper.sh` (`grep x-amzn-iot-thingname`): `rpi5-002_thing` on rpi5-002,
   `rpi5-canary_thing` on yuqi-pi. A missing value **segfaults** (the LWS provider does not
   NULL-check it), so the master fails fast with "AWS_IOT_CORE_THING_NAME must be set".

Verified end-to-end on `rpi5-002` (mi-0039bcbda0edd3f24): with the correct thing the endpoint
returns 200, vends `Canary-STS` (12h), the master signs KVS with it and connects signaling.

Verify on the master node before the run:
- the master log shows `Connected with server response: 200` (not 403) on the credentials
  fetch, IoT creds in use, and keeps streaming past the 1-h mark (where the static path died).
