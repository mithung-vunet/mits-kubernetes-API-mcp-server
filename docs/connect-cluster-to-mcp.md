# Connecting the Kubernetes Dynamic MCP Server to Your Cluster

The MCP server uses the Kubernetes Java client, which reads connection details from a **kubeconfig file** — the same as `kubectl`.

This guide covers connecting from **Windows** to a **remote Ubuntu cluster** using a service account token, which is the most reliable method (avoids certificate encoding issues).

---

## Part 1 — On the Ubuntu Cluster (run once)

### Step 1: Create a dedicated service account

```bash
kubectl create serviceaccount mcp-admin -n kube-system
```

### Step 2: Grant cluster-admin access

```bash
kubectl create clusterrolebinding mcp-admin-binding \
  --clusterrole=cluster-admin \
  --serviceaccount=kube-system:mcp-admin
```

> **Tip:** For a read-only MCP setup, replace `cluster-admin` with `view`:
> ```bash
> kubectl create clusterrolebinding mcp-admin-binding \
>   --clusterrole=view \
>   --serviceaccount=kube-system:mcp-admin
> ```

### Step 3: Create a long-lived token (valid 1 year)

```bash
kubectl create token mcp-admin -n kube-system --duration=8760h
```

Copy the entire printed token — you'll need it on Windows.

### Step 4: Get the API server's CA certificate

```bash
cat /etc/kubernetes/pki/ca.crt | base64 -w0
```

Copy this output — it's a single long base64 string.

### Step 5: Note your cluster's external IP

```bash
curl -s ifconfig.me    # or: hostname -I
```

---

## Part 2 — On Windows (PowerShell)

### Step 1: Install kubectl

```powershell
# Create directory and download kubectl
New-Item -ItemType Directory -Force -Path "C:\tools\kubectl"
Invoke-WebRequest -Uri "https://dl.k8s.io/release/v1.32.0/bin/windows/amd64/kubectl.exe" `
  -OutFile "C:\tools\kubectl\kubectl.exe"

# Add to PATH permanently (takes effect in new terminals)
$currentPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if ($currentPath -notlike "*C:\tools\kubectl*") {
    [Environment]::SetEnvironmentVariable("PATH", "$currentPath;C:\tools\kubectl", "User")
}

# Reload PATH in this session
$env:PATH += ";C:\tools\kubectl"

# Verify
kubectl version --client
```

### Step 2: Write the kubeconfig

Open PowerShell and run the block below, substituting your values:

```powershell
$kubeconfig = @"
apiVersion: v1
kind: Config
clusters:
- cluster:
    insecure-skip-tls-verify: true
    server: https://<CLUSTER_EXTERNAL_IP>:6443
  name: kubernetes
contexts:
- context:
    cluster: kubernetes
    user: mcp-admin
  name: mcp-admin@kubernetes
current-context: mcp-admin@kubernetes
preferences: {}
users:
- name: mcp-admin
  user:
    token: <PASTE_TOKEN_FROM_STEP_3>
"@

New-Item -ItemType Directory -Force -Path "$HOME\.kube" | Out-Null
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText("$HOME\.kube\config", $kubeconfig, $utf8NoBom)
Write-Host "Kubeconfig written to $HOME\.kube\config"
```

> **Why `insecure-skip-tls-verify`?**  
> When `kubeadm` initializes the cluster it generates a TLS certificate for the API server that only includes the internal cluster IP as a Subject Alternative Name (SAN). The external IP is not in the cert, so TLS verification fails from outside.  
> See [Part 3](#part-3--production-tls-fix) for the proper fix.

### Step 3: Verify the connection

```powershell
kubectl get nodes
```

Expected output:
```
NAME           STATUS   ROLES                  AGE   VERSION
mithun-2-vm    Ready    control-plane,master   43d   v1.27.4
```

```powershell
# Also verify full access
kubectl get namespaces
kubectl get pods -A
```

---

## Part 3 — Production TLS Fix

For a production setup where you don't want `insecure-skip-tls-verify`, regenerate the API server certificate on Ubuntu to include the external IP:

### On Ubuntu:

```bash
# Regenerate the API server cert with the external IP as a SAN
sudo kubeadm init phase certs apiserver \
  --apiserver-cert-extra-sans=<CLUSTER_EXTERNAL_IP>

# Restart kubelet to pick up the new cert
sudo systemctl restart kubelet

# Verify the cert now includes the external IP
openssl x509 -in /etc/kubernetes/pki/apiserver.crt -text -noout | grep -A1 "Subject Alternative"
```

### On Windows, update kubeconfig to use CA verification:

Replace `insecure-skip-tls-verify: true` with the CA cert data:

```powershell
$kubeconfig = @"
apiVersion: v1
kind: Config
clusters:
- cluster:
    certificate-authority-data: <BASE64_CA_CERT_FROM_STEP_4>
    server: https://<CLUSTER_EXTERNAL_IP>:6443
  name: kubernetes
contexts:
- context:
    cluster: kubernetes
    user: mcp-admin
  name: mcp-admin@kubernetes
current-context: mcp-admin@kubernetes
preferences: {}
users:
- name: mcp-admin
  user:
    token: <TOKEN>
"@

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText("$HOME\.kube\config", $kubeconfig, $utf8NoBom)
```

---

## Part 4 — Configure MCP in VS Code

Create `.vscode/mcp.json` in your project root:

```json
{
  "servers": {
    "kubernetes-dynamic": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "C:\\Projects\\kubernetes-mcp-java\\dynamic-api\\target\\kubernetes-dynamic-mcp-server-1.0.0.jar"
      ]
    }
  }
}
```

The MCP server reads `%USERPROFILE%\.kube\config` automatically. Restart VS Code after creating this file.

To use a **different kubeconfig path** (e.g. multiple clusters):
```json
{
  "servers": {
    "kubernetes-dynamic": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "C:\\Projects\\kubernetes-mcp-java\\dynamic-api\\target\\kubernetes-dynamic-mcp-server-1.0.0.jar"],
      "env": {
        "KUBECONFIG": "C:\\Users\\YourUser\\.kube\\my-cluster-config"
      }
    }
  }
}
```

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `dial tcp ... connection refused` | Port 6443 blocked | `sudo ufw allow 6443/tcp` on Ubuntu |
| `x509: certificate is valid for 10.0.0.x, not <external IP>` | Cert SAN mismatch | Add `insecure-skip-tls-verify: true` or do the [TLS fix](#part-3--production-tls-fix) |
| `specifying a root certificates file with the insecure flag is not allowed` | Both CA data and insecure-skip set | Remove `certificate-authority-data` when using `insecure-skip-tls-verify` |
| `error: x509: malformed subjectPublicKey` | UTF-8 BOM in kubeconfig | Always write with `New-Object System.Text.UTF8Encoding($false)` |
| `Unauthorized` (401) | Token expired or wrong | Re-run `kubectl create token mcp-admin -n kube-system --duration=8760h` |
| `Forbidden` (403) | Service account lacks permissions | Check the clusterrolebinding: `kubectl get clusterrolebinding mcp-admin-binding -o yaml` |
| Server not appearing in Copilot | mcp.json not picked up | Restart VS Code; check `.vscode/mcp.json` exists at workspace root |

Run these on the Ubuntu server to collect the details you need.

### 1. Get the API Server URL
```bash
kubectl cluster-info
# Output example:
# Kubernetes control plane is running at https://192.168.1.10:6443
```

### 2. Get the Full kubeconfig (contains certs + token)
```bash
cat ~/.kube/config
```

### 3. Get the kubeconfig in a single self-contained file (base64 certs embedded)
```bash
kubectl config view --raw
```

### 4. Check what user/context is active
```bash
kubectl config current-context
kubectl config get-contexts
```

### 5. Get the API server's CA certificate (if needed separately)
```bash
kubectl config view --raw -o jsonpath='{.clusters[0].cluster.certificate-authority-data}' | base64 -d
```

---

## Option A — MCP Server Running on Windows (Remote Cluster)

### Step 1: Copy the kubeconfig from Ubuntu to Windows

On the **Ubuntu server**:
```bash
# Print the raw kubeconfig — copy this entire output
kubectl config view --raw
```

On **Windows**, create the file `C:\Users\<YourUser>\.kube\config`
and paste the content in.

Then fix the server address — find the line:
```yaml
    server: https://127.0.0.1:6443
```
Replace `127.0.0.1` with the actual Ubuntu server IP:
```yaml
    server: https://<UBUNTU_SERVER_IP>:6443
```

### Step 2: Allow port 6443 through the firewall on Ubuntu
```bash
sudo ufw allow 6443/tcp
sudo ufw status
```

### Step 3: Verify from Windows PowerShell
```powershell
# Download kubectl for Windows if not already installed
curl.exe -LO "https://dl.k8s.io/release/v1.32.0/bin/windows/amd64/kubectl.exe"
# Move it somewhere in PATH, e.g. C:\Windows\System32\kubectl.exe

# Test connection
kubectl cluster-info
kubectl get nodes
```

### Step 4: Configure mcp.json for VS Code

Create or edit `.vscode/mcp.json` in your workspace:
```json
{
  "servers": {
    "kubernetes-dynamic": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "C:\\Projects\\kubernetes-mcp-java\\dynamic-api\\target\\kubernetes-dynamic-mcp-server-1.0.0.jar"
      ]
    }
  }
}
```

The server will automatically find `C:\Users\<YourUser>\.kube\config`.

To use a **specific kubeconfig file** instead:
```json
{
  "servers": {
    "kubernetes-dynamic": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "C:\\Projects\\kubernetes-mcp-java\\dynamic-api\\target\\kubernetes-dynamic-mcp-server-1.0.0.jar"
      ],
      "env": {
        "KUBECONFIG": "C:\\Users\\<YourUser>\\.kube\\config"
      }
    }
  }
}
```

---

## Option B — MCP Server Running on the Ubuntu Node Itself

### Step 1: Install Java on Ubuntu
```bash
sudo apt-get install -y openjdk-17-jre-headless
java -version
```

### Step 2: Copy the JAR to Ubuntu
From Windows PowerShell:
```powershell
scp C:\Projects\kubernetes-mcp-java\dynamic-api\target\kubernetes-dynamic-mcp-server-1.0.0.jar user@<UBUNTU_IP>:~/
```

Or build directly on Ubuntu (requires Maven):
```bash
sudo apt-get install -y maven
cd ~/kubernetes-mcp-java/dynamic-api
mvn clean package -DskipTests
```

### Step 3: kubeconfig is already in place
Since you initialized the cluster with `kubeadm`, `~/.kube/config` already exists.
No extra steps needed — the server will use it automatically.

### Step 4: Test the JAR
```bash
# Quick smoke test — send an MCP initialize request
echo '{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1"}},"id":1}' \
  | java -jar ~/kubernetes-dynamic-mcp-server-1.0.0.jar
# Should print a JSON response with serverInfo
```

### Step 5: Configure mcp.json to SSH into the Ubuntu server

For a remote Claude Desktop or VS Code connecting over SSH, use:
```json
{
  "servers": {
    "kubernetes-dynamic": {
      "type": "stdio",
      "command": "ssh",
      "args": [
        "<UBUNTU_USER>@<UBUNTU_IP>",
        "java -jar /home/<UBUNTU_USER>/kubernetes-dynamic-mcp-server-1.0.0.jar"
      ]
    }
  }
}
```

---

## Creating a Dedicated Service Account (Recommended for Production)

Rather than using the admin kubeconfig, create a restricted service account:

Run on the **Ubuntu cluster**:

```bash
# Create a service account for the MCP server
kubectl create serviceaccount mcp-server -n default

# Bind it to cluster-reader role (read-only across all namespaces)
kubectl create clusterrolebinding mcp-server-reader \
  --clusterrole=view \
  --serviceaccount=default:mcp-server

# For full read+write access, use cluster-admin instead:
# kubectl create clusterrolebinding mcp-server-admin \
#   --clusterrole=cluster-admin \
#   --serviceaccount=default:mcp-server

# Create a long-lived token for the service account
kubectl create token mcp-server --duration=8760h -n default
# Copy the printed token — you'll need it below
```

### Build a minimal kubeconfig using the service account token

Run on **Ubuntu**, replacing the values with your own:
```bash
# Get your cluster's CA cert
CA_DATA=$(kubectl config view --raw -o jsonpath='{.clusters[0].cluster.certificate-authority-data}')

# Get the API server URL
API_SERVER=$(kubectl config view -o jsonpath='{.clusters[0].cluster.server}')

# Get the token (from the create token command above)
TOKEN="<paste-token-here>"

# Write a minimal kubeconfig
cat > ~/mcp-kubeconfig.yaml <<EOF
apiVersion: v1
kind: Config
clusters:
- name: mcp-cluster
  cluster:
    server: ${API_SERVER}
    certificate-authority-data: ${CA_DATA}
contexts:
- name: mcp-context
  context:
    cluster: mcp-cluster
    user: mcp-server
current-context: mcp-context
users:
- name: mcp-server
  user:
    token: ${TOKEN}
EOF

# Verify it works
kubectl --kubeconfig=~/mcp-kubeconfig.yaml get nodes
```

Copy `mcp-kubeconfig.yaml` to Windows and point `KUBECONFIG` to it in `mcp.json`.

---

## Verify the MCP Server Can See the Cluster

Once configured, use the AI to run this in Copilot chat:

> "Use the kubernetes-dynamic MCP server to discover all API resources"

This calls `discover_api_resources` and should return a full list of resource types.
Then try:

> "List all pods across all namespaces"

Which calls `list_resources` with `resource=pods`.

---

## Troubleshooting

| Problem | Command to diagnose | Fix |
|---|---|---|
| `Connection refused` to 6443 | `nc -zv <IP> 6443` from Windows | `sudo ufw allow 6443/tcp` on Ubuntu |
| `certificate signed by unknown authority` | `kubectl cluster-info` | Use `--insecure-skip-tls-verify: true` in kubeconfig (dev only) |
| `Unauthorized` (401) | `kubectl auth whoami` | Token expired — regenerate with `kubectl create token` |
| `Forbidden` (403) | `kubectl auth can-i list pods` | Add permissions to the service account |
| Server not appearing in Copilot | — | Restart VS Code after editing mcp.json |
