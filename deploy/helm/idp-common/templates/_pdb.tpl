{{- define "idp-common.pdb" -}}
{{- if .Values.pdb.enabled -}}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "idp-common.fullname" . }}
  labels:
    app.kubernetes.io/name: {{ include "idp-common.name" . }}
    app.kubernetes.io/instance: {{ .Release.Name }}
spec:
  minAvailable: {{ .Values.pdb.minAvailable | default 1 }}
  selector:
    matchLabels:
      app.kubernetes.io/name: {{ include "idp-common.name" . }}
      app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- end -}}
