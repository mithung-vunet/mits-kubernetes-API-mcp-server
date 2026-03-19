# mits-kubernetes-API-mcp-server

A **Java 17** implementation of the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) that gives AI assistants (GitHub Copilot, Claude) **live, read/write access to any Kubernetes cluster** via the cluster's own REST API — no `kubectl` binary required on the host, no hardcoded resource lists, full CRD support out of the box.

---

## Demo — What You Can Ask Copilot

> *All of the following were answered live against a real cluster using this server:*

| Question asked to Copilot | Tool used |
|---|---|
| *"What Kubernetes version is the cluster running?"* | `get_cluster_info` |
| *"How many namespaces are running?"* | `list_resources` → NamespaceList |
| *"Show me pod details for the ibmb namespace"* | `list_resources` → PodList |
| *"Why is payment-switch-deployment crashing with so many restarts?"* | `describe_resource` + `get_pod_logs` |
| *"Generate a restart timeseries chart for payment-switch"* | `list_resources` → chart generated |

**Root cause discovered in seconds:** `payment-switch` was panicking at startup because IBM MQ (`MQRC_HOST_NOT_AVAILABLE`) was unreachable — 22 pods failed over 29 days, each crashing 2–3 times before Kubernetes stopped retrying. The MCP server surfaced the crash log and full pod history instantly.

---

## Architecture

```
┌─────────────────────────────────────┐        ┌──────────────────────────────┐
│  VS Code / Claude Desktop           │        │  Kubernetes Cluster          │
│                                     │        │                              │
│  ┌──────────────┐   MCP (stdio)     │        │  ┌──────────────────────┐    │
│  │ GitHub       │ ──────────────►   │        │  │  kube-apiserver      │    │
│  │ Copilot      │                   │  HTTPS │  │  :6443               │    │
│  └──────────────┘  DynamicMcpServer ├───────►│  └──────────────────────┘    │
│                    (Java fat JAR)   │        │                              │
│                    reads kubeconfig │        │  Bearer token auth (SA)      │
└─────────────────────────────────────┘        └──────────────────────────────┘
```

- The JAR reads `~/.kube/config` at startup to get the cluster URL and bearer token
- Every tool call proxies directly to the Kubernetes REST API using OkHttp with a Bearer token interceptor
- Responses are returned as structured JSON to the AI assistant

---

## Project Structure

```
mits-kubernetes-API-mcp-server/
├── dynamic-api/
│   ├── src/main/java/com/kubernetes/mcp/
│   │   └── DynamicMcpServer.java            ← All 17 tools, ~950 lines
│   ├── target/
│   │   └── kubernetes-dynamic-mcp-server-1.0.0.jar  ← Pre-built fat JAR (~40 MB)
│   ├── pom.xml
│   ├── mvnw.cmd / mvnw                      ← Maven wrapper (build from source if needed)
│   ├── build.bat                            ← Windows one-click build
│   └── build.sh                             ← Linux/macOS one-click build
├── docs/
│   ├── connect-cluster-to-mcp.md            ← Full connectivity guide
│   └── single-node-kubernetes-ubuntu.md     ← kubeadm cluster setup guide
├── .vscode/
│   └── mcp.json                             ← VS Code MCP config (copy & edit path)
└── README.md
```

---

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Java JRE | 17+ | [Microsoft Build of OpenJDK](https://learn.microsoft.com/en-us/java/openjdk/download) — only the **JRE** (runtime) is needed to run the pre-built JAR |
| Kubernetes cluster | 1.24+ | Local or remote — see [single-node setup guide](docs/single-node-kubernetes-ubuntu.md) |
| Maven *(optional)* | 3.8+ | Only needed if you want to **build from source** — `mvnw.cmd` wrapper is included |

---

## Quick Start

### Step 1 — Get the JAR

**Option A — Download the pre-built JAR (recommended):**

The fat JAR is included in this repo at:
```
dynamic-api/target/kubernetes-dynamic-mcp-server-1.0.0.jar
```
Clone the repo or download the JAR directly:
```bash
git clone https://github.com/mithung-vunet/mits-kubernetes-API-mcp-server.git
```
The JAR is ~40 MB and contains all dependencies — **no Maven, no build step required**.

**Option B — Build from source:**

```bat
# Windows
cd dynamic-api && mvnw.cmd clean package -DskipTests
```
```bash
# Linux / macOS
cd dynamic-api && chmod +x build.sh && ./build.sh
```

---

### Step 2 — Connect to Your Kubernetes Cluster

> Full guide with troubleshooting: [docs/connect-cluster-to-mcp.md](docs/connect-cluster-to-mcp.md)

#### 2a. On the Kubernetes node — create a service account

```bash
# Create a dedicated service account for MCP
kubectl create serviceaccount mcp-admin -n kube-system

# Grant cluster-admin access
kubectl create clusterrolebinding mcp-admin-binding \
  --clusterrole=cluster-admin \
  --serviceaccount=kube-system:mcp-admin

# Generate a long-lived token (1 year)
kubectl create token mcp-admin -n kube-system --duration=8760h
# ↑ Copy the printed token — you'll need it in the next step
```

#### 2b. On Windows — write the kubeconfig file

Open PowerShell and paste (substituting your values):

```powershell
$token = "<PASTE_TOKEN_FROM_ABOVE>"
$clusterIP = "<YOUR_CLUSTER_EXTERNAL_IP>"   # e.g. 103.65.21.134

$kubeconfig = @"
apiVersion: v1
kind: Config
clusters:
- cluster:
    insecure-skip-tls-verify: true
    server: https://${clusterIP}:6443
  name: kubernetes
contexts:
- context:
    cluster: kubernetes
    user: mcp-admin
  name: mcp-admin@kubernetes
current-context: mcp-admin@kubernetes
users:
- name: mcp-admin
  user:
    token: ${token}
"@

New-Item -ItemType Directory -Force -Path "$HOME\.kube" | Out-Null
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText("$HOME\.kube\config", $kubeconfig, $utf8NoBom)
```

> **Why `insecure-skip-tls-verify`?** The API server TLS certificate is typically issued only for the internal IP. Using `insecure-skip-tls-verify: true` skips cert validation for the external IP. For production, see [Production TLS Fix](#production-tls-fix).

#### 2c. Verify the connection

```powershell
kubectl get nodes
# NAME            STATUS   ROLES                  AGE   VERSION
# mithun-2-vm     Ready    control-plane,master   43d   v1.27.4
```

---

### Step 3 — Configure VS Code MCP

Create or update `.vscode/mcp.json` in your workspace root:

```json
{
  "servers": {
    "kubernetes-dynamic": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "C:\\path\\to\\mits-kubernetes-API-mcp-server\\dynamic-api\\target\\kubernetes-dynamic-mcp-server-1.0.0.jar"
      ]
    }
  }
}
```

> **Tip:** Update the path to match where you cloned the repo. Use `\\` (double backslash) on Windows.

Restart VS Code — the server `kubernetes-dynamic` will appear in GitHub Copilot's MCP tool list.

---

### Step 4 — Configure Claude Desktop (optional)

**Windows** (`%APPDATA%\Claude\claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "kubernetes-dynamic": {
      "command": "java",
      "args": [
        "-jar",
        "C:\\path\\to\\kubernetes-dynamic-mcp-server-1.0.0.jar"
      ]
    }
  }
}
```

**macOS / Linux** (`~/.config/claude/claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "kubernetes-dynamic": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/kubernetes-dynamic-mcp-server-1.0.0.jar"
      ]
    }
  }
}
```

---

## Available Tools (17 total)

### Generic API Tools — work with ANY Kubernetes resource including CRDs

| Tool | Description |
|---|---|
| `discover_api_resources` | Enumerate every resource type the cluster knows about (core + all API groups + CRDs) |
| `list_resources` | `GET /api(s)/{g}/{v}[/namespaces/{ns}]/{resource}` — list any resource type |
| `get_resource` | Get a single resource by name |
| `get_subresource` | Get a subresource e.g. `pods/log`, `pods/status`, `deployments/scale` |
| `create_resource` | Create a resource from a JSON body |
| `replace_resource` | Full update (PUT) of a resource |
| `patch_resource` | JSON merge patch a resource |
| `apply_resource` | Server-Side Apply a resource manifest |
| `delete_resource` | Delete a resource by name |

### High-Level Convenience Tools

| Tool | Description |
|---|---|
| `get_pod_logs` | Rich log retrieval — container selection, previous instance, timestamps, tailLines, sinceSeconds |
| `exec_in_pod` | Execute a command inside a running container, return stdout/stderr |
| `describe_resource` | Resource + related Events in one call — equivalent to `kubectl describe` |
| `top_pods` | CPU/memory usage for pods (requires metrics-server) |
| `top_nodes` | CPU/memory usage for nodes (requires metrics-server) |
| `run_pod` | Quickly create a pod from an image name, optionally expose via ClusterIP Service |
| `get_cluster_info` | Cluster version, API server URL, platform, Go version |
| `rollout_restart` | Rolling restart of a Deployment, StatefulSet, or DaemonSet |

---

## Real-World Use Cases

These were solved live against a real cluster during development:

### 1. Cluster Health Check
```
Copilot: "What Kubernetes version is the cluster running?"
→ get_cluster_info → v1.27.4, linux/amd64, built 2023-07-19
```

### 2. Namespace Inventory
```
Copilot: "How many namespaces are running and what are they?"
→ list_resources(namespaces) → 12 namespaces:
   cert-manager, default, ibmb, kube-flannel, kube-node-lease,
   kube-public, kube-system, kurl, longhorn-system, manojtest,
   vu-instrumentation-crd, vu-operator
```

### 3. Pod Health Audit — ibmb namespace
```
Copilot: "Show me pod details for the ibmb namespace"
→ list_resources(pods, ibmb) → 88 pods across 13 deployments
   Key findings:
   • healthbeat-k8s-master: 8/9 pods Failed, up to 380 restarts ⚠️
   • payment-switch-deployment: 22/23 pods Failed ⚠️
   • ibmb-ib: 2/3 pods Failed ⚠️
```

### 4. Root Cause Analysis — payment-switch CrashLoopBackOff
```
Copilot: "Why is payment-switch-deployment crashing with so many restarts?"
→ describe_resource(payment-switch-deployment-...) + get_pod_logs(previous=true)
→ Root cause found instantly:

   panic: MQRC_HOST_NOT_AVAILABLE [2538]
   MQCONNX: MQCC_FAILED — MQ_HOST=ibm-mq:1414

   The container connects to IBM MQ on startup. When the MQ pod
   is unavailable, payment-switch panics and crashes immediately.
   Kubernetes respawned a new pod daily for 29 days — 22 pods,
   44 total restarts, all for the same root cause.

Fix: Add startup retry with backoff instead of hard panic on MQ connect failure.
```

---

## How Auth Works (Technical Detail)

The Kubernetes Java SDK applies authentication at the *API-call level* (via `Authentication.applyToParams()`), not as an OkHttp network interceptor. This means that reusing `ApiClient.getHttpClient()` directly sends requests **without** an `Authorization` header, resulting in anonymous `403` errors.

This server fixes that by:
1. Reading the kubeconfig file directly using SnakeYAML (already a transitive dependency)
2. Walking `current-context → context.user → users[].user.token` to extract the bearer token
3. Wrapping the OkHttpClient with an explicit interceptor that injects `Authorization: Bearer <token>` on every request

```java
httpClient = base.newBuilder()
    .addInterceptor(chain -> chain.proceed(
        chain.request().newBuilder()
             .header("Authorization", "Bearer " + token)
             .build()))
    .build();
```

---

## Production TLS Fix

The `insecure-skip-tls-verify` flag is needed when the API server certificate doesn't include the external IP as a SAN. To fix this properly:

```bash
# On the cluster node — regenerate API server cert with external IP SAN
sudo kubeadm init phase certs apiserver \
  --apiserver-cert-extra-sans=<CLUSTER_EXTERNAL_IP>
sudo systemctl restart kubelet
```

Then update kubeconfig — replace `insecure-skip-tls-verify: true` with:
```yaml
clusters:
- cluster:
    certificate-authority-data: <BASE64_ENCODED_CA_CERT>
    server: https://<CLUSTER_EXTERNAL_IP>:6443
  name: kubernetes
```

---

## Troubleshooting

### Server starts but gets 403 on all calls
The bearer token isn't being sent. Verify `~/.kube/config` has the `token:` field under `users:` and that the current context points to the right user.

```powershell
kubectl auth can-i list pods --all-namespaces
# Must return: yes
```

### `kubectl get nodes` works but MCP server fails
The JAR reads `$HOME/.kube/config` (or `$KUBECONFIG` env var). Make sure the file is not BOM-encoded — use PowerShell's `New-Object System.Text.UTF8Encoding($false)` to write it.

### `insecure-skip-tls-verify` not respected
Ensure you are not also specifying `certificate-authority-data` in the same cluster block — they conflict.

### Metrics tools (top_pods / top_nodes) return 404
The [metrics-server](https://github.com/kubernetes-sigs/metrics-server) must be installed:
```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

---

## Further Reading

- [Connecting to Cluster from Windows](docs/connect-cluster-to-mcp.md) — full step-by-step with troubleshooting
- [Single-Node Kubernetes on Ubuntu (kubeadm)](docs/single-node-kubernetes-ubuntu.md) — cluster setup guide
- [Model Context Protocol specification](https://modelcontextprotocol.io/)
- [Kubernetes API reference](https://kubernetes.io/docs/reference/kubernetes-api/)

---

## License

MIT
