#!/usr/bin/env python3
"""Valida la topologia Kafka contra contracts/events/topology.yaml (ADR 0031, SEC-052).

Requiere PyYAML (pip install pyyaml). Todo YAML se parsea con yaml.safe_load; las ACL se normalizan a tuplas
(resourceType, name, patternType, operation) y cualquier recurso que el parser no interprete es un ERROR.

Reglas (cada violacion es un ERROR etiquetado con su id; el --self-test exige una mutacion por cada id):
  R01 Todo eventType del catalogo (contracts/events/*.schema.json) tiene entrada en topology.yaml y viceversa.
  R02 Cada topico de la topologia esta definido, declarado en topics.yaml, con kind valido y clave coherente.
  R03 Varios productores (por eventType o por topico) solo en topicos kind signal o control; los de dominio
      tienen un unico productor.
  R04 Separacion senal/control/estado: un topico signal (multi-productor abierto) solo contiene senales
      (seguridad.*, chat.*); un topico control solo contiene eventos de control (legalhold.*, consumo.*,
      auditoria.*). Nunca estado ni control en un topico multi-productor abierto.
  R05 Write de cada servicio == exactamente los topicos de los eventTypes que produce (mas los *-dlt de los
      topicos de dominio que consume si usa EventErrorHandlers.deadLetter). Nadie escribe en un topico ajeno.
  R06 Read solo sobre los topicos que el servicio escucha (@KafkaListener, incluidos los de libs/security) y
      obligatorio sobre ellos.
  R07 Describe obligatorio sobre cada topico que lee o escribe y prohibido sobre cualquier otro.
  R08 Ninguna ACL de recurso type cluster.
  R09 patternType de topicos siempre literal; prefix solo en grupos (y solo los esperados).
  R10 Ningun nombre de recurso "*".
  R11 Operaciones solo dentro de {Write, Read, Describe} (nada de All, Create, Alter, Delete...).
  R12 Recurso o ACL que el parser no puede interpretar (sin type/name, type desconocido, operacion ausente).
  R13 ACL de grupo de consumo: la esperada de cada servicio (literal o prefijo) y ninguna otra.
  R14 KafkaUser con authentication tls y authorization simple.
  R15 Listeners del cluster solo tls con authentication tls (ningun plain).
  R16 Cluster con authorization simple y sin superUsers.
  R17 Cluster con auto.create.topics.enable "false" y allow.everyone.if.no.acl.found "false".
  R18 Ningun fuente ni application.yml de main usa el topico retirado dominio.documentos.
  R19 Servicios sin KafkaUser (renderer, edge-gateway) no producen ni escuchan; todo productor tiene KafkaUser.
  R20 Todo eventType que un listener declara existe en topology.yaml.

Uso: check_kafka_topics.py            valida el repositorio
     check_kafka_topics.py --self-test  prueba la propia validacion (una mutacion por regla, debe detectarla)
"""
import copy
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("Falta PyYAML: pip install pyyaml")

ROOT = Path(__file__).resolve().parents[2]
TOPOLOGY = ROOT / "contracts/events/topology.yaml"
SCHEMAS = ROOT / "contracts/events"
KAFKA_DIR = ROOT / "deploy/platform/kafka"
RETIRED_TOPIC = "dominio.documentos"
# eventTypes reservados en la topologia sin esquema aun en el catalogo.
RESERVED = {"tenant.credenciales_rotadas"}
SIGNAL_PREFIXES = ("seguridad.", "chat.")
CONTROL_PREFIXES = ("legalhold.", "consumo.", "auditoria.")
KINDS = {"domain", "signal", "control"}
ALLOWED_OPS = {"Write", "Read", "Describe"}
RESOURCE_TYPES = {"topic", "group", "cluster", "transactionalId", "delegationToken", "userOnly"}
NAME = r"[\w.\-]+"
LISTENER_RE = re.compile(r'@KafkaListener\s*\(\s*topics\s*=\s*"((?:[^"\\]|\\.)*)"')
RULES = {f"R{i:02d}" for i in range(1, 21)}


def load_yaml_docs(path):
    with open(path, encoding="utf-8") as f:
        return [d for d in yaml.safe_load_all(f) if d is not None]


def topics_of_listener(expr, topology_events, all_topics, errors, where):
    """Topicos que escucha un @KafkaListener(topics = expr) segun su default (SpEL de EventTopology o literal)."""
    if "allTopics()" in expr:
        return set(all_topics)
    found = set()
    spel = re.search(r"topics?For\(([^)]*)\)", expr)
    if spel:
        for et in re.findall(r"'([a-z0-9_.]+)'", spel.group(1)):
            if et not in topology_events:
                errors.append(("R20", f"{where}: escucha el eventType '{et}' sin entrada en topology.yaml"))
            else:
                found.add(topology_events[et]["topic"])
        return found
    default = re.sub(r"\$\{[^:}]+:([^}]*)\}", r"\1", expr.strip())
    return {t.strip() for t in default.split(",") if re.fullmatch(NAME, t.strip())}


def scan_services(events, all_topics, errors):
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
        domain_listener = dlt = False
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
    return services


def load_raw():
    """Lee todos los insumos (YAML ya parseado + escaneo de fuentes). Los mutantes del self-test parten de aqui."""
    topology = yaml.safe_load(TOPOLOGY.read_text(encoding="utf-8"))
    events = {et: {"topic": (e or {}).get("topic")} for et, e in (topology.get("eventTypes") or {}).items()}
    all_topics = {e["topic"] for e in events.values() if e["topic"]}
    errors = []
    services = scan_services(events, all_topics, errors)
    catalog = {re.sub(r"\.v\d+\.schema\.json$", "", f.name) for f in SCHEMAS.glob("*.schema.json")}
    return {"topology": topology, "catalog": catalog, "services": services, "load_errors": errors,
            "users": load_yaml_docs(KAFKA_DIR / "users.yaml"), "topics": load_yaml_docs(KAFKA_DIR / "topics.yaml"),
            "cluster": load_yaml_docs(KAFKA_DIR / "cluster.yaml")}


def normalize_acls(user, errors):
    """ACL de un KafkaUser -> lista de (resourceType, name, patternType, operation). Lo no interpretable es R12."""
    out = []
    acls = ((user.get("spec") or {}).get("authorization") or {}).get("acls") or []
    if not isinstance(acls, list):
        errors.append(("R12", f"{user['metadata']['name']}: acls no es una lista"))
        return out
    for i, acl in enumerate(acls):
        where = f"{user['metadata']['name']}: acl #{i}"
        res = acl.get("resource") if isinstance(acl, dict) else None
        if not isinstance(res, dict) or not isinstance(res.get("type"), str):
            errors.append(("R12", f"{where}: recurso sin type interpretable"))
            continue
        rtype, name = res["type"], res.get("name")
        pattern = res.get("patternType", "literal")
        if rtype not in RESOURCE_TYPES:
            errors.append(("R12", f"{where}: type de recurso desconocido '{rtype}'"))
            continue
        if rtype != "cluster" and (not isinstance(name, str) or not name):
            errors.append(("R12", f"{where}: recurso {rtype} sin name interpretable"))
            continue
        ops = acl.get("operations", [acl.get("operation")] if "operation" in acl else None)
        if not isinstance(ops, list) or not ops or not all(isinstance(o, str) for o in ops):
            errors.append(("R12", f"{where}: sin operacion interpretable"))
            continue
        if rtype == "cluster":
            errors.append(("R08", f"{where}: ACL de tipo cluster (nunca permitido)"))
            continue
        if name == "*":
            errors.append(("R10", f"{where}: nombre de recurso '*'"))
            continue
        if pattern not in ("literal", "prefix"):
            errors.append(("R12", f"{where}: patternType desconocido '{pattern}'"))
            continue
        if rtype == "topic" and pattern != "literal":
            errors.append(("R09", f"{where}: patternType '{pattern}' sobre el topico '{name}' (solo literal)"))
            continue
        for op in ops:
            if op not in ALLOWED_OPS:
                errors.append(("R11", f"{where}: operacion '{op}' sobre '{name}' (solo Write, Read, Describe)"))
                continue
            out.append((rtype, name, pattern, op))
    return out


def parse_users(docs, errors):
    users = {}
    for d in docs:
        if d.get("kind") != "KafkaUser":
            continue
        name = (d.get("metadata") or {}).get("name")
        if not name:
            errors.append(("R12", "KafkaUser sin metadata.name"))
            continue
        spec = d.get("spec") or {}
        if (spec.get("authentication") or {}).get("type") != "tls":
            errors.append(("R14", f"{name}: authentication.type debe ser tls"))
        if (spec.get("authorization") or {}).get("type") != "simple":
            errors.append(("R14", f"{name}: authorization.type debe ser simple"))
        users[name] = normalize_acls(d, errors)
    return users


def check_cluster(docs, errors):
    kafkas = [d for d in docs if d.get("kind") == "Kafka"]
    if len(kafkas) != 1:
        errors.append(("R15", "cluster.yaml debe definir exactamente un recurso Kafka"))
        return
    spec = (kafkas[0].get("spec") or {}).get("kafka") or {}
    listeners = spec.get("listeners") or []
    if not listeners:
        errors.append(("R15", "cluster.yaml sin listeners"))
    for lst in listeners:
        name = lst.get("name", "?")
        if lst.get("tls") is not True:
            errors.append(("R15", f"listener '{name}' sin tls (plain no permitido)"))
        if (lst.get("authentication") or {}).get("type") != "tls":
            errors.append(("R15", f"listener '{name}' sin authentication.type tls"))
    authz = spec.get("authorization") or {}
    if authz.get("type") != "simple":
        errors.append(("R16", "cluster sin authorization.type simple"))
    if authz.get("superUsers"):
        errors.append(("R16", f"cluster con superUsers {authz['superUsers']} (prohibido)"))
    config = spec.get("config") or {}
    for key in ("auto.create.topics.enable", "allow.everyone.if.no.acl.found"):
        if str(config.get(key)).lower() != "false":
            errors.append(("R17", f"cluster sin {key}: \"false\""))


def validate(raw):
    errors = list(raw["load_errors"])
    topo = raw["topology"]
    topo_topics = topo.get("topics") or {}
    events = {}
    for et, e in (topo.get("eventTypes") or {}).items():
        e = e or {}
        events[et] = {"topic": e.get("topic"), "producers": list(e.get("producers") or []), "key": e.get("key")}
    declared = {(d.get("metadata") or {}).get("name") for d in raw["topics"] if d.get("kind") == "KafkaTopic"}
    services = raw["services"]
    users = parse_users(raw["users"], errors)
    check_cluster(raw["cluster"], errors)

    # R01 catalogo completo
    for et in sorted(raw["catalog"] - set(events)):
        errors.append(("R01", f"eventType '{et}' del catalogo sin entrada en topology.yaml"))
    for et in sorted(set(events) - raw["catalog"] - RESERVED):
        errors.append(("R01", f"topology.yaml declara '{et}' sin esquema en contracts/events"))
    # R02 definicion de topicos
    for t, v in sorted(topo_topics.items()):
        if (v or {}).get("kind") not in KINDS:
            errors.append(("R02", f"topico '{t}': kind invalido (domain|signal|control)"))
        if t not in declared:
            errors.append(("R02", f"topico '{t}' no declarado en topics.yaml"))
    # producciones por topico
    producers_of_topic = {}
    for et, e in sorted(events.items()):
        if not e["topic"] or not e["producers"] or not e["key"]:
            errors.append(("R02", f"{et}: entrada incompleta (topic, producers y key son obligatorios)"))
            continue
        producers_of_topic.setdefault(e["topic"], set()).update(e["producers"])
        if e["topic"] not in topo_topics:
            errors.append(("R02", f"{et}: topico '{e['topic']}' sin definicion en la seccion topics de topology.yaml"))
            continue
        if topo_topics[e["topic"]].get("key") != e["key"]:
            errors.append(("R02", f"{et}: key '{e['key']}' distinta de la del topico '{e['topic']}'"))
        kind = topo_topics[e["topic"]].get("kind")
        if len(e["producers"]) > 1 and kind not in ("signal", "control"):
            errors.append(("R03", f"{et}: varios productores solo se admiten en topicos signal o control "
                                  f"(topico '{e['topic']}')"))
        # R04 separacion
        if kind == "signal" and not et.startswith(SIGNAL_PREFIXES):
            errors.append(("R04", f"{et}: un topico signal multi-productor ('{e['topic']}') solo admite senales "
                                  f"{SIGNAL_PREFIXES}, no estado ni control"))
        if kind == "control" and not et.startswith(CONTROL_PREFIXES):
            errors.append(("R04", f"{et}: un topico control ('{e['topic']}') solo admite eventos de control "
                                  f"{CONTROL_PREFIXES}"))
        if kind == "domain" and et.startswith(SIGNAL_PREFIXES + CONTROL_PREFIXES):
            errors.append(("R04", f"{et}: senal o control en el topico de dominio '{e['topic']}'"))
        for p in e["producers"]:
            if p not in users:
                errors.append(("R19", f"{et}: productor '{p}' sin KafkaUser en users.yaml"))
    for t, ps in sorted(producers_of_topic.items()):
        if len(ps) > 1 and (topo_topics.get(t) or {}).get("kind") not in ("signal", "control"):
            errors.append(("R03", f"topico '{t}' con varios productores {sorted(ps)} y kind distinto de signal/control"))
    # R05 a R07 por servicio
    produced = {}
    for et, e in events.items():
        for p in e["producers"]:
            produced.setdefault(p, set()).add(e["topic"])
    for svc, acls in sorted(users.items()):
        info = services.get(svc) or {"listened": set(), "domain_listener": False, "dlt": False, "lib_listeners": set()}
        topic_ops = {}
        for rtype, name, _pattern, op in acls:
            if rtype == "topic":
                topic_ops.setdefault(name, set()).add(op)
        writes = {n for n, ops in topic_ops.items() if "Write" in ops}
        reads = {n for n, ops in topic_ops.items() if "Read" in ops}
        expected_w = set(produced.get(svc, set()))
        if info["dlt"]:
            expected_w |= {t + "-dlt" for t in info["listened"]
                           if (topo_topics.get(t) or {}).get("kind") == "domain" and t != "idp.tenant.events"}
        expected_r = set(info["listened"])
        for t in sorted(expected_w - writes):
            errors.append(("R05", f"{svc}: falta Write sobre '{t}' (topico que produce)"))
        for t in sorted(writes - expected_w):
            errors.append(("R05", f"{svc}: Write sobre el topico ajeno '{t}' (solo debe escribir {sorted(expected_w)})"))
        for t in sorted(expected_r - reads):
            errors.append(("R06", f"{svc}: sin Read sobre el topico '{t}' que escucha"))
        for t in sorted(reads - expected_r):
            errors.append(("R06", f"{svc}: Read sobre el topico '{t}' que no escucha"))
        for t in sorted(expected_w | expected_r | set(topic_ops)):
            if t not in declared:
                errors.append(("R02", f"{svc}: topico '{t}' no declarado en topics.yaml"))
        expected_d = expected_w | expected_r
        for t in sorted(expected_d):
            if "Describe" not in topic_ops.get(t, set()):
                errors.append(("R07", f"{svc}: falta Describe sobre '{t}'"))
        for t in sorted(set(topic_ops) - expected_d):
            if "Describe" in topic_ops[t]:
                errors.append(("R07", f"{svc}: Describe sobre el topico ajeno '{t}'"))
        # R13 grupos
        groups = {(n, p) for rtype, n, p, op in acls if rtype == "group" and op == "Read"}
        expected_g = set()
        if info["domain_listener"]:
            expected_g.add((svc, "literal"))
        for cls, grp in (("AccesoRevocadoKafkaListener", "acceso-revocado"),
                         ("TenantPoolEvictionKafkaListener", "pool-evict")):
            if cls in info["lib_listeners"]:
                expected_g.add((f"{svc}-{grp}-", "prefix"))
        for g in sorted(expected_g - groups):
            errors.append(("R13", f"{svc}: falta ACL del grupo de consumo '{g[0]}' ({g[1]})"))
        for g in sorted({(n, p) for rtype, n, p, _o in acls if rtype == "group"} - expected_g):
            errors.append(("R13", f"{svc}: ACL de grupo no esperada '{g[0]}' ({g[1]})"))
    for svc in sorted(produced):
        if svc not in users:
            errors.append(("R19", f"{svc}: produce eventos pero no tiene KafkaUser"))
    # R19 servicios sin usuario y R18 topico retirado
    for svc, info in sorted(services.items()):
        if svc not in users and info["listened"]:
            errors.append(("R19", f"{svc}: escucha {sorted(info['listened'])} pero no tiene KafkaUser"))
        for f in info["retired"]:
            errors.append(("R18", f"{svc}: {f} referencia el topico retirado '{RETIRED_TOPIC}'"))
    return errors


# ---------------------------------------------------------------------------------------------------------
# Self-test: una mutacion por regla (como minimo) sobre los insumos reales; validate() debe detectarla.
# ---------------------------------------------------------------------------------------------------------

def _user(raw, name):
    for d in raw["users"]:
        if d.get("kind") == "KafkaUser" and d["metadata"]["name"] == name:
            return d
    raise KeyError(name)


def _acls(raw, name):
    return _user(raw, name)["spec"]["authorization"]["acls"]


def _acl(topic, op, pattern="literal", rtype="topic"):
    return {"resource": {"type": rtype, "name": topic, "patternType": pattern}, "operation": op}


def _drop(raw, user, rtype, name, op):
    acls = _acls(raw, user)
    acls[:] = [a for a in acls if not (a["resource"]["type"] == rtype and a["resource"].get("name") == name
                                       and a.get("operation") == op)]


def _kafka(raw):
    return next(d for d in raw["cluster"] if d.get("kind") == "Kafka")["spec"]["kafka"]


def _topic_doc(raw, name):
    return next(d for d in raw["topics"] if d["metadata"]["name"] == name)


def mutations():
    """(regla, nombre, funcion de mutacion) — incluye los casos del hallazgo de auditoria."""
    ev = lambda r: r["topology"]["eventTypes"]
    return [
        ("R01", "eventType sin entrada", lambda r: ev(r).pop("revision.completada")),
        ("R01", "entrada sin esquema", lambda r: ev(r).update({"zzz.fantasma": {
            "topic": "review.events", "producers": ["review-service"], "key": "documentId"}})),
        ("R02", "topico sin declarar en topics.yaml", lambda r: r["topics"].remove(_topic_doc(r, "quality.events"))),
        ("R02", "key incoherente", lambda r: ev(r)["documento.recibido"].update(key="tenantId")),
        ("R02", "kind invalido", lambda r: r["topology"]["topics"]["quality.events"].update(kind="otro")),
        ("R03", "dos productores en topico de dominio",
         lambda r: ev(r)["revision.escalada"]["producers"].append("quality-service")),
        ("R04", "control en el topico de senales",
         lambda r: ev(r)["legalhold.aplicado"].update(topic="audit.signals")),
        ("R04", "estado en el topico multi-productor de senales",
         lambda r: ev(r)["revision.completada"].update(topic="audit.signals")),
        ("R04", "senal en el topico de control",
         lambda r: ev(r)["chat.respuesta_bloqueada"].update(topic="audit.control")),
        ("R04", "senal en topico de dominio", lambda r: ev(r)["chat.respuesta_desde_cache"].update(
            topic="review.events")),
        ("R05", "Write ajeno", lambda r: _acls(r, "quality-service").append(_acl("review.events", "Write"))),
        ("R05", "Write faltante", lambda r: _drop(r, "review-service", "topic", "review.events", "Write")),
        ("R05", "suplantacion en document.events",
         lambda r: _acls(r, "extraction-service").append(_acl("document.events", "Write"))),
        ("R05", "Write extra sobre un -dlt ajeno",
         lambda r: _acls(r, "document-service").append(_acl("quality.events-dlt", "Write"))),
        ("R05", "Write de control para quien no lo produce",
         lambda r: _acls(r, "document-service").append(_acl("audit.control", "Write"))),
        ("R06", "Read ajeno extra", lambda r: _acls(r, "quality-service").append(_acl("notification.events", "Read"))),
        ("R06", "consumidor sin Read", lambda r: _drop(r, "document-service", "topic", "review.events", "Read")),
        ("R07", "falta Describe", lambda r: _drop(r, "review-service", "topic", "review.events", "Describe")),
        ("R07", "Describe ajeno", lambda r: _acls(r, "quality-service").append(_acl("notification.events", "Describe"))),
        ("R08", "ACL cluster", lambda r: _acls(r, "document-service").append(_acl("kafka-cluster", "Describe", rtype="cluster"))),
        ("R08", "ACL cluster Write", lambda r: _acls(r, "audit-service").append(_acl("kafka-cluster", "Write", rtype="cluster"))),
        ("R09", "prefix sobre topico", lambda r: _acls(r, "document-service").append(_acl("documentos.", "Read", "prefix"))),
        ("R09", "prefix sobre topico propio", lambda r: _acls(r, "review-service").append(_acl("revision.", "Write", "prefix"))),
        ("R12", "patternType desconocido sobre topico",
         lambda r: _acls(r, "review-service").append(_acl("review.events", "Write", "contains"))),
        ("R10", "wildcard sobre topico", lambda r: _acls(r, "document-service").append(_acl("*", "Read"))),
        ("R10", "wildcard sobre grupo", lambda r: _acls(r, "document-service").append(_acl("*", "Read", rtype="group"))),
        ("R11", "operation All sobre un topico propio",
         lambda r: _acls(r, "review-service").append(_acl("review.events", "All"))),
        ("R11", "operation Create", lambda r: _acls(r, "review-service").append(_acl("review.events", "Create"))),
        ("R11", "operation Alter", lambda r: _acls(r, "review-service").append(_acl("review.events", "Alter"))),
        ("R11", "operation Delete", lambda r: _acls(r, "review-service").append(_acl("review.events", "Delete"))),
        ("R11", "All en lista operations", lambda r: _acls(r, "review-service").append({
            "resource": {"type": "topic", "name": "review.events", "patternType": "literal"},
            "operations": ["Write", "All"]})),
        ("R12", "recurso sin type", lambda r: _acls(r, "review-service").append(
            {"resource": {"name": "review.events"}, "operation": "Write"})),
        ("R12", "type de recurso desconocido", lambda r: _acls(r, "review-service").append(
            _acl("tx-1", "Write", rtype="transactionalIdX"))),
        ("R12", "ACL sin recurso", lambda r: _acls(r, "review-service").append({"operation": "Write"})),
        ("R12", "ACL sin operacion", lambda r: _acls(r, "review-service").append(
            {"resource": {"type": "topic", "name": "review.events", "patternType": "literal"}})),
        ("R13", "falta ACL de grupo", lambda r: _drop(r, "document-service", "group", "document-service", "Read")),
        ("R13", "falta ACL de grupo prefijo",
         lambda r: _drop(r, "document-service", "group", "document-service-acceso-revocado-", "Read")),
        ("R13", "ACL de grupo ajeno", lambda r: _acls(r, "quality-service").append(
            _acl("document-service", "Read", rtype="group"))),
        ("R14", "KafkaUser sin tls", lambda r: _user(r, "review-service")["spec"]["authentication"].update(
            type="scram-sha-512")),
        ("R14", "KafkaUser sin authentication", lambda r: _user(r, "review-service")["spec"].pop("authentication")),
        ("R14", "KafkaUser sin authorization simple",
         lambda r: _user(r, "review-service")["spec"]["authorization"].update(type="opa")),
        ("R15", "listener plain", lambda r: _kafka(r)["listeners"][0].update(tls=False)),
        ("R15", "listener sin authentication tls", lambda r: _kafka(r)["listeners"][0].pop("authentication")),
        ("R15", "listener plain adicional", lambda r: _kafka(r)["listeners"].append(
            {"name": "plain", "port": 9092, "type": "internal", "tls": False})),
        ("R16", "cluster sin authorization", lambda r: _kafka(r).pop("authorization")),
        ("R16", "cluster con superUsers", lambda r: _kafka(r)["authorization"].update(superUsers=["CN=admin"])),
        ("R17", "auto.create.topics.enable ausente", lambda r: _kafka(r)["config"].pop("auto.create.topics.enable")),
        ("R17", "auto.create.topics.enable true",
         lambda r: _kafka(r)["config"].update({"auto.create.topics.enable": "true"})),
        ("R17", "allow.everyone.if.no.acl.found ausente",
         lambda r: _kafka(r)["config"].pop("allow.everyone.if.no.acl.found")),
        ("R18", "topico retirado", lambda r: r["services"]["review-service"]["retired"].append("X.java")),
        ("R19", "servicio sin usuario escucha", lambda r: r["services"]["renderer"].update(listened={"document.events"})),
        ("R19", "productor sin KafkaUser", lambda r: ev(r)["revision.escalada"].update(producers=["fantasma-service"])),
        ("R20", "eventType escuchado desconocido",
         lambda r: r["load_errors"].append(("R20", "x: escucha el eventType 'zzz'"))),
    ]


def self_test():
    base = load_raw()
    failures = []
    baseline = validate(base)
    if baseline:
        failures.append(f"el modelo base ya tiene errores: {baseline[:3]}")
    covered = set()
    for rule, name, fn in mutations():
        covered.add(rule)
        raw = copy.deepcopy(base)
        fn(raw)
        if not any(r == rule for r, _ in validate(raw)):
            failures.append(f"{rule} '{name}': la mutacion no fue detectada por la regla")
    for rule in sorted(RULES - covered):
        failures.append(f"{rule}: regla sin mutacion asociada en el self-test")
    for f in failures:
        print("SELF-TEST FALLO", f)
    print("SELF-TEST OK" if not failures else f"SELF-TEST: {len(failures)} fallo(s)")
    return 1 if failures else 0


def main():
    if "--self-test" in sys.argv[1:]:
        return self_test()
    errors = validate(load_raw())
    for rule, msg in errors:
        print(f"ERROR [{rule}] {msg}")
    print("OK" if not errors else f"{len(errors)} error(es)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
