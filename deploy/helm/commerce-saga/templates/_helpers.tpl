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
