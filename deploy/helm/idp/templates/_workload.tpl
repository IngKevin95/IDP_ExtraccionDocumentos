{{/*
Configuracion de runtime por servicio: entorno, volumenes de secretos montados (bundles SSL) y credenciales
siempre via secretKeyRef (nunca literales). Devuelve YAML con las claves env, extraVolumes y extraVolumeMounts.
Argumento: dict "root" <contexto raiz> "key" <clave del servicio en values> "values" <values del servicio>.
*/}}
{{- define "idp.workload" -}}
{{- $r := .root -}}
{{- $g := $r.Values.global -}}
{{- $p := $g.platform -}}
{{- $s := $p.secrets -}}
{{- $app := .key | kebabcase -}}
{{- $env := list -}}
{{- $vols := list -}}
{{- $mounts := list -}}
{{- $kafkaApps := list "tenant-service" "document-service" "extraction-service" "audit-service" -}}
{{- /* JWT: issuer y audience obligatorios (sin default en application.yml) */ -}}
{{- if has $app (list "edge-gateway" "tenant-service" "audit-service") -}}
{{- $env = append $env (dict "name" "JWT_JWK_SET_URI" "value" $p.jwt.jwkSetUri) -}}
{{- $env = append $env (dict "name" "JWT_ISSUER_URI" "value" $p.jwt.issuerUri) -}}
{{- $env = append $env (dict "name" "JWT_AUDIENCE" "value" $app) -}}
{{- end -}}
{{- if eq $app "tenant-service" -}}
{{- $env = append $env (dict "name" "JWT_PLATFORM_ISSUER" "value" $p.jwt.platformIssuerUri) -}}
{{- $env = append $env (dict "name" "JWT_PLATFORM_JWK_SET_URI" "value" $p.jwt.platformJwkSetUri) -}}
{{- end -}}
{{- if eq $app "document-service" -}}
{{- $env = append $env (dict "name" "IDP_JWK_SET_URI" "value" $p.jwt.jwkSetUri) -}}
{{- $env = append $env (dict "name" "IDP_JWT_ISSUER_URI" "value" $p.jwt.issuerUri) -}}
{{- $env = append $env (dict "name" "IDP_JWT_AUDIENCE" "value" $app) -}}
{{- end -}}
{{- /* Kafka: mTLS con el Secret del KafkaUser de Strimzi (user.crt, user.key, ca.crt), nombrado como el servicio */ -}}
{{- if has $app $kafkaApps -}}
{{- $bootstrapVar := ternary "IDP_KAFKA_BOOTSTRAP" "KAFKA_BOOTSTRAP_SERVERS" (eq $app "document-service") -}}
{{- $env = append $env (dict "name" $bootstrapVar "value" $p.kafka.bootstrapServers) -}}
{{- $env = append $env (dict "name" "KAFKA_SECURITY_PROTOCOL" "value" "SSL") -}}
{{- $env = append $env (dict "name" "SPRING_KAFKA_SSL_BUNDLE" "value" "kafka") -}}
{{- $env = append $env (dict "name" "SPRING_SSL_BUNDLE_PEM_KAFKA_KEYSTORE_CERTIFICATE" "value" "file:/etc/idp/kafka/user.crt") -}}
{{- $env = append $env (dict "name" "SPRING_SSL_BUNDLE_PEM_KAFKA_KEYSTORE_PRIVATEKEY" "value" "file:/etc/idp/kafka/user.key") -}}
{{- $env = append $env (dict "name" "SPRING_SSL_BUNDLE_PEM_KAFKA_TRUSTSTORE_CERTIFICATE" "value" "file:/etc/idp/kafka/ca.crt") -}}
{{- $vols = append $vols (dict "name" "kafka-mtls" "secret" (dict "secretName" $app "defaultMode" 288)) -}}
{{- $mounts = append $mounts (dict "name" "kafka-mtls" "mountPath" "/etc/idp/kafka" "readOnly" true) -}}
{{- /* OpenBao: direccion y CA (HTTPS obligatorio); el token sale de un Secret */ -}}
{{- $prefix := get (dict "tenant-service" "IDP_OPENBAO" "document-service" "IDP_OPENBAO" "extraction-service" "EXTRACTION_OPENBAO" "audit-service" "IDP_AUDIT_KMS_OPENBAO") $app -}}
{{- $addrVar := ternary "OPENBAO_ADDR" (printf "%s_ADDRESS" $prefix) (eq $app "audit-service") -}}
{{- $tokenVar := ternary "OPENBAO_TOKEN" (printf "%s_TOKEN" $prefix) (has $app (list "audit-service" "extraction-service")) -}}
{{- $env = append $env (dict "name" $addrVar "value" $p.openbao.address) -}}
{{- $env = append $env (dict "name" $tokenVar "valueFrom" (dict "secretKeyRef" (dict "name" $s.openbao.name "key" "token"))) -}}
{{- $env = append $env (dict "name" (printf "%s_SSL_BUNDLE" $prefix) "value" "openbao") -}}
{{- $env = append $env (dict "name" "SPRING_SSL_BUNDLE_PEM_OPENBAO_TRUSTSTORE_CERTIFICATE" "value" "file:/etc/idp/openbao/ca.crt") -}}
{{- $vols = append $vols (dict "name" "openbao-ca" "secret" (dict "secretName" $p.openbao.caSecret)) -}}
{{- $mounts = append $mounts (dict "name" "openbao-ca" "mountPath" "/etc/idp/openbao" "readOnly" true) -}}
{{- end -}}
{{- /* Base de control (directorio de tenants) */ -}}
{{- if has $app (list "document-service" "extraction-service" "audit-service") -}}
{{- $cp := ternary "EXTRACTION_CONTROL" "IDP_CONTROL_DB" (eq $app "extraction-service") -}}
{{- $env = append $env (dict "name" (printf "%s_URL" $cp) "value" $p.controlDb.jdbcUrl) -}}
{{- $env = append $env (dict "name" (printf "%s_USERNAME" $cp) "valueFrom" (dict "secretKeyRef" (dict "name" $s.controlDb.name "key" "username"))) -}}
{{- $env = append $env (dict "name" (printf "%s_PASSWORD" $cp) "valueFrom" (dict "secretKeyRef" (dict "name" $s.controlDb.name "key" "password"))) -}}
{{- end -}}
{{- /* Datasource propio: tenant-service (tenant_db) y audit-service (control_db, rol sin UPDATE/DELETE) */ -}}
{{- if has $app (list "tenant-service" "audit-service") -}}
{{- $isAudit := eq $app "audit-service" -}}
{{- $dbSecret := ternary $s.controlDb.name $s.tenantDb.name $isAudit -}}
{{- $env = append $env (dict "name" "DB_HOST" "value" $p.controlDb.host) -}}
{{- $env = append $env (dict "name" "DB_PORT" "value" (toString $p.controlDb.port)) -}}
{{- $env = append $env (dict "name" "DB_NAME" "value" (ternary $p.controlDb.name $p.tenantDb.name $isAudit)) -}}
{{- $env = append $env (dict "name" "DB_USER" "valueFrom" (dict "secretKeyRef" (dict "name" $dbSecret "key" "username"))) -}}
{{- $env = append $env (dict "name" "DB_PASS" "valueFrom" (dict "secretKeyRef" (dict "name" $dbSecret "key" "password"))) -}}
{{- end -}}
{{- if eq $app "audit-service" -}}
{{- $env = append $env (dict "name" "AUDIT_WORM_BUCKET" "value" $p.worm.bucket) -}}
{{- $env = append $env (dict "name" "AUDIT_WORM_ENDPOINT" "value" $p.worm.endpoint) -}}
{{- $env = append $env (dict "name" "AUDIT_WORM_ACCESS_KEY" "valueFrom" (dict "secretKeyRef" (dict "name" $s.worm.name "key" "ACCESS_KEY_ID"))) -}}
{{- $env = append $env (dict "name" "AUDIT_WORM_SECRET_KEY" "valueFrom" (dict "secretKeyRef" (dict "name" $s.worm.name "key" "SECRET_ACCESS_KEY"))) -}}
{{- end -}}
{{- if eq $app "document-service" -}}
{{- $env = append $env (dict "name" "IDP_TENANT_DB_URL" "value" $p.tenantDb.jdbcUrlTemplate) -}}
{{- $env = append $env (dict "name" "IDP_TENANT_DB_USER" "valueFrom" (dict "secretKeyRef" (dict "name" $s.tenantDb.name "key" "username"))) -}}
{{- $env = append $env (dict "name" "IDP_TENANT_DB_PASSWORD" "valueFrom" (dict "secretKeyRef" (dict "name" $s.tenantDb.name "key" "password"))) -}}
{{- $env = append $env (dict "name" "IDP_DOWNLOAD_SECRET" "valueFrom" (dict "secretKeyRef" (dict "name" $s.downloadSecret.name "key" "secret"))) -}}
{{- $env = append $env (dict "name" "IDP_PUBLIC_BASE_URL" "value" (printf "https://%s" $g.domain)) -}}
{{- $env = append $env (dict "name" "IDP_RENDERER_URL" "value" $p.renderer.url) -}}
{{- $env = append $env (dict "name" "IDP_RENDERER_SSL_BUNDLE" "value" "renderer") -}}
{{- $env = append $env (dict "name" "SPRING_SSL_BUNDLE_PEM_RENDERER_KEYSTORE_CERTIFICATE" "value" "file:/etc/idp/renderer-client/tls.crt") -}}
{{- $env = append $env (dict "name" "SPRING_SSL_BUNDLE_PEM_RENDERER_KEYSTORE_PRIVATEKEY" "value" "file:/etc/idp/renderer-client/tls.key") -}}
{{- $env = append $env (dict "name" "SPRING_SSL_BUNDLE_PEM_RENDERER_TRUSTSTORE_CERTIFICATE" "value" "file:/etc/idp/renderer-client/ca.crt") -}}
{{- $vols = append $vols (dict "name" "renderer-client-tls" "secret" (dict "secretName" $p.renderer.clientTlsSecret "defaultMode" 288)) -}}
{{- $mounts = append $mounts (dict "name" "renderer-client-tls" "mountPath" "/etc/idp/renderer-client" "readOnly" true) -}}
{{- end -}}
{{- if has $app (list "document-service" "extraction-service") -}}
{{- $isExtraction := eq $app "extraction-service" -}}
{{- $env = append $env (dict "name" (ternary "EXTRACTION_STORAGE_ENDPOINT" "IDP_STORAGE_ENDPOINT" $isExtraction) "value" $p.storage.endpoint) -}}
{{- $env = append $env (dict "name" (ternary "EXTRACTION_S3_ACCESS_KEY" "IDP_STORAGE_ACCESS_KEY" $isExtraction) "valueFrom" (dict "secretKeyRef" (dict "name" $s.s3.name "key" "ACCESS_KEY_ID"))) -}}
{{- $env = append $env (dict "name" (ternary "EXTRACTION_S3_SECRET_KEY" "IDP_STORAGE_SECRET_KEY" $isExtraction) "valueFrom" (dict "secretKeyRef" (dict "name" $s.s3.name "key" "SECRET_ACCESS_KEY"))) -}}
{{- end -}}
{{- if eq $app "renderer" -}}
{{- $env = append $env (dict "name" "RENDERER_SSL_BUNDLE" "value" "server") -}}
{{- $env = append $env (dict "name" "SPRING_SSL_BUNDLE_PEM_SERVER_KEYSTORE_CERTIFICATE" "value" "file:/etc/idp/tls/tls.crt") -}}
{{- $env = append $env (dict "name" "SPRING_SSL_BUNDLE_PEM_SERVER_KEYSTORE_PRIVATEKEY" "value" "file:/etc/idp/tls/tls.key") -}}
{{- $env = append $env (dict "name" "SPRING_SSL_BUNDLE_PEM_SERVER_TRUSTSTORE_CERTIFICATE" "value" "file:/etc/idp/tls/ca.crt") -}}
{{- $vols = append $vols (dict "name" "renderer-tls" "secret" (dict "secretName" $p.renderer.serverTlsSecret "defaultMode" 288)) -}}
{{- $mounts = append $mounts (dict "name" "renderer-tls" "mountPath" "/etc/idp/tls" "readOnly" true) -}}
{{- end -}}
{{- /* chat-service: proveedor de IA (fallo cerrado: provider "none" = el chat responde 503), modelos fijados por version
       y clave del proveedor siempre desde Secret (opcional: sin clave y con provider "none" el pod arranca igual). */ -}}
{{- if eq $app "chat-service" -}}
{{- $llm := .values.llm | default dict -}}
{{- $env = append $env (dict "name" "CHAT_AI_PROVIDER" "value" ($llm.provider | default "none")) -}}
{{- if $llm.model -}}
{{- $env = append $env (dict "name" "CHAT_LLM_MODEL" "value" $llm.model) -}}
{{- end -}}
{{- if $llm.embeddingModel -}}
{{- $env = append $env (dict "name" "CHAT_EMBEDDING_MODEL" "value" $llm.embeddingModel) -}}
{{- end -}}
{{- if $llm.fallbackModel -}}
{{- $env = append $env (dict "name" "CHAT_LLM_FALLBACK_MODEL" "value" $llm.fallbackModel) -}}
{{- end -}}
{{- if $llm.endpoint -}}
{{- $env = append $env (dict "name" "SPRING_AI_OPENAI_BASE_URL" "value" $llm.endpoint) -}}
{{- end -}}
{{- $env = append $env (dict "name" "SPRING_AI_OPENAI_API_KEY" "valueFrom" (dict "secretKeyRef" (dict "name" $s.llm.name "key" "apiKey" "optional" true))) -}}
{{- end -}}
{{- /* dev-mode solo con global.devMode=true (unicamente values-local.yaml; lo verifica tools/ci/check_dev_mode.sh) */ -}}
{{- if $g.devMode -}}
{{- $env = append $env (dict "name" "IDP_SECURITY_DEV_MODE" "value" "true") -}}
{{- end -}}
{{- $extra := .values.extraEnv | default list -}}
env: {{ concat $env $extra | toJson }}
extraVolumes: {{ $vols | toJson }}
extraVolumeMounts: {{ $mounts | toJson }}
{{- end -}}
