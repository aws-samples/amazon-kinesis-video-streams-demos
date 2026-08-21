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

## The exact IAM change (Option A — recommended)

Repoint the role-alias directly at `Canary-STS`, which already has exactly the right perms and
a 12h max session.

**1. Let the IoT credentials service assume `Canary-STS`** — add this statement to the
`Canary-STS` trust policy (keep the two existing statements):

```json
{
  "Effect": "Allow",
  "Principal": { "Service": "credentials.iot.amazonaws.com" },
  "Action": "sts:AssumeRole"
}
```

**2. Repoint the role-alias and raise the duration past 1h:**

```bash
aws iot update-role-alias \
  --role-alias rpi5-canary_role_alias \
  --role-arn arn:aws:iam::232283333863:role/Canary-STS \
  --credential-duration-seconds 43200 \
  --region us-west-2
```

`43200` is valid: it is ≤ `Canary-STS` `MaxSessionDuration` (43200) and ≤ the IoT role-alias
max (43200). The SDK's IoT provider auto-refreshes before expiry, so even a 12-h ceiling is
just "how often it refreshes," not a run cap.

**Result:** the endpoint vends `Canary-STS` creds directly (full KVS), auto-refreshed → no
3600s cap → reconnect tests > 1h work. `rpi5-canary-bootstrap` + its `assume-canary-sts`
inline policy become vestigial for the IoT path (leave them — the static Jenkins path still
uses `Canary-STS` via chaining).

### Option B (lower blast radius, more setup)

Instead of touching the shared `Canary-STS` trust, create a **new** role-alias pointing at a
new role that (a) trusts `credentials.iot.amazonaws.com` and (b) attaches the `Canary-STS`
managed policy. Then set `IOT_CORE_ROLE_ALIAS` to the new alias. Prefer Option A unless you
want to avoid adding a principal to `Canary-STS`.

## Rollback

```bash
aws iot update-role-alias --role-alias rpi5-canary_role_alias \
  --role-arn arn:aws:iam::232283333863:role/rpi5-canary-bootstrap \
  --credential-duration-seconds 3600 --region us-west-2
```
and remove the `credentials.iot.amazonaws.com` statement from the `Canary-STS` trust policy.

## Running a >1h test after the IAM change

Set on the job (defaults already point at the confirmed endpoint + alias):

```
USE_IOT_CREDENTIALS=true
DURATION_IN_SECONDS=3900          # 65 min
IOT_CORE_CERT=/home/jenkins/.aws-iot/rpi5-canary_certificate.pem   # confirmed present on yuqi-pi
IOT_CORE_PRIVATE_KEY=/home/jenkins/.aws-iot/rpi5-canary_private.key
```

Confirmed on `yuqi-pi` (mi-07723b1aaa1d55ef4) via SSM on 2026-08-20 — the IoT cert chain lives
at `/home/jenkins/.aws-iot/`: `rpi5-canary_certificate.pem`, `rpi5-canary_private.key`,
`AmazonRootCA1.pem` (+ a `credhelper.sh`). The runner param defaults now point at these.

Verify on the master node before the run:
- the master log shows the IoT provider in use and a successful `JoinStorageSession`, and
  keeps streaming past the 1-h mark (the point where the static path used to die).
