# Open5GS Swap Events

Keeps the `swap_events` collection in the Open5GS MongoDB in sync with the `subscribers` collection. Each row records the current IMSI and IMEISV of an MSISDN and when each last changed. `open5gs-em` reads it to answer the SIM-swap and device-swap checks.

Every `POLL_INTERVAL` (30s by default) the service reads every subscriber, compares it with its `swap_events` row, and writes only the rows that changed. The first pass creates a row for every existing subscriber. There is no change stream and no checkpoint, so any MongoDB deployment works, standalone included.

## The `swap_events` contract

`open5gs-em` reads this schema, so it must not change.

| Field | Type | Meaning |
|---|---|---|
| `_id` | string | The MSISDN: the first entry of the subscriber's `msisdn` list |
| `imsi` | string | The subscriber's IMSI |
| `imeisv` | string | The last non-empty IMEISV, or `""` if none was ever seen |
| `lastUpdatedImsi` | date | When the IMSI last changed |
| `lastUpdatedImeisv` | date | When the IMEISV last changed |
| `_class` | string | Spring Data type hint |

Rules:
- A new row gets the subscriber's creation time, taken from its `_id` ObjectId, for both timestamps.
- A changed IMSI or IMEISV moves only its own timestamp.
- An empty IMEISV never clears the stored one.
- Subscribers without an MSISDN or IMSI are skipped.
- When several subscribers share an MSISDN, the newest one (highest `_id`) owns the row. This happens, for example, when a SIM swap is provisioned as a new subscriber before the old one is deleted.
- Deleted subscribers keep their row; `open5gs-em` checks that the subscriber exists first.

## Timestamp accuracy

A change is stamped with the time a pass saw it, not the time it happened:
- It is late by at most one poll interval plus the pass duration, and never early.
- Changes made while the service is down are stamped with the time of the first pass after it restarts.
- A change that is undone within one interval is never seen.

That is fine for checks that ask "was there a swap in the last N hours", with up to tens of thousands of subscribers. Exact change times, or millions of subscribers, would need a MongoDB change stream instead. That needs a replica set and a persisted resume token, but it replays changes missed during a restart with their real times. The models and `SwapCheckUpdater` would carry over unchanged.

## MongoDB prerequisites

Any MongoDB deployment, standalone included. The service's user needs `find` on `subscribers`, and read and write on `swap_events`:

```js
use open5gs
db.createRole({
  role: "swapEvents",
  privileges: [
    { resource: { db: "open5gs", collection: "subscribers" }, actions: ["find"] },
    { resource: { db: "open5gs", collection: "swap_events" },
      actions: ["find", "insert", "update", "createCollection"] }
  ],
  roles: []
})
db.createUser({ user: "swap-events", pwd: passwordPrompt(), roles: ["swapEvents"] })
```

## Install

```bash
helm install swap-events oci://registry-1.docker.io/pnetrepo/open5gs-swap-events-chart \
  --version X.Y.Z \
  --set mongodb.uri=mongodb://<host>:27017/open5gs
```

To keep credentials out of the configmap, leave `mongodb.uri` empty and provide `MONGODB_URI` from a Secret:

```yaml
extraEnvFrom:
  - secretRef:
      name: open5gs-mongodb-uri   # has a MONGODB_URI key
```

`mongodb.uri` may be a template, e.g. `mongodb://{{ .Release.Name }}-mongodb:27017/open5gs`. The [open5gs-em-stack](https://github.com/pnet-software/open5gs-em-stack) chart deploys this chart next to Open5GS that way.

The chart always runs exactly one replica. See [values.yaml](charts/open5gs-swap-events/values.yaml) for the rest: image, resources, probes, security context, `serviceMonitor.enabled`, scheduling.

## Configuration

| Environment variable | Helm value | Default | Meaning |
|---|---|---|---|
| `MONGODB_URI` | `mongodb.uri` | required | Connection string including the Open5GS database, e.g. `mongodb://mongodb:27017/open5gs` |
| `POLL_INTERVAL` | `config.pollInterval` | `30s` | Time between the end of one pass and the start of the next |
| `STALL_TIMEOUT` | `config.stallTimeout` | `10m` | Liveness fails if no pass has finished for this long |
| `SHUTDOWN_TIMEOUT` | `config.shutdownTimeout` | `20s` | Limit for each shutdown phase |
| `LOG_FORMAT` | `config.logFormat` | `ecs` | Structured console logs: `ecs`, `logstash` or `gelf`. Empty for plain text |
| `LOG_LEVEL` | `config.logLevel` | `INFO` | Root log level. Quiet passes log at `DEBUG` |
| `JAVA_TOOL_OPTIONS` | `config.javaToolOptions` | `-XX:MaxRAMPercentage=75` | JVM options |
| `SERVER_PORT` | (fixed) | `8080` | Actuator port |

`config.extra`, `extraEnv` and `extraEnvFrom` add more environment variables.

## Health and metrics

Actuator is on port 8080:
- `/actuator/health/liveness` includes the poller. It is DOWN when no pass has finished, with any result, within `STALL_TIMEOUT`. A failed pass still counts as finished, so a Mongo outage doesn't restart the pod.
- `/actuator/health/readiness` includes Mongo.
- `/actuator/health` shows the details: last result, last success time, subscribers seen, last duration.
- `/actuator/prometheus`:

| Metric | Meaning |
|---|---|
| `swapchecks_changes_total{outcome="created\|updated"}` | Rows written |
| `swapchecks_skipped_total{reason="missing_field\|duplicate_msisdn"}` | Subscribers skipped, counted on every pass |
| `swapchecks_poll_seconds_count/sum/max{result="success\|failed\|aborted"}` | Passes and their duration |
| `swapchecks_last_success_time` | Epoch seconds when the last successful pass finished, 0 before the first |
| `swapchecks_subscribers` | Subscribers seen in the last successful pass |

Suggested alert: `time() - swapchecks_last_success_time > 5 * <poll interval in seconds>`.

## Runbook

- **Run a single instance.** More replicas only repeat the same work. Two pods overlapping during a rolling update is harmless.
- **Mongo outage**: passes fail after the driver's 30s server-selection timeout and are retried every interval. Readiness goes DOWN and liveness stays UP. Passes resume on their own once Mongo is back.
- **Nothing to reset**: every pass is a full compare, so there is no checkpoint.
- **Rebuilding `swap_events`**: deleting the collection rebuilds it on the next pass. That resets every timestamp to the subscriber's creation time and erases the swap history, so avoid it.
- **Cutting a release**: push a `vX.Y.Z` tag. CI then:
  - tests the code,
  - pushes the image `docker.io/pnetrepo/open5gs-swap-events:vX.Y.Z` (also tagged with the short SHA and `latest`),
  - pushes the chart `oci://registry-1.docker.io/pnetrepo/open5gs-swap-events-chart`, version `X.Y.Z`,
  - creates the GitHub Release `vX.Y.Z`, with notes generated from the commits.

## Development

Requires JDK 17+.

```bash
./mvnw verify                                          # unit tests + integration tests + JaCoCo report
TEST_MONGODB_URI=mongodb://localhost:27017 ./mvnw verify  # integration tests against an existing Mongo (what CI does)
./mvnw package jib:dockerBuild                         # build the image into the local Docker daemon
MONGODB_URI=mongodb://localhost:27017/open5gs LOG_FORMAT= ./mvnw spring-boot:run
```

Without `TEST_MONGODB_URI`, the integration tests start a `mongo:7` container with Testcontainers, which needs Docker. Each test class uses its own random database and drops it afterwards.

CI is GitHub Actions ([ci.yml](.github/workflows/ci.yml)):
- `test` runs Mongo as a service container (`TEST_MONGODB_URI`).
- Jib builds and pushes the image to Docker Hub without a Docker daemon.
- `helm-lint` and `helm-publish` use Helm; the chart is pushed to `oci://registry-1.docker.io/pnetrepo`.

The release jobs need the organization secrets `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN`, and the variable `DOCKERHUB_NAMESPACE` (`pnetrepo`).

## License

Copyright 2026 P-NET New Generation Emerging Networks & Verticals Private Company.

Licensed under the [Apache License, Version 2.0](LICENSE). You may use, modify and redistribute this work, provided you keep the copyright and license notices and include the [NOTICE](NOTICE) file in what you distribute.
