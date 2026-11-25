{{/* 이미지 태그: image.tags.<이름> 이 있으면 그것(EKS release 가 바뀐 이미지만 새 태그로 준다), 없으면 공통 image.tag */}}
{{- define "commerce.imageTag" -}}
{{- index (.root.Values.image.tags | default dict) .name | default .root.Values.image.tag -}}
{{- end -}}

{{- define "commerce.image" -}}
{{- $img := printf "%s/%s:%s" .root.Values.image.repositoryPrefix .name (include "commerce.imageTag" .) -}}
{{- if .root.Values.image.registry -}}
{{- printf "%s/%s" .root.Values.image.registry $img -}}
{{- else -}}
{{- $img -}}
{{- end -}}
{{- end -}}

{{- define "commerce.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: commerce-saga
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
{{- end -}}

{{- define "commerce.kafkaBootstrap" -}}
{{ .Release.Name }}-kafka-bootstrap:9092
{{- end -}}

{{/* alloy 가 메트릭을 긁을 서비스 (config.alloy 의 METRICS_SERVICE_TARGETS). compose 와 같은 라벨, 주소만 클러스터 Service */}}
{{- define "commerce.metricsServiceTargets" -}}
{{- $targets := list }}
{{- range $name, $svc := .Values.services }}
{{- $targets = append $targets (dict "__address__" (printf "%s:%v" $name $svc.port) "service" $name) }}
{{- end }}
{{- toJson $targets }}
{{- end -}}

{{/* k8s 에만 있는 수집 (alloy 가 config.alloy 와 같은 폴더에서 읽는다). 노드 kubelet 의 컨테이너 지표(cAdvisor):
     메모리·limit·CPU·OOM, kubelet 의 PVC 사용량. 이 namespace 것만, 쓰는 지표만 남긴다(경보 ContainerMemoryNearLimit, 대시보드) */}}
{{- define "commerce.kubernetesAlloy" -}}
discovery.kubernetes "nodes" {
  role = "node"
}

// API 서버의 노드 프록시로 읽는다(노드 IP·kubelet 인증서를 몰라도 된다). 권한은 ClusterRole alloy
discovery.relabel "cadvisor" {
  targets = discovery.kubernetes.nodes.targets

  rule {
    target_label = "__address__"
    replacement  = "kubernetes.default.svc:443"
  }

  rule {
    source_labels = ["__meta_kubernetes_node_name"]
    target_label  = "__metrics_path__"
    replacement   = "/api/v1/nodes/${1}/proxy/metrics/cadvisor"
  }

  rule {
    source_labels = ["__meta_kubernetes_node_name"]
    target_label  = "node"
  }
}

prometheus.scrape "cadvisor" {
  targets           = discovery.relabel.cadvisor.output
  job_name          = "cadvisor"
  scheme            = "https"
  bearer_token_file = "/var/run/secrets/kubernetes.io/serviceaccount/token"
  scrape_interval   = "15s"
  forward_to        = [prometheus.relabel.cadvisor.receiver]

  tls_config {
    ca_file = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
  }
}

prometheus.relabel "cadvisor" {
  forward_to = [prometheus.remote_write.default.receiver]

  rule {
    source_labels = ["namespace"]
    regex         = "{{ .Release.Namespace }}"
    action        = "keep"
  }

  rule {
    source_labels = ["__name__"]
    regex         = "container_(memory_working_set_bytes|spec_memory_limit_bytes|cpu_usage_seconds_total|oom_events_total)"
    action        = "keep"
  }
}

// PVC 디스크 사용량(Kafka·Postgres 등). cAdvisor 가 아니라 kubelet 자신의 /metrics 에 있다
discovery.relabel "kubelet" {
  targets = discovery.kubernetes.nodes.targets

  rule {
    target_label = "__address__"
    replacement  = "kubernetes.default.svc:443"
  }

  rule {
    source_labels = ["__meta_kubernetes_node_name"]
    target_label  = "__metrics_path__"
    replacement   = "/api/v1/nodes/${1}/proxy/metrics"
  }

  rule {
    source_labels = ["__meta_kubernetes_node_name"]
    target_label  = "node"
  }
}

prometheus.scrape "kubelet" {
  targets           = discovery.relabel.kubelet.output
  job_name          = "kubelet"
  scheme            = "https"
  bearer_token_file = "/var/run/secrets/kubernetes.io/serviceaccount/token"
  scrape_interval   = "30s"
  forward_to        = [prometheus.relabel.kubelet.receiver]

  tls_config {
    ca_file = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
  }
}

prometheus.relabel "kubelet" {
  forward_to = [prometheus.remote_write.default.receiver]

  rule {
    source_labels = ["namespace"]
    regex         = "{{ .Release.Namespace }}"
    action        = "keep"
  }

  rule {
    source_labels = ["__name__"]
    regex         = "kubelet_volume_stats_(used|capacity)_bytes"
    action        = "keep"
  }
}
{{- end -}}
