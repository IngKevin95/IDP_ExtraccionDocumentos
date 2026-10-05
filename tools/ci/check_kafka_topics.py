#!/usr/bin/env python3
"""Valida que todo topico usado por un servicio exista en topics.yaml y tenga ACL del servicio.

Fuentes de topicos por servicio: claves *topic/*topics de application.yml (main) y
@KafkaListener(topics = "${k:default}") en main del servicio. Los servicios con la lib
security (listeners de revocacion/desalojo) heredan su topico por defecto.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOPICS = ROOT / "deploy/platform/kafka/topics.yaml"
USERS = ROOT / "deploy/platform/kafka/users.yaml"
# Servicios que activan los listeners de libs/security (ver ADR de revocacion).
SECURITY_LISTENER_SERVICES = {"document-service", "audit-service", "extraction-service", "tenant-service"}
SECURITY_DEFAULT_TOPIC_RE = re.compile(r'revocation-topic:([\w.\-]+)\}')
NAME = r"[\w.\-]+"


def default_of(value):
    value = value.strip().strip("'\"")
    m = re.fullmatch(r"\$\{[^:}]+:([^}]*)\}", value)
    return m.group(1) if m else value


def split_topics(raw):
    return [t.strip() for t in raw.split(",") if re.fullmatch(NAME, t.strip())]


def yml_topics(text):
    found = set()
    for m in re.finditer(r"^\s*[\w\-]*topics?:\s*(.+?)\s*$", text, re.M):
        found.update(split_topics(default_of(m.group(1))))
    return found


def java_topics(src_dir):
    found = set()
    for f in src_dir.rglob("*.java"):
        for m in re.finditer(r'topics\s*=\s*"([^"]+)"', f.read_text(encoding="utf-8")):
            raw = m.group(1)
            if raw.startswith("#{"):
                continue  # SpEL: cubierto por la clave de application.yml
            found.update(split_topics(default_of(raw)))
    return found


def kafka_users(text):
    users = {}
    for doc in text.split("\n---"):
        m = re.search(r"^  name: (\S+)", doc, re.M)
        if not m:
            continue
        acls = re.findall(r"type: topic, name: ([\w.\-]+), patternType: literal \}\s*\n\s*operation: (\w+)", doc)
        users[m.group(1)] = acls
    return users


def main():
    declared = set(re.findall(r"^  name: ([\w.\-]+)$", TOPICS.read_text(encoding="utf-8"), re.M))
    users = kafka_users(USERS.read_text(encoding="utf-8"))
    sec_default = None
    for f in (ROOT / "libs/security/src/main/java").rglob("*.java"):
        m = SECURITY_DEFAULT_TOPIC_RE.search(f.read_text(encoding="utf-8"))
        if m:
            sec_default = m.group(1)
    errors = []
    if not sec_default:
        errors.append("no se encontro el topico por defecto de revocacion en libs/security")
    for svc_dir in sorted((ROOT / "services").iterdir()):
        yml = svc_dir / "src/main/resources/application.yml"
        if not yml.exists():
            continue
        svc = svc_dir.name
        topics = yml_topics(yml.read_text(encoding="utf-8")) | java_topics(svc_dir / "src/main/java")
        if svc in SECURITY_LISTENER_SERVICES and sec_default:
            topics.add(sec_default)
        # Servicios consumidores de dominio sin clave explicita en yml: los listeners ya cuentan via java_topics.
        for t in sorted(topics):
            if t not in declared:
                errors.append(f"{svc}: topico '{t}' no declarado en topics.yaml")
            if t in declared and not any(n == t for n, _ in users.get(svc, [])):
                errors.append(f"{svc}: sin ACL sobre el topico '{t}' en users.yaml")
    if SECURITY_LISTENER_SERVICES and sec_default:
        for svc in SECURITY_LISTENER_SERVICES:
            ops = {o for n, o in users.get(svc, []) if n == sec_default}
            if "Read" not in ops:
                errors.append(f"{svc}: falta Read sobre '{sec_default}'")
            for g in ("acceso-revocado", "pool-evict"):
                text = USERS.read_text(encoding="utf-8")
                if not re.search(rf"name: {svc}-{g}-, patternType: prefix", text):
                    errors.append(f"{svc}: falta ACL de grupo prefijo '{svc}-{g}-'")
    if sec_default and "Write" not in {o for n, o in users.get("tenant-service", []) if n == sec_default}:
        errors.append(f"tenant-service: falta Write sobre '{sec_default}'")
    for e in errors:
        print("ERROR", e)
    print("OK" if not errors else f"{len(errors)} error(es)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
