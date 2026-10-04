# Despliegue Plataforma IDP

Contiene los manifiestos de infraestructura y aplicación.

## Estructura

- `helm/idp-common/`: Library chart con plantillas (seguras por defecto, non-root, readOnlyRootFilesystem).
- `helm/idp/`: Umbrella chart con los 10 microservicios (dependen de idp-common).
- `platform/`: Valores para los operadores base (Strimzi, CloudNativePG, etc) y políticas de Kyverno.
- `argocd/`: Aplicaciones de despliegue continuo.

## Desarrollo Local (Docker Compose)

En la raíz del proyecto, `docker-compose.yml` inicia:
- Postgres con pgvector
- Kafka KRaft
- Keycloak
- OpenBao
- SeaweedFS
- Redis

Ejecución: `docker compose up -d`

## Instalación en Kubernetes (kind / k3s)

1. Instalar operadores desde `platform/`.
2. Aplicar políticas Kyverno: `kubectl apply -f deploy/platform/kyverno-policies.yaml`.
3. Desplegar aplicación con ArgoCD: `kubectl apply -f deploy/argocd/idp-project.yaml`.

Para nubes (AWS/GCP/Azure) o OnPremise, ArgoCD usará los archivos `values-{env}.yaml` correspondientes inyectando credenciales por ExternalSecrets/OpenBao.
