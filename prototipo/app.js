const IDP_DATA = {
  documents: [
    {
      id: "embargo_2026-4471",
      file: "embargo_2026-4471.pdf",
      tenant: "Banco Aurora",
      batch: "8842",
      typology: "Embargo judicial",
      confidence: 0.98,
      status: "completo",
      statusLabel: "Completo",
      time: "3.1s",
      size: "1.2MB",
      origin: "API",
      createdAt: "14:41",
      assignedTo: "Carlos Ariza",
      validation: "validado",
      fields: {
        radicado: "2026-CO-04471",
        monto_embargado: "$ 42.500.000 COP",
        juzgado: "Juzgado 12 Civil del Circuito de Bogota",
        fecha_radicado: null
      }
    },
    {
      id: "desembargo_9012",
      file: "desembargo_9012.pdf",
      tenant: "NeoFin",
      batch: "8843",
      typology: "Desembargo",
      confidence: 0.91,
      status: "procesando",
      statusLabel: "Procesando",
      time: "-",
      size: "2.4MB",
      origin: "API",
      createdAt: "14:29",
      assignedTo: "Sin asignar",
      validation: "pendiente"
    },
    {
      id: "embargo_2026-4472",
      file: "embargo_2026-4472.pdf",
      tenant: "Banco Aurora",
      batch: "8842",
      typology: "Embargo judicial",
      confidence: null,
      status: "error",
      statusLabel: "Error",
      time: "timeout LLM",
      size: "1.9MB",
      origin: "API",
      createdAt: "14:18",
      assignedTo: "Mesa soporte",
      validation: "fallido"
    },
    {
      id: "embargo_2026-4470",
      file: "embargo_2026-4470.pdf",
      tenant: "Fiduciaria Meridiano",
      batch: "8841",
      typology: "Embargo judicial",
      confidence: 0.99,
      status: "completo",
      statusLabel: "Completo",
      time: "2.7s",
      size: "1.1MB",
      origin: "Lote batch",
      createdAt: "13:55",
      assignedTo: "Ana Lucia Pena",
      validation: "validado"
    },
    {
      id: "contrato_fiducia_331",
      file: "contrato_fiducia_331.pdf",
      tenant: "Fiduciaria Meridiano",
      batch: "8840",
      typology: "Contrato fiducia",
      confidence: null,
      status: "pendiente",
      statusLabel: "En cola",
      time: "-",
      size: "3.8MB",
      origin: "API",
      createdAt: "13:42",
      assignedTo: "Sin asignar",
      validation: "pendiente"
    }
  ],
  roles: {
    Administrador: {
      canEditFields: true,
      canConfigure: true,
      canManageUsers: true,
      canRetry: true
    },
    Operador: {
      canEditFields: true,
      canConfigure: false,
      canManageUsers: false,
      canRetry: true
    },
    Auditor: {
      canEditFields: false,
      canConfigure: false,
      canManageUsers: false,
      canRetry: false
    }
  }
};

const state = {
  role: localStorage.getItem("idp_role") || "Administrador",
  tenant: localStorage.getItem("idp_tenant") || "Banco Aurora"
};

document.addEventListener("DOMContentLoaded", () => {
  document.addEventListener("click", blockDisabledAction, true);
  enhanceShell();
  enhanceTabs();
  enhanceConsulta();
  enhanceIngesta();
  enhanceDocumento();
  enhanceTipologias();
  enhanceWebhooks();
  enhanceUsers();
  enhanceKpis();
  applyPermissions();
});

function enhanceShell() {
  document.body.dataset.role = state.role.toLowerCase();
  document.body.dataset.tenant = state.tenant;

  const topbar = document.querySelector(".topbar-actions");
  if (!topbar || document.querySelector(".session-controls")) return;

  const controls = document.createElement("div");
  controls.className = "session-controls";
  controls.innerHTML = `
    <label class="sr-only" for="tenant-switcher">Tenant activo</label>
    <select id="tenant-switcher" aria-label="Tenant activo">
      ${["Banco Aurora", "NeoFin", "Fiduciaria Meridiano"].map((tenant) => `<option ${tenant === state.tenant ? "selected" : ""}>${tenant}</option>`).join("")}
    </select>
    <label class="sr-only" for="role-switcher">Rol activo</label>
    <select id="role-switcher" aria-label="Rol activo">
      ${Object.keys(IDP_DATA.roles).map((role) => `<option ${role === state.role ? "selected" : ""}>${role}</option>`).join("")}
    </select>
    <button class="btn ghost" type="button" data-session-expire>Simular sesion expirada</button>
  `;
  topbar.prepend(controls);

  controls.querySelector("#tenant-switcher").addEventListener("change", (event) => {
    state.tenant = event.target.value;
    localStorage.setItem("idp_tenant", state.tenant);
    document.body.dataset.tenant = state.tenant;
    showToast(`Tenant activo: ${state.tenant}`, "ok");
    enhanceConsulta(true);
  });

  controls.querySelector("#role-switcher").addEventListener("change", (event) => {
    state.role = event.target.value;
    localStorage.setItem("idp_role", state.role);
    applyPermissions();
    showToast(`Rol activo: ${state.role}`, "ok");
  });

  controls.querySelector("[data-session-expire]").addEventListener("click", () => {
    showModal("Sesion expirada", "Tu sesion de prototipo expiro. En producto se redirige a SSO/OIDC y se conserva el destino original.", "Iniciar sesion");
  });
}

function enhanceTabs() {
  document.querySelectorAll(".tabs").forEach((tabs) => {
    tabs.querySelectorAll(".tab").forEach((tab) => {
      tab.addEventListener("click", (event) => {
        tabs.querySelectorAll(".tab").forEach((item) => item.classList.remove("active"));
        event.currentTarget.classList.add("active");
      });
    });
  });
}

function enhanceConsulta(force = false) {
  const content = document.querySelector(".topbar-title")?.textContent.includes("Consulta") ? document.querySelector(".content") : null;
  if (!content) return;

  if (!document.querySelector(".operational-filters")) {
    const filters = document.createElement("div");
    filters.className = "operational-filters";
    filters.innerHTML = `
      <div class="field"><label>Estado</label><select data-filter="status"><option value="">Todos</option><option value="completo">Completo</option><option value="procesando">Procesando</option><option value="pendiente">En cola</option><option value="error">Error</option></select></div>
      <div class="field"><label>Tipologia</label><select data-filter="typology"><option value="">Todas</option><option>Embargo judicial</option><option>Desembargo</option><option>Contrato fiducia</option></select></div>
      <div class="field"><label>Confianza minima</label><input data-filter="confidence" type="number" min="0" max="1" step="0.01" placeholder="0.85"></div>
      <button class="btn" type="button" data-export-csv>Exportar CSV</button>
    `;
    const search = content.querySelector(".field");
    search?.after(filters);
    content.querySelector("input[placeholder='embargo_2026-4471']")?.setAttribute("data-filter", "query");
  }

  const tableBody = content.querySelector("tbody");
  const inputs = content.querySelectorAll("[data-filter]");
  const render = () => {
    if (!tableBody) return;
    const filters = Object.fromEntries(Array.from(inputs).map((input) => [input.dataset.filter, input.value.trim()]));
    const docs = IDP_DATA.documents.filter((doc) => {
      const query = filters.query?.toLowerCase() || "";
      const matchesQuery = !query || `${doc.id} ${doc.file} ${doc.tenant} ${doc.typology}`.toLowerCase().includes(query);
      const matchesStatus = !filters.status || doc.status === filters.status;
      const matchesTypology = !filters.typology || doc.typology === filters.typology;
      const minConfidence = filters.confidence === "" ? null : Number(filters.confidence);
      const matchesConfidence = minConfidence === null || (doc.confidence !== null && doc.confidence >= minConfidence);
      return matchesQuery && matchesStatus && matchesTypology && matchesConfidence;
    });

    tableBody.innerHTML = docs.length
      ? docs.map(documentRow).join("")
      : `<tr><td colspan="5"><div class="empty-state">Sin resultados para los filtros actuales.</div></td></tr>`;
  };

  if (!force) {
    inputs.forEach((input) => input.addEventListener("input", render));
    content.querySelector("[data-export-csv]")?.addEventListener("click", () => showToast("CSV generado en prototipo: documentos-filtrados.csv", "ok"));
  }
  render();
}

function documentRow(doc) {
  const confidence = doc.confidence === null ? "-" : `<b>${doc.confidence.toFixed(2)}</b>`;
  return `
    <tr class="clickable" onclick="location.href='documento.html?id=${doc.id}'">
      <td><div class="doc-name"><a href="documento.html?id=${doc.id}">${doc.file}</a></div><div class="doc-meta">${doc.tenant} &middot; lote #${doc.batch}</div></td>
      <td>${doc.typology}</td>
      <td class="confidence">${confidence}</td>
      <td><span class="badge ${doc.status}"><svg viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="12" r="10"/></svg> ${doc.statusLabel}</span></td>
      <td>${doc.time}</td>
    </tr>`;
}

function enhanceIngesta() {
  const drop = document.querySelector(".upload-drop");
  if (!drop || drop.dataset.ready) return;
  drop.dataset.ready = "true";
  drop.setAttribute("tabindex", "0");
  drop.setAttribute("role", "button");
  drop.innerHTML += `<input class="file-input" type="file" accept=".pdf,.jpg,.jpeg,.png,.docx" aria-label="Seleccionar documento">`;
  const fileInput = drop.querySelector("input");
  const submit = document.querySelector(".panel .btn.primary");

  drop.addEventListener("click", () => fileInput.click());
  drop.addEventListener("dragover", (event) => {
    event.preventDefault();
    drop.classList.add("dragover");
  });
  drop.addEventListener("dragleave", () => drop.classList.remove("dragover"));
  drop.addEventListener("drop", (event) => {
    event.preventDefault();
    drop.classList.remove("dragover");
    handleFile(event.dataTransfer.files[0], drop);
  });
  fileInput.addEventListener("change", () => handleFile(fileInput.files[0], drop));

  submit?.addEventListener("click", (event) => {
    const selected = drop.dataset.fileName;
    if (!selected) {
      event.preventDefault();
      event.stopImmediatePropagation();
      showToast("Selecciona un documento antes de subir.", "warn");
      return;
    }
    simulateUpload(drop);
  }, true);
}

function handleFile(file, drop) {
  if (!file) return;
  const validTypes = [".pdf", ".jpg", ".jpeg", ".png", ".docx"];
  const extension = `.${file.name.split(".").pop().toLowerCase()}`;
  if (!validTypes.includes(extension)) {
    showToast("Formato no soportado por ADR-006.", "error");
    return;
  }
  if (file.size > 20 * 1024 * 1024) {
    showToast("El archivo supera 20MB. Rechazado sin consumir LLM.", "error");
    return;
  }
  drop.dataset.fileName = file.name;
  drop.querySelector("svg").outerHTML = `<div class="upload-file">${file.name}<span>${formatBytes(file.size)} · listo para validar</span></div>`;
}

function simulateUpload(drop) {
  const existing = document.querySelector(".upload-progress");
  existing?.remove();
  const progress = document.createElement("div");
  progress.className = "upload-progress";
  progress.innerHTML = `<div><span></span></div><strong>0%</strong>`;
  drop.after(progress);
  let value = 0;
  const timer = setInterval(() => {
    value += 20;
    progress.querySelector("span").style.width = `${value}%`;
    progress.querySelector("strong").textContent = `${value}%`;
    if (value >= 100) {
      clearInterval(timer);
      showToast("Ingesta validada y encolada para clasificacion.", "ok");
    }
  }, 120);
}

function enhanceDocumento() {
  const title = document.querySelector(".topbar-title");
  if (!title?.textContent.includes(".pdf") || document.querySelector(".document-review-grid")) return;
  const content = document.querySelector(".content");
  const firstPanel = content.querySelector(".panel");
  const doc = IDP_DATA.documents[0];
  const review = document.createElement("div");
  review.className = "document-review-grid";
  review.innerHTML = `
    <section class="panel document-viewer" aria-label="Visor de documento fuente">
      <div class="panel-title">Documento fuente</div>
      <div class="viewer-page">
        <div class="viewer-toolbar">
          <button class="btn" type="button" data-zoom="-">-</button>
          <span>Pagina 1 de 3 &middot; OCR listo</span>
          <button class="btn" type="button" data-zoom="+">+</button>
        </div>
        <div class="mock-document" data-zoom-level="1">
          <h4>Juzgado 12 Civil del Circuito de Bogota</h4>
          <p>Proceso ejecutivo con radicado <mark>2026-CO-04471</mark>.</p>
          <p>Ordenese el embargo por valor de <mark>$ 42.500.000 COP</mark>.</p>
          <p>La fecha de radicado no consta en el documento fuente.</p>
        </div>
      </div>
    </section>
    <section class="panel review-queue">
      <div class="panel-title">Revision humana y auditoria</div>
      <div class="kv"><span>Asignado a</span><span>${doc.assignedTo}</span></div>
      <div class="kv"><span>Estado de validacion</span><span><span class="badge completo"><svg viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="12" r="10"/></svg> Validado</span></span></div>
      <div class="kv"><span>Version de tipologia</span><span>embargo_judicial v3</span></div>
      <div class="kv"><span>Modelo usado</span><span>gemini-2.5-flash-lite</span></div>
      <div class="audit-log">
        <div>14:41 &middot; Sistema &middot; Documento recibido</div>
        <div>14:42 &middot; LLM &middot; Campos extraidos</div>
        <div>14:44 &middot; Carlos Ariza &middot; Campo fecha_radicado confirmado como null legitimo</div>
      </div>
      <div class="action-row">
        <button class="btn primary" type="button" data-action="approve">Aprobar resultado</button>
        <button class="btn" type="button" data-action="retry">Reprocesar</button>
      </div>
    </section>
  `;
  firstPanel.before(review);
  review.querySelector("[data-action='approve']").addEventListener("click", () => showToast("Resultado aprobado y auditado.", "ok"));
  review.querySelector("[data-action='retry']").addEventListener("click", () => showModal("Reprocesar documento", "En producto se elige version de tipologia/modelo y se conserva el historial de intentos.", "Crear reproceso"));
}

function enhanceTipologias() {
  const textarea = document.querySelector("textarea");
  if (!textarea || textarea.dataset.ready) return;
  textarea.dataset.ready = "true";
  const status = document.createElement("div");
  status.className = "validation-status alert ok";
  status.innerHTML = "<div>YAML valido para prototipo. Cambia el contenido para ejecutar validacion fail-fast.</div>";
  textarea.after(status);
  textarea.addEventListener("input", () => {
    const text = textarea.value;
    const valid = text.includes("tipologia:") && text.includes("campos:") && text.includes("tipo:");
    status.className = `validation-status alert ${valid ? "ok" : "err"}`;
    status.innerHTML = `<div>${valid ? "Config valida. Puede publicarse como nueva version." : "Config invalida: faltan tipologia, campos o tipo."}</div>`;
  });
}

function enhanceWebhooks() {
  if (!document.querySelector(".topbar-title")?.textContent.includes("Webhooks") || document.querySelector(".webhook-tools")) return;
  const panel = document.querySelector(".content .panel");
  const tools = document.createElement("div");
  tools.className = "webhook-tools";
  tools.innerHTML = `
    <button class="btn" type="button" data-test-webhook>Probar endpoint</button>
    <button class="btn" type="button" data-rotate-secret>Rotar secreto</button>
    <button class="btn" type="button" data-retry-delivery>Reintentar fallidos</button>
  `;
  panel.append(tools);
  tools.querySelector("[data-test-webhook]").addEventListener("click", () => showToast("Prueba enviada: HTTP 200 en 184ms.", "ok"));
  tools.querySelector("[data-rotate-secret]").addEventListener("click", () => showModal("Rotar secreto", "El secreto nuevo queda visible una sola vez y las firmas HMAC se validan por version.", "Rotar"));
  tools.querySelector("[data-retry-delivery]").addEventListener("click", () => showToast("7 entregas fallidas reencoladas.", "ok"));
}

function enhanceUsers() {
  if (!document.querySelector(".topbar-title")?.textContent.includes("Usuarios") || document.querySelector(".permission-hint")) return;
  const panel = document.querySelector(".content .panel");
  const hint = document.createElement("div");
  hint.className = "permission-hint alert warn";
  hint.innerHTML = "<div>Los controles visibles se ajustan con el selector de rol superior para simular RBAC en el prototipo.</div>";
  panel.after(hint);
}

function enhanceKpis() {
  if (!document.querySelector(".topbar-title")?.textContent.includes("KPIs") || document.querySelector(".kpi-filters")) return;
  const tabs = document.querySelector(".tabs");
  const filters = document.createElement("div");
  filters.className = "kpi-filters operational-filters";
  filters.innerHTML = `
    <div class="field"><label>Ventana</label><select><option>Ultimas 24 horas</option><option>7 dias</option><option>30 dias</option></select></div>
    <div class="field"><label>Tenant</label><select><option>Todos</option><option>Banco Aurora</option><option>NeoFin</option><option>Fiduciaria Meridiano</option></select></div>
    <div class="field"><label>Tipologia</label><select><option>Todas</option><option>Embargo judicial</option><option>Desembargo</option></select></div>
  `;
  tabs.after(filters);
  filters.addEventListener("change", () => showToast("KPIs recalculados para la ventana seleccionada.", "ok"));
}

function applyPermissions() {
  document.body.dataset.role = state.role.toLowerCase();
  const permissions = IDP_DATA.roles[state.role];
  document.querySelectorAll("[data-requires]").forEach((element) => {
    const required = element.dataset.requires;
    element.toggleAttribute("disabled", !permissions[required]);
    element.classList.toggle("is-disabled", !permissions[required]);
  });

  document.querySelectorAll(".topbar-actions .primary, .modal-foot .primary").forEach((button) => {
    const text = button.textContent.toLowerCase();
    const needsConfig = text.includes("guardar configuracion") || text.includes("nuevo webhook") || text.includes("invitar usuario");
    const needsEdit = text.includes("editar campos");
    if ((needsConfig && !permissions.canConfigure) || (needsEdit && !permissions.canEditFields)) {
      button.setAttribute("aria-disabled", "true");
      button.classList.add("is-disabled");
    } else {
      button.removeAttribute("aria-disabled");
      button.classList.remove("is-disabled");
    }
  });
}

function blockDisabledAction(event) {
  const blocked = event.target.closest("[aria-disabled='true'], .is-disabled");
  if (!blocked) return;
  event.preventDefault();
  event.stopImmediatePropagation();
  showToast(`El rol ${state.role} no tiene permiso para esta accion.`, "warn");
}

function showToast(message, variant = "ok") {
  const toast = document.createElement("div");
  toast.className = `toast ${variant}`;
  toast.textContent = message;
  document.body.append(toast);
  requestAnimationFrame(() => toast.classList.add("show"));
  setTimeout(() => {
    toast.classList.remove("show");
    setTimeout(() => toast.remove(), 220);
  }, 2600);
}

function showModal(title, body, actionLabel) {
  let dialog = document.querySelector("#runtime-modal");
  if (!dialog) {
    dialog = document.createElement("dialog");
    dialog.id = "runtime-modal";
    document.body.append(dialog);
  }
  dialog.innerHTML = `
    <div class="modal-head"><h3>${title}</h3><button class="icon-btn" type="button" data-close-modal>x</button></div>
    <div class="modal-body"><p>${body}</p></div>
    <div class="modal-foot"><button class="btn primary" type="button" data-close-modal>${actionLabel}</button></div>
  `;
  dialog.querySelectorAll("[data-close-modal]").forEach((button) => button.addEventListener("click", () => dialog.close()));
  dialog.showModal();
}

function formatBytes(bytes) {
  if (!bytes) return "0B";
  const units = ["B", "KB", "MB", "GB"];
  const index = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, index)).toFixed(1)}${units[index]}`;
}
