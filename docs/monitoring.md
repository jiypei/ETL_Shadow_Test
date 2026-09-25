# Monitoring: JVM and DuckDB CPU and memory

The service exposes Prometheus metrics at `GET /actuator/prometheus`. Like `/actuator/health` it is outside `/api/**`, so it needs no token; keep it reachable only inside the cluster (no Ingress route). Every series carries `application="etl-shadow-test"`.

Why the extra DuckDB metrics: DuckDB runs embedded in the JVM process but allocates off-heap, so the JVM metrics never show it, and the container memory limit (`docs/operations.md`, Deployment) is JVM heap plus DuckDB. Without them an OOM-kill cannot be traced to either side.

## Metrics

| Metric (Prometheus name) | Labels | Meaning |
| --- | --- | --- |
| `jvm_memory_used_bytes`, `_committed_bytes`, `_max_bytes` | `area` (`heap`, `nonheap`), `id` (pool) | JVM memory, standard Micrometer. |
| `jvm_gc_pause_seconds_*`, `jvm_threads_live_threads` | | GC pauses and thread count. |
| `process_cpu_usage` | | CPU of the whole process, 0 to 1 of all cores (`system_cpu_count`). Includes DuckDB. |
| `shadow_process_cpu_time_seconds_total` | `part` | Counter of process CPU seconds, split: `test-runs` (Test Run threads, which also do the DuckDB work they call), `native` (threads the JVM does not see: DuckDB's worker threads, plus GC and JIT), `other-java` (HTTP, heartbeat, sweeps, webhooks). The three add up to (at least) the process total; `native` is the remainder and never decreases. |
| `duckdb_instances` | `role` | Open DuckDB instances. `test-run` equals the number of running Test Runs that have started their DuckDB work. |
| `duckdb_memory_used_bytes` | `role` | Memory held by DuckDB's buffer manager (from `duckdb_memory()`), summed over instances of the role. |
| `duckdb_memory_limit_bytes` | `role` | Sum of `memory_limit` of the open instances. DuckDB reports limits in MiB: `1GB` shows as 953.6 MiB. |
| `duckdb_temporary_storage_bytes` | `role` | Bytes spilled to `shadow.duckdb.temp-directory`. |

Roles: `test-run` (one per Test Run, `shadow.duckdb.memory-limit` each), `history` (one, 256MB, created on the first history query or abandoned-run sweep), `parquet-schema` (one per Environment, 256MB, created on the first Parquet Target).

Limits of the numbers: DuckDB CPU cannot be isolated exactly, because its work shares threads with the JVM. Use `test-runs + native` as "comparison work" and note that `native` also holds GC. `duckdb_memory_used_bytes` counts what DuckDB's buffer manager tracks; small allocations outside it are not included, so leave headroom as operations.md says.

## Configuration

Already in this repository:

- `build.gradle.kts`: `micrometer-registry-prometheus` (Actuator was already there).
- `application.yml`: `management.endpoints.web.exposure.include: health,prometheus` and the `application` tag.

To scrape it, pick the option matching your cluster.

With the Prometheus Operator (kube-prometheus-stack), give the Service a named port and add a ServiceMonitor:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: etl-shadow-test
  labels: { app: etl-shadow-test }
spec:
  selector: { app: etl-shadow-test }
  ports:
    - name: http
      port: 8080
      targetPort: 8080
---
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: etl-shadow-test
  labels:
    release: kube-prometheus-stack   # must match your Prometheus' serviceMonitorSelector
spec:
  selector:
    matchLabels: { app: etl-shadow-test }
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 15s
```

With a plain Prometheus using annotation discovery, annotate the pod template instead:

```yaml
metadata:
  annotations:
    prometheus.io/scrape: "true"
    prometheus.io/path: /actuator/prometheus
    prometheus.io/port: "8080"
```

Check it: `kubectl port-forward deploy/etl-shadow-test 8080` then `curl -s localhost:8080/actuator/prometheus | grep -E '^(duckdb|shadow)_'`. In Prometheus, *Status > Targets* should list the pod as UP.

The container panels of the dashboard also use `container_memory_working_set_bytes` (cAdvisor, scraped by kube-prometheus-stack by default) and `kube_pod_container_resource_limits` (kube-state-metrics). Without them those two lines stay empty; everything else still works.

## Grafana dashboard

Import the ready-made dashboard `docs/grafana/etl-shadow-test.json`:

1. Grafana > *Dashboards* > *New* > *Import* > *Upload dashboard JSON file*, choose the file.
2. Select your Prometheus data source when asked, then *Import*.
3. At the top, set *Instance* (defaults to all) and *Kubernetes namespace* (a regex, `.*` by default) to the namespace the service runs in.

To provision it instead (Grafana Helm chart or kube-prometheus-stack sidecar), put the JSON in a ConfigMap labelled `grafana_dashboard: "1"`:

```sh
kubectl create configmap etl-shadow-test-dashboard -n monitoring --from-file=docs/grafana/etl-shadow-test.json
kubectl label configmap etl-shadow-test-dashboard -n monitoring grafana_dashboard=1
```

Panels and their queries, if you build it by hand:

| Panel | PromQL |
| --- | --- |
| Running Test Runs | `sum(duckdb_instances{role="test-run"})` |
| CPU by part (cores, stacked) | `sum by (part) (rate(shadow_process_cpu_time_seconds_total[$__rate_interval]))` |
| Process CPU vs. cores | `sum(process_cpu_usage * system_cpu_count)` and `sum(system_cpu_count)` |
| Process memory (stacked) | `sum(jvm_memory_used_bytes{area="heap"})`, `sum(jvm_memory_used_bytes{area="nonheap"})`, `sum by (role) (duckdb_memory_used_bytes)`; as lines, `container_memory_working_set_bytes` and `kube_pod_container_resource_limits{resource="memory"}` |
| JVM heap | `jvm_memory_used_bytes`, `jvm_memory_committed_bytes`, `jvm_memory_max_bytes` with `area="heap"` |
| DuckDB used vs. limit | `sum by (role) (duckdb_memory_used_bytes)` and `sum by (role) (duckdb_memory_limit_bytes)` |
| DuckDB spill | `sum by (role) (duckdb_temporary_storage_bytes)` |
| Threads and GC | `jvm_threads_live_threads`, `rate(jvm_gc_pause_seconds_count[$__rate_interval])` |

Add `{application="etl-shadow-test", instance=~"$instance"}` to each service metric (the imported dashboard does).

## Suggested alerts

| Alert | Expression | Why |
| --- | --- | --- |
| Close to the container memory limit | `sum(container_memory_working_set_bytes{pod=~"etl-shadow-test.*", container!=""}) / sum(kube_pod_container_resource_limits{pod=~"etl-shadow-test.*", resource="memory"}) > 0.9` for 5m | An OOM-kill abandons every running Test Run (reported ERROR). |
| Heap nearly full | `sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"}) > 0.9` for 10m | |
| DuckDB spilling a lot | `sum(duckdb_temporary_storage_bytes{role="test-run"}) > <80% of the temp volume>` | Spilling is allowed, but a full temp volume fails the Row Diff with ERROR. |
| CPU saturated | `sum(process_cpu_usage * system_cpu_count) / sum(system_cpu_count) > 0.9` for 15m | Queries slow down towards `query-timeout`. |
