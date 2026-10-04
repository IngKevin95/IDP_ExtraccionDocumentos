{{- define "idp-common.service" -}}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "idp-common.fullname" . }}
  labels:
    app.kubernetes.io/name: {{ include "idp-common.name" . }}
    app.kubernetes.io/instance: {{ .Release.Name }}
spec:
  type: {{ .Values.service.type | default "ClusterIP" }}
  ports:
    - port: {{ .Values.service.port | default 8080 }}
      targetPort: http
      protocol: TCP
      name: http
  selector:
    app.kubernetes.io/name: {{ include "idp-common.name" . }}
    app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
