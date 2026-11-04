# GitOps Delivery Pipeline: Spring Boot on Kubernetes with Helm, GitHub Actions and Argo CD

An end-to-end GitOps pipeline. A push to `main` is built and tested by GitHub Actions, the image is pushed to a **private** registry (GHCR) under a commit-SHA tag, and the workflow commits that tag into the Helm values. **Argo CD** watches the repository, renders the Helm chart and syncs it to a two-node `kubeadm` cluster.

**Design rule:** GitHub Actions never touches the cluster. There is no `kubectl` in the workflow and no kubeconfig in GitHub Secrets. The workflow only talks to the registry and to Git. Argo CD, running inside the cluster, owns the deployment.

---

## 1. Architecture

| Step | Who            | What                                                                  |
| ---- | -------------- | --------------------------------------------------------------------- |
| 1    | Developer      | Pushes code to `main`                                                 |
| 2    | GitHub Actions | `mvn verify` (build and test)                                         |
| 3    | GitHub Actions | Builds the Docker image, pushes `ghcr.io/fonsahpageo/helloapi:<sha7>` |
| 4    | GitHub Actions | Commits the new tag to `helm/k8s-demo-api/values.yaml`                |
| 5    | Argo CD        | Detects the Git change and renders the Helm chart                     |
| 6    | Argo CD        | Syncs the desired state to Kubernetes                                 |
| 7    | Kubernetes     | Runs the API on the worker node                                       |

---

## 2. Repository layout

```
.
├── .github/workflows/ci-cd.yaml     # CI: test, build, push, update Helm tag
├── argocd/application.yaml          # Argo CD Application (bootstrap, applied once)
├── helloapi/                        # Spring Boot application
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/...
├── helm/k8s-demo-api/               # Helm chart
│   ├── Chart.yaml
│   ├── values.yaml
│   └── templates/{_helpers.tpl,deployment.yaml,service.yaml}
└── README.md
```

---

## 3. Environment and VM details

Two EC2 instances in the same VPC and subnet.

| Role          | Hostname        | Private IP     | Public IP       | OS                 | Software                              |
| ------------- | --------------- | -------------- | --------------- | ------------------ | ------------------------------------- |
| Control plane | `control-plane` | `172.31.24.19` | `50.16.103.156` | Ubuntu 24.04.5 LTS | Kubernetes v1.34.13, containerd 2.2.1 |
| Worker        | `worker`        | `172.31.23.78` | `98.80.14.134`  | Ubuntu 24.04.5 LTS | Kubernetes v1.34.13, containerd 2.2.1 |

Instance specifications: t3.small(2 vCPU / 4GB RAM) for control, t2.medium(2 vCPU / 2GB RAM) for worker

Cluster-internal traffic uses the **private** IPs. The public IPs are used to reach NodePorts from a browser or `curl`.

### Security group rules

| Port  | Protocol | Source                 | Purpose                        |
| ----- | -------- | ---------------------- | ------------------------------ |
| 22    | TCP      | admin IP               | SSH                            |
| 6443  | TCP      | cluster security group | Kubernetes API                 |
| 10250 | TCP      | cluster security group | kubelet                        |
| 8472  | UDP      | cluster security group | Flannel VXLAN (pod networking) |
| 30999 | TCP      | admin IP               | Argo CD UI (NodePort)          |
| 30080 | TCP      | admin IP               | API NodePort                   |

---

## 4. Kubernetes cluster (kubeadm)

### 4.1 Prerequisites, on both nodes

```bash
# Hostnames and name resolution
sudo hostnamectl set-hostname control-plane      # worker: set to "worker"
echo "172.31.24.19  control-plane" | sudo tee -a /etc/hosts
echo "172.31.23.78  worker"        | sudo tee -a /etc/hosts

# Disable swap
sudo swapoff -a
sudo sed -i '/\sswap\s/ s/^/#/' /etc/fstab

# Kernel modules and sysctls
cat <<EOF | sudo tee /etc/modules-load.d/k8s.conf
overlay
br_netfilter
EOF
sudo modprobe overlay && sudo modprobe br_netfilter

cat <<EOF | sudo tee /etc/sysctl.d/k8s.conf
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF
sudo sysctl --system

# containerd with the systemd cgroup driver
sudo apt-get update && sudo apt-get install -y containerd
sudo mkdir -p /etc/containerd
containerd config default | sudo tee /etc/containerd/config.toml >/dev/null
sudo sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml
sudo systemctl restart containerd && sudo systemctl enable containerd

# kubeadm, kubelet, kubectl
K8S_MINOR=v1.34
sudo apt-get install -y apt-transport-https ca-certificates curl gpg
sudo mkdir -p -m 755 /etc/apt/keyrings
curl -fsSL https://pkgs.k8s.io/core:/stable:/${K8S_MINOR}/deb/Release.key \
  | sudo gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
echo "deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/${K8S_MINOR}/deb/ /" \
  | sudo tee /etc/apt/sources.list.d/kubernetes.list
sudo apt-get update
sudo apt-get install -y kubelet kubeadm kubectl
sudo apt-mark hold kubelet kubeadm kubectl
sudo systemctl enable --now kubelet
```

### 4.2 Initialise the control plane

`--apiserver-advertise-address` must be the **private** IP. On EC2 the public IP is not assigned to the network interface, so the API server cannot bind to it. The public IP is added to the certificate SANs so `kubectl` also works from outside.

```bash
sudo kubeadm init \
  --apiserver-advertise-address=172.31.24.19 \
  --apiserver-cert-extra-sans=50.16.103.156 \
  --pod-network-cidr=10.244.0.0/16

mkdir -p $HOME/.kube
sudo cp /etc/kubernetes/admin.conf $HOME/.kube/config
sudo chown $(id -u):$(id -g) $HOME/.kube/config
```

### 4.3 Install the CNI (Flannel)

The pod CIDR `10.244.0.0/16` is Flannel's default and does not overlap with the VPC range (`172.31.0.0/16`).

```bash
kubectl apply -f https://github.com/flannel-io/flannel/releases/latest/download/kube-flannel.yml
```

### 4.4 Join the worker

Run the join command printed by `kubeadm init`, on the worker, using the control plane's **private** IP:

```bash
sudo kubeadm join 172.31.24.19:6443 --token <token> \
  --discovery-token-ca-cert-hash sha256:<hash>
```

A new token can be generated at any time (tokens last 24 hours):

```bash
kubeadm token create --print-join-command
```

### 4.5 Verify

```bash
kubectl get nodes -o wide
kubectl get pods -A
```

Both nodes must be `Ready`, and all system pods `Running`. The control plane keeps its default `NoSchedule` taint, so all application pods are scheduled on the worker.

_Evidence: `docs/nodes-ready.png`,  `docs/describe-pods.png`, `docs/endpoints-reachable.png`, `docs/running-pods.png`, `docs/workflow-github-actions.png`_

---

## 5. Application (Spring Boot)

Java 21, Spring Boot 3.5, Maven. Listens on port **8080**.

| Endpoint          | Response                                                              |
| ----------------- | --------------------------------------------------------------------- |
| `GET /api/hello`  | Greeting, optional `?name=` parameter                                 |
| `GET /api/health` | `{"status":"UP", ...}`                                                |
| `GET /api/info`   | Name, version, build time, **git commit**, Java version, pod hostname |

The commit shown by `/api/info` is injected at image build time (`--build-arg APP_COMMIT=<full sha>`), so the running pod can be tied to an exact commit.

```bash
cd helloapi
./mvnw test
docker build -t helloapi:local --build-arg APP_COMMIT=local-test .
docker run --rm -p 8080:8080 helloapi:local
curl localhost:8080/api/hello
```

The Dockerfile is a multi-stage build (Maven build stage, then a slim JRE-Alpine runtime). The container runs as non-root UID `10001` and has a `HEALTHCHECK` on `/api/health`.

---

## 6. Private registry (GHCR)

- Registry: `ghcr.io`
- Image: `ghcr.io/fonsahpageo/helloapi`

Two separate personal access tokens (classic) are used, each with the minimum scope:

| Token              | Scope            | Used by                                                  |
| ------------------ | ---------------- | -------------------------------------------------------- |
| CI token           | `write:packages` | GitHub Actions, stored as the secret `REGISTRY_PASSWORD` |
| Cluster pull token | `read:packages`  | The Kubernetes pull secret                               |

A leaked cluster token can therefore only read images, not push them.

### Image pull secret

The secret is created by hand in the **same namespace as the application pods** (`demo`). It is never stored in Git. The chart references it by name through `imagePullSecrets`.

```bash
kubectl create namespace demo
read -s -p "GHCR read token: " GHCR_TOKEN; echo
kubectl create secret docker-registry registry-credentials \
  --namespace demo \
  --docker-server=ghcr.io \
  --docker-username=FonsahPageo \
  --docker-password="$GHCR_TOKEN"
unset GHCR_TOKEN
```

---

## 7. GitHub Actions (CI)

Workflow: `.github/workflows/ci-cd.yaml`. Trigger: push to `main` (changes under `helloapi/` or to the workflow file). Pull requests run the test job only.

### Repository secrets

Settings, Secrets and variables, Actions, **Repository secrets**:

| Secret              | Value                             |
| ------------------- | --------------------------------- |
| `REGISTRY_URL`      | `ghcr.io`                         |
| `REGISTRY_USERNAME` | `FonsahPageo`                     |
| `REGISTRY_PASSWORD` | classic PAT with `write:packages` |

`GITOPS_TOKEN` is **not** used: the Helm chart lives in the same repository, so the built-in `GITHUB_TOKEN` (with `contents: write`, only in the job that commits) is enough. Secret values are masked in logs and are only passed through `secrets.*`. A guard step fails early if a registry secret is empty.

### Jobs

1. **test**: checkout, set up Java 21 (Temurin, Maven cache), `mvn -B verify`.
2. **build-and-push** (push only): log in to GHCR with the secrets, build with Buildx, push two tags: the 7-character commit SHA (what is deployed) and `latest`. The full commit SHA is baked into the image as `APP_COMMIT`.
3. **update-manifest**: rewrites the `tag:` line in `helm/k8s-demo-api/values.yaml` to the commit SHA and pushes the commit `ci: deploy helloapi <sha> [skip ci]`. The job fails if the tag is empty.

---

## 8. Helm chart

Location: `helm/k8s-demo-api`. Configurable values:

| Value                                               | Default                                               | Purpose                |
| --------------------------------------------------- | ----------------------------------------------------- | ---------------------- |
| `replicaCount`                                      | `2`                                                   | Pod replicas           |
| `image.repository`                                  | `ghcr.io/fonsahpageo/helloapi`                        | Image repository       |
| `image.tag`                                         | commit SHA (written by CI)                            | Image tag              |
| `image.pullPolicy`                                  | `IfNotPresent`                                        | Pull policy            |
| `imagePullSecrets`                                  | `registry-credentials`                                | Registry credentials   |
| `containerPort`                                     | `8080`                                                | Container port         |
| `service.type` / `port` / `targetPort` / `nodePort` | `ClusterIP` / `80` / `8080` / unset                   | Service                |
| `resources`                                         | requests `100m`/`128Mi`, limits `500m`/`512Mi`        | CPU and memory         |
| `probes.startup/readiness/liveness`                 | HTTP GET `/api/health`                                | Probe path and timings |
| `podSecurityContext`, `securityContext`             | non-root, read-only root FS, all capabilities dropped | Hardening              |

```bash
helm lint helm/k8s-demo-api
helm template k8s-demo-api helm/k8s-demo-api
```

> **Note on `helm list`:** Argo CD renders the chart with `helm template` and applies the manifests itself, so it does **not** create a Helm release. `helm list -n demo` is therefore empty by design. The rendered resources are visible with `helm template` and `kubectl get all -n demo`, and in the Argo CD UI.

---

## 9. Argo CD

### 9.1 Install

```bash
kubectl create namespace argocd
kubectl apply -n argocd --server-side --force-conflicts \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
kubectl get pods -n argocd
```

### 9.2 Expose through NodePort 30999

The existing `argocd-server` Service is patched (no duplicate Service is created). Its HTTPS port targets container port 8080.

```bash
kubectl get svc argocd-server -n argocd -o yaml
kubectl patch svc argocd-server -n argocd --type merge \
  -p '{"spec":{"type":"NodePort","ports":[{"name":"https","port":443,"targetPort":8080,"nodePort":30999}]}}'
kubectl patch svc argocd-server -n argocd --type json \
  -p '[{"op":"add","path":"/spec/ports/-","value":{"name":"http","port":80,"protocol":"TCP","targetPort":8080}}]'
kubectl get svc argocd-server -n argocd
```

### 9.3 Access

URL: **https://50.16.103.156:30999** (any node IP works). The certificate is Argo CD's default self-signed certificate, so the browser shows a warning that must be accepted. Plain HTTP on this port is redirected to HTTPS.

```bash
# Username: admin
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d; echo
```

### 9.4 Application

`argocd/application.yaml` points at the chart in this repository and deploys into the `demo` namespace with automated sync, pruning and self-healing. The repository is public, so Argo CD needs no repository credentials. It is applied **once** as the bootstrap. After that, all changes flow through Git.

```bash
kubectl apply -f https://raw.githubusercontent.com/FonsahPageo/gitops-assessment/main/argocd/application.yaml
kubectl get applications -n argocd
```

---

## 10. Reaching the API

The API is available at `http://<node-public-ip>:30080/api/info`, exposed on NodePort.

---

## 11. End-to-end verification

| #   | Check                 | Command                                                                                                                 | Expected                                                          |
| --- | --------------------- | ----------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| 1   | Nodes ready           | `kubectl get nodes -o wide`                                                                                             | `control-plane` and `worker` both `Ready`                         |
| 2   | Worker runs workloads | `kubectl get pods -n demo -o wide`                                                                                      | 2 pods `1/1` on `worker`                                          |
| 3   | API responds          | `curl -s localhost:8081/api/hello` (port-forward)                                                                       | greeting JSON                                                     |
| 4   | Private image pull    | `kubectl describe pod -n demo -l app.kubernetes.io/name=k8s-demo-api \| grep -E "Pulling\|Successfully pulled\|Image:"` | `Successfully pulled image "ghcr.io/fonsahpageo/helloapi:<sha7>"` |
| 5   | Chart valid           | `helm lint helm/k8s-demo-api && helm template k8s-demo-api helm/k8s-demo-api`                                           | `0 chart(s) failed`, valid manifests                              |
| 6   | Argo CD reachable     | `curl -k -I https://50.16.103.156:30999/`                                                                               | `HTTP/1.1 200 OK`                                                 |
| 7   | Application healthy   | `kubectl get applications -n argocd`                                                                                    | `Synced` / `Healthy`                                              |
| 8   | No secrets in Git     | see below                                                                                                               | `clean`                                                           |

To prove the image is really pulled from the registry, remove the cached copy on the worker and restart the pods:

```bash
# on the worker
sudo ctr -n k8s.io images rm ghcr.io/fonsahpageo/helloapi:<tag>
# on the control plane
kubectl rollout restart deploy/k8s-demo-api -n demo
```

Secret check (run from the repo root):

```bash
git grep -nIE "ghp_|github_pat_|PRIVATE KEY|client-certificate-data|client-key-data" $(git rev-list --all) || echo "clean"
```

### Change demonstration (GitOps loop)

1. Change the greeting in `helloapi/src/main/java/com/example/helloapi/ApiController.java` and update the matching test.
2. `git push` to `main`.
3. **GitHub Actions** runs: `test`, `build-and-push` and `update-manifest` all succeed.
4. The image `ghcr.io/fonsahpageo/helloapi:<sha7>` appears in GHCR.
5. A bot commit `ci: deploy helloapi <sha7> [skip ci]` changes `image.tag` in `values.yaml`.
6. **Argo CD** detects the commit and rolls out new pods with no manual `kubectl` deploy.
7. `/api/info` returns the new commit SHA, and `/api/hello` the new greeting.

---

## 12. Troubleshooting

| Symptom                                                        | Cause                                                                                     | Fix                                                                                             |
| -------------------------------------------------------------- | ----------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| `kubeadm init` fails with `context deadline exceeded`          | Advertise address was the **public** IP, which is not on the instance's interface         | `kubeadm reset -f`, then init with the private IP (public IP via `--apiserver-cert-extra-sans`) |
| `kubeadm join` times out                                       | Security group blocks 6443 from the worker                                                | Allow 6443, 10250 and UDP 8472 from the cluster security group                                  |
| Nodes `Ready` but pods on different nodes cannot talk          | UDP 8472 (Flannel VXLAN) blocked                                                          | Allow UDP 8472 between nodes                                                                    |
| `docker login`: `Username and password required`               | Secrets missing or in the wrong tab                                                       | Add them under **Repository secrets** with the exact names                                      |
| Sync error `cannot unmarshal array into ... capabilities.drop` | `drop` list nested inside a list in `values.yaml`                                         | Use `drop: [ALL]` or `- ALL`. `helm lint` does not catch this                                   |
| Pod `InvalidImageName`, image ends with `:`                    | `image.tag` was empty                                                                     | Set a real tag; CI computes it from `GITHUB_SHA`, and the job fails if empty                    |
| `/api/info` shows an old build after a push                    | Using `latest` with `IfNotPresent` reuses the cached image                                | Deploy by unique SHA tag (what the pipeline does)                                               |
| `kubectl create secret` says `docker-password` is required     | Shell variable name case mismatch (`$ghcr_token` vs `$GHCR_TOKEN`)                        | Variables are case-sensitive                                                                    |
| Resources appear in the wrong namespace                        | `destination.namespace` in the Application was wrong                                      | Fix `argocd/application.yaml`, re-apply, delete the old namespace                               |
| `curl` on port 30999 returns `307` or `415`                    | That port is Argo CD, not the API; it redirects HTTP to HTTPS and owns the `/api/` prefix | Use `https://` and the Argo CD UI; test the API through the Service                             |
| `helm list` shows nothing                                      | Argo CD renders with `helm template`, no Helm release exists                              | Expected; use `kubectl get all -n demo`                                                         |

---

## 13. Security notes

- No credentials, tokens or kubeconfig files are committed. The registry pull secret exists only in the cluster, and CI credentials only in GitHub Secrets.
- Separate least-privilege tokens: `write:packages` for CI, `read:packages` for the cluster.
- Containers run as non-root with a read-only root filesystem and all Linux capabilities dropped.
- The registry package is private. Argo CD is exposed through a NodePort restricted by security group to the administrator's IP.
- Cleanup of tokens: classic PATs have an expiry (90 days) and should be rotated or deleted after the assessment.
