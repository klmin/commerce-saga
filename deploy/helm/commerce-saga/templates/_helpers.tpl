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
     메모리·limit·CPU·OOM. 이 namespace 의 컨테이너만, 쓰는 지표만 남긴다(경보 ContainerMemoryNearLimit) */}}
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
{{- end -}}

{{/* 화면·인증 공개 주소 (설계 문서 7-3·7-4). edge 를 켤 때만 쓴다. 끝 / 없는 주소들을 JSON 으로:
     web·admin·auth(= AUTH_ISSUER), adminClientHost(호스트 방식만), webHome·adminHome(로그인 뒤·로그아웃 뒤 기본으로 갈 곳),
     webPostLogout(SAS 에 등록하는 gateway-web 의 로그아웃 뒤 주소. 경로 방식은 관리자 화면도 gateway-web 이라 /admin/ 도), secure */}}
{{- define "commerce.publicUrls" -}}
{{- $e := .Values.edge -}}
{{- if and (eq $e.mode "path") (not $e.path.url) -}}
{{- /* 공개 주소를 아직 모른다(EKS 처음 배포: LB 가 생겨야 주소를 안다). edge·화면은 띄우고 로그인 설정만 뺀다 → 빈 값 */ -}}
{}
{{- else if eq $e.mode "path" -}}
{{- $base := $e.path.url | trimSuffix "/" -}}
{{- dict "web" $base "admin" (printf "%s/admin" $base) "auth" (printf "%s/auth" $base)
      "adminClientHost" "" "webHome" "/" "adminHome" "/admin/"
      "webPostLogout" (printf "%s/,%s/admin/" $base $base)
      "secure" (hasPrefix "https://" $base) | toJson -}}
{{- else if eq $e.mode "host" -}}
{{- $web := required "edge.mode=host 면 edge.host.web 이 필요하다" $e.host.web | trimSuffix "/" -}}
{{- $admin := required "edge.mode=host 면 edge.host.admin 이 필요하다" $e.host.admin | trimSuffix "/" -}}
{{- $auth := required "edge.mode=host 면 edge.host.auth 가 필요하다" $e.host.auth | trimSuffix "/" -}}
{{- dict "web" $web "admin" $admin "auth" $auth
      "adminClientHost" (lower (urlParse $admin).host) "webHome" (printf "%s/" $web) "adminHome" (printf "%s/" $admin)
      "webPostLogout" (printf "%s/" $web)
      "secure" (hasPrefix "https://" $web) | toJson -}}
{{- else -}}
{{- fail (printf "edge.mode 는 host 또는 path 다: %s" $e.mode) -}}
{{- end -}}
{{- end -}}

{{/* 주소의 호스트 (포트 빼고). HTTPRoute hostnames 용 */}}
{{- define "commerce.hostOf" -}}
{{- (split ":" (urlParse .).host)._0 -}}
{{- end -}}
