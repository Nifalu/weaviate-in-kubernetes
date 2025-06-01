# Weaviate Configuration Guide

### Replicas

```yaml
replicas: 3
```

- Determines on how many nodes of the cluster weaviate should be running.
- This is **NOT** the number of shards weaviate will be making and this is also **NOT** the number of machines in the cluster. You could for example have a cluster with 10 machines and tell weaviate to use 3 of them.

### Resource Configuration

```yaml
resources:
  requests:
    cpu: "8"
    memory: 16Gi
  limits:
    cpu: "12"
    memory: 20Gi
```

- **Requests**: Minimum resources guaranteed to each pod
- **Limits**: Maximum resources a pod can consume
- These numbers should not be higher than the available resources on the individual machines.

### Environment Variables

```yaml
env:
  AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED: "true"
```

- Allow access without authentication (development/testing)

```yaml
  CLUSTER_GOSSIP_BIND_PORT: 7000
  CLUSTER_DATA_BIND_PORT: 7001
```

- Internal ports for cluster communication
- Gossip port: Membership and failure detection
- Data port: Data replication between nodes

```yaml
  RAFT_BOOTSTRAP_EXPECT: 3
  RAFT_BOOTSTRAP_TIMEOUT: 600
```

- RAFT consensus protocol configuration. Set how many nodes it should expect and how long the timeout interval is for cluster creation.

```yaml
  TRACK_VECTOR_DIMENSIONS: "false"
  GOGC: 100
  GOMEMLIMIT: 12GiB
```

- Performance optimizations
- Go garbage collection target (100%)
- Memory limit for Go runtime (leaving headroom)

```yaml
  QUERY_MAXIMUM_RESULTS: 100000
```

- Allow large result sets (100k objects)

### Storage Configuration

```yaml
storage:
  size: 2Ti
  storageClassName: "longhorn"
```

- 2TB persistent volume for each replica
- Use Longhorn distributed storage

### Service Configuration

We want to use NodePort to directly communicate with the node from outside the cluster.

```yaml
service:
  type: NodePort
  ports:
    - name: http
      port: 80
      protocol: TCP
      nodePort: 30080
```

- HTTP API exposed on NodePort 30080
- Accessible from outside the cluster

```yaml
grpcService:
  enabled: true
  type: NodePort
  ports:
    - name: grpc
      port: 50051
      protocol: TCP
      nodePort: 30051
```

- gRPC service for high-performance operations
- Exposed on port 30051

### Pod Anti-Affinity

```yaml
affinity:
  podAntiAffinity:
    requiredDuringSchedulingIgnoredDuringExecution:
    - labelSelector:
        matchLabels:
          app: weaviate
      topologyKey: kubernetes.io/hostname
```

- Ensures each Weaviate pod runs on a different physical node

### Tolerations

```yaml
tolerations:
- key: node-role.kubernetes.io/control-plane
  operator: Exists
  effect: NoSchedule
```

- Allows pods to be scheduled on control plane node

### Security Context

```yaml
containerSecurityContext:
  runAsUser: 0
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: false
```

- Runs as root user

### Health Checks

```yaml
startupProbe:
  enabled: true
  initialDelaySeconds: 60
  periodSeconds: 10
  failureThreshold: 30
```

- Allows up to 360 seconds (60 + 10×30) for startup
- Important for large data loads

```yaml
livenessProbe:
  initialDelaySeconds: 300
  periodSeconds: 30
  failureThreshold: 3
```

- Checks if pod is alive every 30 seconds
- Restarts pod after 3 consecutive failures

```yaml
readinessProbe:
  initialDelaySeconds: 30
  periodSeconds: 10
  failureThreshold: 3
```

- Determines when pod is ready to receive traffic
- Removes from service if unhealthy

### High Availability

```yaml
podDisruptionBudgets:
  weaviate:
    enabled: true
    spec:
      minAvailable: 2
```

- Ensures at least 2 pods remain available during updates
- Prevents complete service disruption during maintenance

```yaml
terminationGracePeriodSeconds: 300
```

- Allows 5 minutes for graceful shutdown
- Ensures in-flight requests complete

## Usage

Deploy Weaviate with this configuration:

```bash
helm install weaviate weaviate/weaviate -f weaviate-values.yaml --namespace weaviate-system
```

Monitor the deployment:

```bash
kubectl get pods -n weaviate-system -w
```