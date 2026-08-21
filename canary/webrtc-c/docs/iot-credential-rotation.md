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
IOT_CORE_CERT=/home/jenkins/.aws-iot/rpi5-canary_certificate.pem   # confirmed present on yuqi-pi
IOT_CORE_PRIVATE_KEY=/home/jenkins/.aws-iot/rpi5-canary_private.key
```

Confirmed on `yuqi-pi` (mi-07723b1aaa1d55ef4) via SSM on 2026-08-20 — the IoT cert chain lives
at `/home/jenkins/.aws-iot/`: `rpi5-canary_certificate.pem`, `rpi5-canary_private.key`,
`AmazonRootCA1.pem` (+ a `credhelper.sh`). The runner param defaults now point at these.

Verify on the master node before the run:
- the master log shows the IoT provider in use and a successful `JoinStorageSession`, and
  keeps streaming past the 1-h mark (the point where the static path used to die).
