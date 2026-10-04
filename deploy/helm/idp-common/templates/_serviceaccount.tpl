{{- define "idp-common.serviceaccount" -}}
{{- if .Values.serviceAccount.create -}}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "idp-common.serviceAccountName" . }}
  labels:
    app.kubernetes.io/name: {{ include "idp-common.name" . }}
    app.kubernetes.io/instance: {{ .Release.Name }}
automountServiceAccountToken: false
{{- end -}}
{{- end -}}
