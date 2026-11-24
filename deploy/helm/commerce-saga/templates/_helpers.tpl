{{- define "commerce.image" -}}
{{- $img := printf "%s/%s:%s" .root.Values.image.repositoryPrefix .name .root.Values.image.tag -}}
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

{{/* Prometheus 설정 (templates/observability.yaml). infra/prometheus/prometheus.yml 과 같은 job·라벨, 대상만 클러스터 Service */}}
{{- define "commerce.prometheusConfig" -}}
global:
  scrape_interval: 5s
  evaluation_interval: 5s

rule_files:
  - /etc/prometheus/rules/*.yml

scrape_configs:
  - job_name: services
    metrics_path: /actuator/prometheus
    static_configs:
{{- range $name, $svc := .Values.services }}
      - targets: ['{{ $name }}:{{ $svc.port }}']
        labels: {service: {{ $name }}}
{{- end }}

  - job_name: kafka
    static_configs:
      - targets: ['kafka-exporter:9308']
{{- end -}}
