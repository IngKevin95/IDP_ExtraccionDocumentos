#!/usr/bin/env python3
"""Valida la topologia Kafka contra contracts/events/topology.yaml (ADR 0029, SEC-052).

Reglas (cada violacion es un ERROR):
  1. Todo eventType del catalogo (contracts/events/*.schema.json) tiene entrada en topology.yaml.
  2. Cada topico de la topologia esta declarado en topics.yaml y su clave coincide con la del topico.
  3. Solo audit.events admite varios productores (senales); los demas topicos son de un unico productor.
  4. Write ACL de cada servicio == exactamente los topicos de los eventTypes que produce (mas los *-dlt de los
     topicos de dominio que consume si usa EventErrorHandlers.deadLetter). Nadie escribe en un topico ajeno.
  5. Cada consumidor (@KafkaListener en main, incluidos los listeners de libs/security que cablea) tiene Read y
     Describe sobre los topicos que escucha, y su grupo de consumo.
  6. Servicios sin KafkaUser (renderer, edge-gateway) no producen ni escuchan.
  7. Ningun fuente ni application.yml de main usa el topico retirado dominio.documentos.

Uso: check_kafka_topics.py            valida el repositorio
     check_kafka_topics.py --self-test  prueba la propia validacion (debe fallar al romper cada regla)
"""
import copy
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOPOLOGY = ROOT / "contracts/events/topology.yaml"
SCHEMAS = ROOT / "contracts/events"
TOPICS = ROOT / "deploy/platform/kafka/topics.yaml"
USERS = ROOT / "deploy/platform/kafka/users.yaml"
SHARED_TOPIC = "audit.events"
TENANT_TOPIC = "idp.tenant.events"
RETIRED_TOPIC = "dominio.documentos"
# eventTypes reservados en la topologia sin esquema aun en el catalogo.
RESERVED = {"tenant.credenciales_rotadas"}
NAME = r"[\w.\-]+"
LISTENER_RE = re.compile(r'@KafkaListener\s*\(\s*topics\s*=\s*"((?:[^"\\]|\\.)*)"')


def parse_topology(text):
    section, topics, events = None, {}, {}
    for line in text.splitlines():
        if re.match(r"^topics:\s*$", line):
            section = "topics"
            continue
        if re.match(r"^eventTypes:\s*$", line):
            section = "events"
            continue
        m = re.match(rf"^  ({NAME}):\s*\{{(.*)\}}\s*$", line)
        if not m or section is None:
            continue
        body = m.group(2)
        key = re.search(r"\bkey:\s*(\w+)", body)
        if section == "topics":
            topics[m.group(1)] = {"key": key.group(1) if key else None}
        else:
            topic = re.search(rf"\btopic:\s*({NAME})", body)
            prod = re.search(r"\bproducers:\s*\[([^\]]*)\]", body)
            events[m.group(1)] = {
                "topic": topic.group(1) if topic else None,
                "producers": [p.strip() for p in prod.group(1).split(",") if p.strip()] if prod else [],
                "key": key.group(1) if key else None,
            }
    return topics, events


def kafka_users(text):
    users = {}
    for doc in text.split("\n---"):
        m = re.search(r"^  name: (\S+)", doc, re.M)
        if not m or "kind: KafkaUser" not in doc:
            continue
        acls = re.findall(rf"type: topic, name: ({NAME}), patternType: literal \}}\s*\n\s*operation: (\w+)", doc)
        groups = re.findall(rf"type: group, name: ({NAME}), patternType: (\w+) \}}\s*\n\s*operation: Read", doc)
        users[m.group(1)] = {"acls": acls, "groups": set(groups)}
    return users


def topics_of_listener(expr, topology_events, all_topics, errors, where):
    """Topicos que escucha un @KafkaListener(topics = expr) segun su default (SpEL de EventTopology o literal)."""
    if "allTopics()" in expr:
        return set(all_topics)
    found = set()
    spel = re.search(r"topics?For\(([^)]*)\)", expr)
    if spel:
        for et in re.findall(r"'([a-z0-9_.]+)'", spel.group(1)):
            if et not in topology_events:
                errors.append(f"{where}: escucha el eventType '{et}' sin entrada en topology.yaml")
            else:
                found.add(topology_events[et]["topic"])
        return found
    default = re.sub(r"\$\{[^:}]+:([^}]*)\}", r"\1", expr.strip())
    return {t.strip() for t in default.split(",") if re.fullmatch(NAME, t.strip())}


def load_model():
    topo_topics, events = parse_topology(TOPOLOGY.read_text(encoding="utf-8"))
    all_topics = {e["topic"] for e in events.values() if e["topic"]}
    errors = []
    catalog = {re.sub(r"\.v\d+\.schema\.json$", "", f.name) for f in SCHEMAS.glob("*.schema.json")}
    declared = set(re.findall(r"^  name: (" + NAME + r")$", TOPICS.read_text(encoding="utf-8"), re.M))
    users = kafka_users(USERS.read_text(encoding="utf-8"))
    lib_listeners = {}
    for f in (ROOT / "libs/security/src/main/java").rglob("*KafkaListener.java"):
        src = f.read_text(encoding="utf-8")
        topics = set()
        for m in LISTENER_RE.finditer(src):
            topics |= topics_of_listener(m.group(1), events, all_topics, errors, f.name)
        lib_listeners[f.stem] = topics
    services = {}
    for svc_dir in sorted((ROOT / "services").iterdir()):
        if not (svc_dir / "src/main").is_dir():
            continue
        main = svc_dir / "src/main"
        listened, retired = set(), []
        domain_listener = False
        dlt = False
        lib_used = set()
        for f in main.rglob("*.java"):
            src = f.read_text(encoding="utf-8")
            for m in LISTENER_RE.finditer(src):
                domain_listener = True
                listened |= topics_of_listener(m.group(1), events, all_topics, errors, f"{svc_dir.name}/{f.name}")
            if "EventErrorHandlers.deadLetter(" in src:
                dlt = True
            for cls in lib_listeners:
                if re.search(rf"new\s+(?:com\.idp\.security\.)?{cls}\(", src):
                    lib_used.add(cls)
            if RETIRED_TOPIC in src:
                retired.append(f.name)
        for f in list(main.rglob("*.yml")) + list(main.rglob("*.yaml")) + list(main.rglob("*.properties")):
            if RETIRED_TOPIC in f.read_text(encoding="utf-8"):
                retired.append(f.name)
        for cls in lib_used:
            listened |= lib_listeners[cls]
        services[svc_dir.name] = {"listened": listened, "domain_listener": domain_listener, "dlt": dlt,
                                  "lib_listeners": lib_used, "retired": retired}
    return {"topo_topics": topo_topics, "events": events, "catalog": catalog, "declared": declared,
            "users": users, "services": services, "load_errors": errors}


def validate(model):
    errors = list(model["load_errors"])
    events, topo_topics = model["events"], model["topo_topics"]
    declared, users, services = model["declared"], model["users"], model["services"]
    # 1. catalogo completo
    for et in sorted(model["catalog"] - set(events)):
        errors.append(f"eventType '{et}' del catalogo sin entrada en topology.yaml")
    for et in sorted(set(events) - model["catalog"] - RESERVED):
        errors.append(f"topology.yaml declara '{et}' sin esquema en contracts/events")
    # 2 y 3. coherencia de topicos y productores
    for et, e in sorted(events.items()):
        if not e["topic"] or not e["producers"] or not e["key"]:
            errors.append(f"{et}: entrada incompleta (topic, producers y key son obligatorios)")
            continue
        if e["topic"] not in topo_topics:
            errors.append(f"{et}: topico '{e['topic']}' sin definicion en la seccion topics de topology.yaml")
        elif topo_topics[e["topic"]]["key"] != e["key"]:
            errors.append(f"{et}: key '{e['key']}' distinta de la del topico '{e['topic']}'")
        if e["topic"] not in declared:
            errors.append(f"{et}: topico '{e['topic']}' no declarado en topics.yaml")
        if len(e["producers"]) > 1 and e["topic"] != SHARED_TOPIC:
            errors.append(f"{et}: varios productores solo se admiten en {SHARED_TOPIC} (topico '{e['topic']}')")
        for p in e["producers"]:
            if p not in users:
                errors.append(f"{et}: productor '{p}' sin KafkaUser en users.yaml")
    # 4. Write exacto
    produced = {}
    for et, e in events.items():
        for p in e["producers"]:
            produced.setdefault(p, set()).add(e["topic"])
    for svc, u in sorted(users.items()):
        writes = {n for n, o in u["acls"] if o == "Write"}
        expected = set(produced.get(svc, set()))
        info = services.get(svc)
        if info and info["dlt"]:
            expected |= {t + "-dlt" for t in info["listened"] if t not in (SHARED_TOPIC, TENANT_TOPIC)}
        for t in sorted(expected - writes):
            errors.append(f"{svc}: falta Write sobre '{t}' (topico que produce)")
        for t in sorted(writes - expected):
            errors.append(f"{svc}: Write sobre el topico ajeno '{t}' (solo debe escribir {sorted(expected)})")
        for t in sorted(expected | writes):
            if t not in declared:
                errors.append(f"{svc}: topico '{t}' no declarado en topics.yaml")
        for t in sorted(expected):
            if "Describe" not in {o for n, o in u["acls"] if n == t}:
                errors.append(f"{svc}: falta Describe sobre '{t}'")
    for svc in sorted(produced):
        if svc not in users:
            errors.append(f"{svc}: produce eventos pero no tiene KafkaUser")
    # 5 y 6. consumidores
    for svc, info in sorted(services.items()):
        u = users.get(svc)
        if u is None:
            if info["listened"]:
                errors.append(f"{svc}: escucha {sorted(info['listened'])} pero no tiene KafkaUser")
            continue
        for t in sorted(info["listened"]):
            ops = {o for n, o in u["acls"] if n == t}
            if t not in declared:
                errors.append(f"{svc}: topico '{t}' no declarado en topics.yaml")
            if "Read" not in ops:
                errors.append(f"{svc}: sin Read sobre el topico '{t}' que escucha")
            if "Describe" not in ops:
                errors.append(f"{svc}: sin Describe sobre el topico '{t}' que escucha")
        if info["domain_listener"] and not any(g == svc for g, _ in u["groups"]):
            errors.append(f"{svc}: falta ACL del grupo de consumo '{svc}'")
        for cls, grp in (("AccesoRevocadoKafkaListener", "acceso-revocado"),
                         ("TenantPoolEvictionKafkaListener", "pool-evict")):
            if cls in info["lib_listeners"] and (f"{svc}-{grp}-", "prefix") not in u["groups"]:
                errors.append(f"{svc}: falta ACL de grupo prefijo '{svc}-{grp}-'")
        for f in info["retired"]:
            errors.append(f"{svc}: {f} referencia el topico retirado '{RETIRED_TOPIC}'")
    for f in model["services"].get("renderer", {}).get("retired", []):
        errors.append(f"renderer: {f} referencia el topico retirado '{RETIRED_TOPIC}'")
    return errors


def self_test():
    """Rompe una regla a la vez sobre el modelo real y exige que validate() la detecte."""
    base = load_model()
    failures = []
    baseline = validate(base)
    if baseline:
        failures.append(f"el modelo base ya tiene errores: {baseline[:3]}")

    def mutate(name, fn, expected):
        m = copy.deepcopy(base)
        fn(m)
        errs = validate(m)
        if not any(expected in e for e in errs):
            failures.append(f"{name}: no se detecto (se esperaba '{expected}'); errores: {errs[:3]}")

    mutate("eventType sin entrada", lambda m: m["events"].pop("revision.completada"),
           "revision.completada' del catalogo sin entrada")
    mutate("write ajeno", lambda m: m["users"]["quality-service"]["acls"].append(("review.events", "Write")),
           "Write sobre el topico ajeno 'review.events'")
    mutate("write faltante", lambda m: m["users"]["review-service"]["acls"].remove(("review.events", "Write")),
           "falta Write sobre 'review.events'")
    mutate("suplantacion en document.events",
           lambda m: m["users"]["extraction-service"]["acls"].append(("document.events", "Write")),
           "Write sobre el topico ajeno 'document.events'")
    mutate("consumidor sin Read", lambda m: m["users"]["document-service"]["acls"].remove(("review.events", "Read")),
           "sin Read sobre el topico 'review.events'")
    mutate("topico sin declarar", lambda m: m["declared"].discard("quality.events"),
           "topico 'quality.events' no declarado")
    mutate("dos productores fuera de auditoria",
           lambda m: m["events"]["revision.escalada"]["producers"].append("quality-service"),
           "varios productores solo se admiten en audit.events")
    mutate("key incoherente", lambda m: m["events"]["documento.recibido"].update(key="tenantId"),
           "key 'tenantId' distinta")
    mutate("servicio sin usuario escucha",
           lambda m: m["services"]["renderer"].update(listened={"document.events"}),
           "no tiene KafkaUser")
    mutate("topico retirado", lambda m: m["services"]["review-service"]["retired"].append("X.java"),
           "topico retirado")
    mutate("eventType escuchado desconocido", lambda m: m["load_errors"].append("x: escucha el eventType 'zzz'"),
           "escucha el eventType")
    for f in failures:
        print("SELF-TEST FALLO", f)
    print("SELF-TEST OK" if not failures else f"SELF-TEST: {len(failures)} fallo(s)")
    return 1 if failures else 0


def main():
    if "--self-test" in sys.argv[1:]:
        return self_test()
    errors = validate(load_model())
    for e in errors:
        print("ERROR", e)
    print("OK" if not errors else f"{len(errors)} error(es)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
