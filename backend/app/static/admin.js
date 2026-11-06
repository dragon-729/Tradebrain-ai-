const $ = (id) => document.getElementById(id);
let token = sessionStorage.getItem("tb_admin") || "";
$("token").value = token ? "••••••••" : "";

function cell(tr, text, cls) { const td = document.createElement("td"); td.textContent = text ?? "—"; if (cls) td.className = cls; tr.appendChild(td); return td; }
function card(label, value) { const d = document.createElement("div"); d.className = "card"; const b = document.createElement("b"); b.textContent = value; d.append(b, document.createTextNode(label)); return d; }
async function api(path, method = "GET") {
  const r = await fetch(path, { method, headers: { "X-Admin-Token": token } });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.detail || ("HTTP " + r.status));
  return j;
}
function render(s) {
  s = s.status || s;
  $("summary").replaceChildren(card("Configured keys", s.total), card("Active", s.active), card("Invalid", s.invalid),
    card("Temporarily unavailable", s.temporarily_unavailable), card("Connection", s.connection));
  const kb = $("keys"); kb.replaceChildren();
  for (const k of s.keys) {
    const tr = document.createElement("tr");
    cell(tr, k.id); cell(tr, k.key);
    cell(tr, k.status === "cooldown" ? `cooldown (${k.cooldown_remaining_sec}s, ${k.cooldown_reason})` : k.status, k.status);
    cell(tr, k.last_success_at); cell(tr, k.requests); cell(tr, k.failures); cell(tr, k.rate_limited); cell(tr, k.tokens);
    cell(tr, k.last_error ? `${k.last_error_kind}: ${k.last_error}` : "—");
    const td = document.createElement("td"); const b = document.createElement("button"); b.textContent = "Test";
    b.onclick = async () => { try { render(await api(`/v1/admin/keys/${k.id}/test`, "POST")); } catch (e) { $("msg").textContent = e.message; } };
    td.appendChild(b); tr.appendChild(td); kb.appendChild(tr);
  }
  const eb = $("events"); eb.replaceChildren();
  for (const e of s.events) { const tr = document.createElement("tr"); cell(tr, e.time); cell(tr, `${e.key_id} ${e.key}`); cell(tr, e.kind); cell(tr, e.status); cell(tr, e.message); eb.appendChild(tr); }
  $("note").textContent = s.usage_note; $("msg").textContent = "";
}
async function load() { if (!token) { $("msg").textContent = "Enter the admin token."; return; } try { render(await api("/v1/admin/keys")); } catch (e) { $("msg").textContent = e.message; } }
$("save").onclick = () => { const v = $("token").value; if (v && !v.startsWith("•")) { token = v; sessionStorage.setItem("tb_admin", v); $("token").value = "••••••••"; } load(); };
$("refresh").onclick = load;
$("testall").onclick = async () => { try { render(await api("/v1/admin/keys/test", "POST")); } catch (e) { $("msg").textContent = e.message; } };
load(); setInterval(load, 15000);
