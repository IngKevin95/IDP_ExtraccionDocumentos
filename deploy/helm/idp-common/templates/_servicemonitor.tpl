{{- define "idp-common.servicemonitor" -}}
{{- if and .Values.metrics.enabled (.Capabilities.APIVersions.Has "monitoring.coreos.com/v1") -}}
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {{ include "idp-common.fullname" . }}
  labels:
    app.kubernetes.io/name: {{ include "idp-common.name" . }}
    app.kubernetes.io/instance: {{ .Release.Name }}
spec:
  selector:
    matchLabels:
      app.kubernetes.io/name: {{ include "idp-common.name" . }}
      app.kubernetes.io/instance: {{ .Release.Name }}
  endpoints:
    - port: http
      path: /actuator/prometheus
{{- end -}}
{{- end -}}
