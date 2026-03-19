# Single-Node Kubernetes on Ubuntu (kubeadm)

Tested on **Ubuntu 22.04 LTS** and **Ubuntu 24.04 LTS**.  
This sets up a fully functional single-node cluster where the control-plane also runs workloads.

---

## Prerequisites

- Ubuntu 22.04 or 24.04 (fresh install recommended)
- Minimum: 2 CPU, 2 GB RAM, 20 GB disk
- Root or sudo access
- Internet access

---

## Step 1 — System Preparation

```bash
# Update system
sudo apt-get update && sudo apt-get upgrade -y

# Disable swap (Kubernetes requires it off)
sudo swapoff -a
sudo sed -i '/ swap / s/^\(.*\)$/#\1/g' /etc/fstab

# Load required kernel modules
cat <<EOF | sudo tee /etc/modules-load.d/k8s.conf
overlay
br_netfilter
EOF

sudo modprobe overlay
sudo modprobe br_netfilter

# Set kernel networking parameters
cat <<EOF | sudo tee /etc/sysctl.d/k8s.conf
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF

sudo sysctl --system
```

---

## Step 2 — Install containerd (Container Runtime)

**Download URL:** https://github.com/containerd/containerd/releases

```bash
# Install containerd from Ubuntu apt (easier, kept up to date)
sudo apt-get install -y containerd

# Generate default config
sudo mkdir -p /etc/containerd
containerd config default | sudo tee /etc/containerd/config.toml

# Enable SystemdCgroup (required for kubeadm)
sudo sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml

# Restart and enable containerd
sudo systemctl restart containerd
sudo systemctl enable containerd

# Verify
sudo systemctl status containerd
```

---

## Step 3 — Install kubeadm, kubelet, kubectl

**Download URL / Apt repo:** https://pkgs.k8s.io

```bash
# Install dependencies
sudo apt-get install -y apt-transport-https ca-certificates curl gpg

# Add Kubernetes apt repo key (replace 1.32 with your desired version)
K8S_VERSION="1.32"

sudo mkdir -p /etc/apt/keyrings
curl -fsSL https://pkgs.k8s.io/core:/stable:/v${K8S_VERSION}/deb/Release.key \
  | sudo gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg

# Add the apt repository
echo "deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] \
  https://pkgs.k8s.io/core:/stable:/v${K8S_VERSION}/deb/ /" \
  | sudo tee /etc/apt/sources.list.d/kubernetes.list

# Install
sudo apt-get update
sudo apt-get install -y kubelet kubeadm kubectl

# Pin version so apt doesn't auto-upgrade it
sudo apt-mark hold kubelet kubeadm kubectl

# Verify
kubeadm version
kubectl version --client
```

---

## Step 4 — Initialize the Cluster

```bash
# Get the server's IP address
SERVER_IP=$(hostname -I | awk '{print $1}')
echo "Server IP: $SERVER_IP"

# Initialize
# --pod-network-cidr is for Calico (192.168.0.0/16) — change if using Flannel (10.244.0.0/16)
sudo kubeadm init \
  --apiserver-advertise-address=$SERVER_IP \
  --pod-network-cidr=192.168.0.0/16 \
  --cri-socket=unix:///run/containerd/containerd.sock

# After success, set up kubeconfig for your user
mkdir -p $HOME/.kube
sudo cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
sudo chown $(id -u):$(id -g) $HOME/.kube/config

# Verify control-plane is up
kubectl get nodes
# Status will be NotReady until CNI is installed (Step 5)
```

---

## Step 5 — Install a CNI (Pod Network)

Pick **one** of the following:

### Option A — Calico (recommended)

**Download URL:** https://docs.tigera.io/calico/latest/getting-started/kubernetes/self-managed-onprem/onpremises

```bash
# Install Calico operator
kubectl create -f https://raw.githubusercontent.com/projectcalico/calico/v3.29.3/manifests/tigera-operator.yaml

# Install Calico custom resources
kubectl create -f https://raw.githubusercontent.com/projectcalico/calico/v3.29.3/manifests/custom-resources.yaml

# Watch until all pods are Running
watch kubectl get pods -n calico-system
```

### Option B — Flannel (simpler, lighter)

**Download URL:** https://github.com/flannel-io/flannel

> Note: use `--pod-network-cidr=10.244.0.0/16` in Step 4 if you choose Flannel.

```bash
kubectl apply -f https://github.com/flannel-io/flannel/releases/latest/download/kube-flannel.yml
```

---

## Step 6 — Allow Workloads on the Control-Plane Node

By default kubeadm taints the control-plane so no pods run on it.  
For a single-node setup, remove the taint:

```bash
kubectl taint nodes --all node-role.kubernetes.io/control-plane-
```

---

## Step 7 — Verify the Cluster

```bash
# Node should be Ready
kubectl get nodes -o wide

# Core system pods should all be Running
kubectl get pods -n kube-system

# Run a quick test pod
kubectl run test --image=nginx --restart=Never
kubectl get pods
kubectl delete pod test
```

---

## Step 8 — Install kubectl on Your Windows Machine

To connect from your Windows dev machine to this cluster:

**Download URL:** https://dl.k8s.io/release/v1.32.0/bin/windows/amd64/kubectl.exe

```powershell
# Create a dedicated directory
New-Item -ItemType Directory -Force -Path "C:\tools\kubectl"

# Download kubectl v1.32
Invoke-WebRequest -Uri "https://dl.k8s.io/release/v1.32.0/bin/windows/amd64/kubectl.exe" `
  -OutFile "C:\tools\kubectl\kubectl.exe"

# Add to PATH permanently
$currentPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if ($currentPath -notlike "*C:\tools\kubectl*") {
    [Environment]::SetEnvironmentVariable("PATH", "$currentPath;C:\tools\kubectl", "User")
}

# Reload PATH in this PowerShell session
$env:PATH += ";C:\tools\kubectl"

# Verify
kubectl version --client
```

> After installing kubectl, follow the full cluster connection guide:  
> [connect-cluster-to-mcp.md](connect-cluster-to-mcp.md)

---

## Step 9 — (Optional) Install Helm

**Download URL:** https://github.com/helm/helm/releases

```bash
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
helm version
```

---

## Download URL Reference

| Component | URL |
|---|---|
| containerd | https://github.com/containerd/containerd/releases |
| Kubernetes apt repo | https://pkgs.k8s.io |
| Calico (operator) | https://raw.githubusercontent.com/projectcalico/calico/v3.29.3/manifests/tigera-operator.yaml |
| Calico (resources) | https://raw.githubusercontent.com/projectcalico/calico/v3.29.3/manifests/custom-resources.yaml |
| Flannel | https://github.com/flannel-io/flannel/releases/latest/download/kube-flannel.yml |
| kubectl (Windows) | https://dl.k8s.io/release/v1.32.0/bin/windows/amd64/kubectl.exe |
| Helm | https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 |

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Node stuck `NotReady` | CNI not installed — do Step 5 |
| `kubeadm init` fails on swap | `sudo swapoff -a` then retry |
| Pods can't schedule | Taint not removed — do Step 6 |
| `kubectl` connection refused from Windows | Firewall — open port 6443: `sudo ufw allow 6443/tcp` |
| containerd not found | `sudo apt-get install -y containerd.io` (from Docker's repo instead) |
