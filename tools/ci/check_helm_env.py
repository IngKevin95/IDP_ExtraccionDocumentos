#!/usr/bin/env python3
"""Verifica que el chart de Helm inyecte a cada servicio todas las variables obligatorias.

Los application.yml usan placeholders sin default (`${VAR}`), fail-closed por diseno: si el pod no recibe la
variable no arranca. Este verificador:
  1. extrae de services/*/src/main/resources/application.yml las `${VAR}` sin default, mas las reglas de
     fail-fast conocidas (FAILFAST: variables con default vacio que el codigo exige) y el cableado Kafka mTLS;
  2. renderiza el chart (helm template) para cada values-*.yaml y lee env/envFrom del primer contenedor;
  3. falla con `servicio X: falta VAR` por cada variable no inyectada, y con `servicio X: secret S no declarado`
     si un secretKeyRef/volumen apunta a un Secret que ningun ExternalSecret, values o KafkaUser declara;
  4. exige IDP_STORAGE_PROVIDER e IDP_KMS_PROVIDER en los Deployments que usan almacenamiento o KMS, con un valor
     del conjunto permitido (ADR 0032) igual a global.adapters.*, y que values-onprem.yaml no nombre minio ni thales.

Uso: python tools/ci/check_helm_env.py [--self-test]. Ruta de helm: variable HELM_BIN (default: helm en PATH).
Dependencia: PyYAML.
"""
import copy
import glob
import os
import re
import subprocess
import sys

import yaml

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CHART = os.path.join(ROOT, "deploy", "helm", "idp")
SERVICES_DIR = os.path.join(ROOT, "services")
KAFKA_USERS = os.path.join(ROOT, "deploy", "platform", "kafka", "users.yaml")

PLACEHOLDER = re.compile(r"\$\{([^}:]+)(:[^}]*)?\}")
ENV_NAME = re.compile(r"^[A-Z][A-Z0-9_]*$")

# Variables con default vacio que el codigo exige en produccion (fail-fast conocido), por servicio.
_TENANT_CTX = ["IDP_CONTROL_DB_URL", "IDP_CONTROL_DB_USERNAME", "IDP_CONTROL_DB_PASSWORD", "IDP_TENANT_DB_URL",
               "IDP_OPENBAO_ADDRESS", "IDP_OPENBAO_TOKEN"]
FAILFAST = {
    "document-service": _TENANT_CTX,
    "chat-service": _TENANT_CTX,
    "review-service": _TENANT_CTX,
    "notification-service": _TENANT_CTX,
    "quality-service": ["QUALITY_BLIND_SEED", "IDP_CONTROL_DB_URL"],
    "extraction-service": ["EXTRACTION_CONTROL_URL", "EXTRACTION_OPENBAO_ADDRESS", "OPENBAO_TOKEN"],
    "audit-service": ["OPENBAO_ADDR", "OPENBAO_TOKEN", "AUDIT_WORM_BUCKET", "AUDIT_WORM_ENDPOINT"],
}


# Selector de proveedor (ADR 0032): variable de entorno -> (clave de global.adapters, valores permitidos, servicios).
_KMS_APPS = ["document-service", "extraction-service", "audit-service", "chat-service", "review-service",
             "notification-service"]
PROVIDER_ENV = {
    "IDP_STORAGE_PROVIDER": ("storage", {"s3", "gcs", "azure-blob"},
                             [a for a in _KMS_APPS if a != "notification-service"]),
    "IDP_KMS_PROVIDER": ("kms", {"openbao", "aws-kms", "gcp-kms", "azure-keyvault"}, _KMS_APPS),
}
# values-onprem.yaml usa solo s3 y openbao (ADR 0020): minio y thales contradicen la decision.
ONPREM_FORBIDDEN = re.compile(r"minio|thales", re.IGNORECASE)


def helm_bin():
    return os.environ.get("HELM_BIN", "helm")


def walk(node, path=""):
    if isinstance(node, dict):
        for k, v in node.items():
            yield from walk(v, f"{path}.{k}" if path else str(k))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from walk(v, f"{path}[{i}]")
    elif isinstance(node, str):
        yield path, node


def required_vars(service_dir):
    """Devuelve {VAR: motivo} obligatorias del servicio segun su application.yml."""
    name = os.path.basename(service_dir)
    with open(os.path.join(service_dir, "src", "main", "resources", "application.yml"), encoding="utf-8") as f:
        docs = [d for d in yaml.safe_load_all(f) if d]
    req = {}
    for doc in docs:
        for path, value in walk(doc):
            for m in PLACEHOLDER.finditer(value):
                var, default = m.group(1), m.group(2)
                if ENV_NAME.match(var) and default is None:
                    req.setdefault(var, f"placeholder sin default en {path}")
            if path.endswith("jwk-set-uri"):
                m = PLACEHOLDER.search(value)
                if m and ENV_NAME.match(m.group(1)):
                    req.setdefault(m.group(1), "JWKS (default localhost no sirve en cluster)")
            if path == "spring.kafka.bootstrap-servers":
                m = PLACEHOLDER.search(value)
                if m and ENV_NAME.match(m.group(1)):
                    req.setdefault(m.group(1), "Kafka bootstrap (default localhost no sirve en cluster)")
                req.setdefault("SPRING_KAFKA_SSL_BUNDLE", "Kafka mTLS (protocol SSL exige bundle)")
    for var in FAILFAST.get(name, []):
        req.setdefault(var, "fail-fast conocido (default vacio)")
    return req


def deep_merge(a, b):
    for k, v in b.items():
        if isinstance(v, dict) and isinstance(a.get(k), dict):
            deep_merge(a[k], v)
        else:
            a[k] = copy.deepcopy(v)
    return a


def load_values(values_file):
    merged = {}
    for fn in (os.path.join(CHART, "values.yaml"), values_file):
        with open(fn, encoding="utf-8") as f:
            deep_merge(merged, yaml.safe_load(f) or {})
    return merged


def known_secrets(values, rendered_docs):
    names = set()
    p = values.get("global", {}).get("platform", {})
    for spec in p.get("secrets", {}).values():
        names.add(spec["name"])
    for key in ("caSecret",):
        if p.get("openbao", {}).get(key):
            names.add(p["openbao"][key])
    for key in ("serverTlsSecret", "clientTlsSecret"):
        if p.get("renderer", {}).get(key):
            names.add(p["renderer"][key])
    for d in rendered_docs:
        if d.get("kind") == "ExternalSecret":
            names.add(d["metadata"]["name"])
    with open(KAFKA_USERS, encoding="utf-8") as f:
        for d in yaml.safe_load_all(f):
            if d and d.get("kind") == "KafkaUser":
                names.add(d["metadata"]["name"])
    return names


def render(values_file):
    out = subprocess.run([helm_bin(), "template", "idp", CHART, "-f", values_file], capture_output=True,
                         text=True, check=False)
    if out.returncode != 0:
        sys.exit(f"helm template fallo con {os.path.basename(values_file)}:\n{out.stderr}")
    return [d for d in yaml.safe_load_all(out.stdout) if d]


def check(docs, values, requirements):
    """Devuelve lista de errores para un render."""
    errors = []
    secrets = known_secrets(values, docs)
    deployments = {}
    for d in docs:
        if d.get("kind") == "Deployment":
            deployments[d["metadata"]["labels"]["app.kubernetes.io/name"]] = d
    for svc, req in sorted(requirements.items()):
        dep = deployments.get(svc)
        if dep is None:
            errors.append(f"servicio {svc}: sin Deployment en el render")
            continue
        pod = dep["spec"]["template"]["spec"]
        container = pod["containers"][0]
        env = container.get("env", [])
        injected = {e["name"] for e in env}
        for var, why in sorted(req.items()):
            if var not in injected:
                errors.append(f"servicio {svc}: falta {var} ({why})")
        errors += provider_errors(svc, env, values)
        refs = {e["valueFrom"]["secretKeyRef"]["name"] for e in env if "secretKeyRef" in e.get("valueFrom", {})}
        refs |= {ef["secretRef"]["name"] for ef in container.get("envFrom", []) if "secretRef" in ef}
        refs |= {v["secret"]["secretName"] for v in pod.get("volumes", []) if "secret" in v}
        for ref in sorted(refs - secrets):
            errors.append(f"servicio {svc}: secret {ref} no declarado (ni ExternalSecret, ni values, ni KafkaUser)")
    return errors


def provider_errors(svc, env, values):
    """Valida IDP_STORAGE_PROVIDER / IDP_KMS_PROVIDER del servicio contra el conjunto permitido y global.adapters."""
    errors = []
    literal = {e["name"]: e.get("value") for e in env}
    adapters = values.get("global", {}).get("adapters", {})
    for var, (key, allowed, apps) in sorted(PROVIDER_ENV.items()):
        if svc not in apps:
            continue
        if var not in literal:
            errors.append(f"servicio {svc}: falta {var} (proveedor {key}, ADR 0032)")
        elif literal[var] not in allowed:
            errors.append(f"servicio {svc}: {var}={literal[var]!r} valor no permitido ({'|'.join(sorted(allowed))})")
        elif literal[var] != adapters.get(key):
            errors.append(f"servicio {svc}: {var}={literal[var]!r} no coincide con global.adapters.{key}="
                          f"{adapters.get(key)!r}")
    return errors


def onprem_errors(label, text):
    """values-onprem.yaml no puede nombrar minio ni thales (AC-03)."""
    if not label.startswith("values-onprem"):
        return []
    return [f"{label}: nombra {m.group(0).lower()} (usar s3 y openbao, ADR 0020)"
            for m in sorted(set(ONPREM_FORBIDDEN.finditer(text)), key=lambda m: m.group(0).lower())]


def all_requirements():
    reqs = {}
    for d in sorted(glob.glob(os.path.join(SERVICES_DIR, "*", ""))):
        d = d.rstrip("/\\")
        if os.path.isfile(os.path.join(d, "src", "main", "resources", "application.yml")):
            reqs[os.path.basename(d)] = required_vars(d)
    return reqs


def values_files():
    return sorted(glob.glob(os.path.join(CHART, "values-*.yaml")))


def run():
    reqs = all_requirements()
    failed = False
    for vf in values_files():
        label = os.path.basename(vf)
        errors = check(render(vf), load_values(vf), reqs)
        with open(vf, encoding="utf-8") as f:
            errors += onprem_errors(label, f.read())
        for e in errors:
            print(f"[{label}] {e}")
        failed |= bool(errors)
        if not errors:
            print(f"[{label}] OK ({len(reqs)} servicios)")
    return 1 if failed else 0


def self_test():
    reqs = all_requirements()
    vf = os.path.join(CHART, "values-aws.yaml")
    docs = render(vf)
    values = load_values(vf)
    base = check(docs, values, reqs)
    if base:
        print("self-test: el render base ya falla:\n  " + "\n  ".join(base))
        return 1

    def dep(ds, svc):
        return next(d for d in ds if d.get("kind") == "Deployment"
                    and d["metadata"]["labels"]["app.kubernetes.io/name"] == svc)

    def drop_var(ds):
        c = dep(ds, "chat-service")["spec"]["template"]["spec"]["containers"][0]
        c["env"] = [e for e in c["env"] if e["name"] != "IDP_JWT_ISSUER_URI"]
        return "falta IDP_JWT_ISSUER_URI"

    def bad_secret(ds):
        c = dep(ds, "review-service")["spec"]["template"]["spec"]["containers"][0]
        for e in c["env"]:
            if "secretKeyRef" in e.get("valueFrom", {}):
                e["valueFrom"]["secretKeyRef"]["name"] = "secret-inexistente"
                break
        return "secret secret-inexistente no declarado"

    def drop_deployment(ds):
        ds[:] = [d for d in ds if not (d.get("kind") == "Deployment"
                 and d["metadata"]["labels"]["app.kubernetes.io/name"] == "quality-service")]
        return "sin Deployment"

    def drop_failfast(ds):
        c = dep(ds, "quality-service")["spec"]["template"]["spec"]["containers"][0]
        c["env"] = [e for e in c["env"] if e["name"] != "QUALITY_BLIND_SEED"]
        return "falta QUALITY_BLIND_SEED"

    def drop_provider(ds):
        c = dep(ds, "chat-service")["spec"]["template"]["spec"]["containers"][0]
        c["env"] = [e for e in c["env"] if e["name"] != "IDP_STORAGE_PROVIDER"]
        return "falta IDP_STORAGE_PROVIDER"

    def unknown_provider(ds):
        c = dep(ds, "notification-service")["spec"]["template"]["spec"]["containers"][0]
        for e in c["env"]:
            if e["name"] == "IDP_KMS_PROVIDER":
                e["value"] = "thales"
        return "valor no permitido"

    def incoherent_provider(ds):
        c = dep(ds, "document-service")["spec"]["template"]["spec"]["containers"][0]
        for e in c["env"]:
            if e["name"] == "IDP_STORAGE_PROVIDER":
                e["value"] = "gcs"
        return "no coincide con global.adapters.storage"

    ok = True
    for mutation in (drop_var, bad_secret, drop_deployment, drop_failfast, drop_provider, unknown_provider,
                     incoherent_provider):
        mutated = copy.deepcopy(docs)
        expected = mutation(mutated)
        errors = check(mutated, values, reqs)
        hit = any(expected in e for e in errors)
        print(f"self-test {mutation.__name__}: {'OK (detectado)' if hit else 'FALLO (no detectado)'}")
        ok &= hit
    with open(os.path.join(CHART, "values-onprem.yaml"), encoding="utf-8") as f:
        clean = onprem_errors("values-onprem.yaml", f.read())
    for name, text in (("minio", "storage: minio"), ("thales", "kms: Thales")):
        hit = any(name in e for e in onprem_errors("values-onprem.yaml", text))
        print(f"self-test onprem_{name}: {'OK (detectado)' if hit else 'FALLO (no detectado)'}")
        ok &= hit
    if clean:
        print("self-test: values-onprem.yaml real ya falla: " + "; ".join(clean))
        ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(self_test() if "--self-test" in sys.argv[1:] else run())
