# Weaviate in Kubernetes

This "document" should help you how to get a simple [Kubernetes](https://kubernetes.io/) cluster running and install [Weaviate](https://weaviate.io/) on it.

<details>
<summary><h2>Prerequisites:</h2></summary>

If not otherwise stated, the following commands should be executed on all individual machines that should be part of the cluster.

### Update and install required packages

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y apt-transport-https ca-certificates curl software-properties-common containerd

# Add Kubernetes repository
curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.28/deb/Release.key | sudo gpg --dearmor -o /etc/apt/keyrings/kubernetes.gpg
echo "deb [signed-by=/etc/apt/keyrings/kubernetes.gpg] https://pkgs.k8s.io/core:/stable:/v1.28/deb/ /" | sudo tee /etc/apt/sources.list.d/kubernetes.list

# Install Kubernetes packages
sudo apt update
sudo apt install -y kubelet kubeadm kubectl
sudo apt-mark hold kubelet kubeadm kubectl
```

### Disable Swap Memory

```bash
sudo swapoff -a
sudo sed -i 's|^/swap.img|#/swap.img|' /etc/fstab
```

### Configure Kernel Settings

```bash
# Load required modules
cat <<EOF | sudo tee /etc/modules-load.d/k8s.conf
overlay
br_netfilter
EOF

sudo modprobe overlay
sudo modprobe br_netfilter

# Set kernel parameters
cat <<EOF | sudo tee /etc/sysctl.d/k8s.conf
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF

sudo sysctl --system
```

### Configure [containerd](https://containerd.io/)

An industry-standard container runtime with an emphasis on **simplicity**, **robustness** and **portability**

```bash
sudo mkdir -p /etc/containerd
sudo containerd config default | sudo tee /etc/containerd/config.toml
sudo sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml
sudo systemctl restart containerd
sudo systemctl enable containerd
```

### Reboot and Verify

```bash
sudo reboot

# After reboot, verify:
free -h                          # Should show swap: 0B 0B 0B
sudo systemctl status containerd
sudo systemctl status kubelet
```

</details>

<details>
<summary><h2>Kubernetes Setup</h2></summary>

The cluster consists of a `Control Plane` and `Worker` nodes.

### On the `Control Plane` Node

####  Initialize the Cluster

```bash
sudo kubeadm init --pod-network-cidr=10.244.0.0/16 --apiserver-advertise-address=<CONTROLPLANE-IP>
```

This output of this command will include the `join` command needed to join other nodes to this cluster. It looks something like this: **Save this command somewhere**

```bash
kubeadm join <CONTROLPLANE-IP>:<PORT> --token <TOKEN> --discovery-token-ca-cert-hash sha256:<HASH>
```

#### Set up [kubectl](https://kubernetes.io/docs/reference/kubectl/) for your user

```bash
mkdir -p $HOME/.kube
sudo cp -i /etc/pki/kubernetes/admin.conf $HOME/.kube/config
sudo chown $(id -u):$(id -g) $HOME/.kube/config
```

#### Install [Flannel](https://github.com/flannel-io/flannel) CNI Plugin

```bash
kubectl apply -f https://raw.githubusercontent.com/flannel-io/flannel/master/Documentation/kube-flannel.yml
```

### On the `Worker` Node

#### Join workers

```bash
kubeadm join <CONTROLPLANE-IP>:<PORT> --token <TOKEN> --discovery-token-ca-cert-hash sha256:<HASH>
```

### Verify Cluster (on `Control Plane`)

#### Verify all nodes are ready:

```bash
kubectl get nodes
# Wait for all nodes to show "Ready" status
watch kubectl get nodes
```

#### Print Cluster Info

```bash
kubectl cluster-info
kubectl get pods --all-namespaces
```

#### Reboot persistance

The cluster should automatically establish itself again after reboots of any or all nodes. 

### Should the `Control Plane` be part of the cluster?

If so:

```bash
kubectl taint nodes <NODENAME> node-role.kubernetes.io/control-plane:NoSchedule-
```

</details>

<details>
<summary><h2>Install Weaviate</h2></summary>

The following commands should **only** be run on the `control plane` !

### Prerequisites:

#### [Install Helm](https://helm.sh/docs/intro/install/)

### Add the Weaviate Helm repository

```bash
 helm repo add weaviate https://weaviate.github.io/weaviate-helm
 helm repo update
```

### Install Weaviate with the provided values file

```bash
helm install weaviate weaviate/weaviate -f weaviate-values.yaml --namespace weaviate-system
```

#### Wait until all the pods are running

```bash
 kubectl get pods -w -n weaviate-system
```

#### Verify weaviate is running

```bash
kubectl get pods -n weaviate-system
kubectl get services -n weaviate-system
```

</details>