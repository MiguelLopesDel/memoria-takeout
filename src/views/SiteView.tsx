import { useEffect, useState } from "react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip as ChartTooltip,
  XAxis,
  YAxis
} from "recharts";
import { BarChart3, CalendarDays, Globe2, Layers3, Search, Sparkles, X } from "lucide-react";
import { formatDate, labelType } from "../format";
import { PanelTitle } from "../components/primitives";
import type { Facet, SiteReport } from "../types";

export function SiteView({ initialDomain }: { initialDomain?: string }) {
  const [mode, setMode] = useState<"root" | "host">("root");
  const [search, setSearch] = useState("");
  const [options, setOptions] = useState<Facet[]>([]);
  const [selected, setSelected] = useState<string>(() =>
    initialDomain && !initialDomain.includes(",") && !initialDomain.startsWith("-") ? initialDomain : ""
  );
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [report, setReport] = useState<SiteReport | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (selected) return;
    let active = true;
    const id = window.setTimeout(() => {
      fetch(`/api/domains?group=${mode}&search=${encodeURIComponent(search)}&limit=200`)
        .then((res) => res.json())
        .then((data) => { if (active) setOptions(data); })
        .catch(() => {});
    }, 220);
    return () => { active = false; window.clearTimeout(id); };
  }, [search, mode, selected]);

  useEffect(() => {
    if (!selected) { setReport(null); return; }
    let active = true;
    setLoading(true);
    const params = new URLSearchParams({ domain: selected, whole: String(mode === "root"), limit: "40" });
    if (from) params.set("from", from);
    if (to) params.set("to", to);
    fetch(`/api/site?${params.toString()}`)
      .then((res) => res.json())
      .then((data) => { if (active) setReport(data); })
      .catch(() => {})
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [selected, mode, from, to]);

  if (!selected) {
    return (
      <div className="siteView">
        <section className="chartPanel span2">
          <div className="panelTitle splitTitle">
            <span><Globe2 size={16} /> Escolha um site para analisar</span>
            <div className="modeToggle">
              <button className={mode === "root" ? "active" : ""} onClick={() => setMode("root")}>Domínio principal</button>
              <button className={mode === "host" ? "active" : ""} onClick={() => setMode("host")}>Host exato</button>
            </div>
          </div>
          <p className="siteHint">
            {mode === "root"
              ? "Agrupa subdomínios (pt./en./m.) num site só. Busque entre todos os seus sites e clique para ver métricas, artigos mais lidos e curiosidades."
              : "Cada subdomínio é listado separadamente. Busque e clique para analisar um host específico."}
          </p>
          <div className="siteSearchBox">
            <Search size={16} />
            <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Filtrar sites por nome (ex.: reddit, wikipedia, .br, manga)" />
            {search && <button className="iconButton" onClick={() => setSearch("")} title="Limpar"><X size={14} /></button>}
          </div>
          <div className="rankList">
            {options.map((item) => (
              <button key={item.label} onClick={() => setSelected(item.label)}>
                <span>{item.label}</span><strong>{item.value.toLocaleString("pt-BR")}</strong>
              </button>
            ))}
            {options.length === 0 && <p className="siteHint">{search ? "Nenhum site corresponde à busca." : "Nenhum site indexado ainda."}</p>}
          </div>
        </section>
      </div>
    );
  }

  const timeline = (report?.timeline ?? []).map((item) => ({ name: item.label, value: item.value }));
  const typeData = (report?.byType ?? []).slice(0, 8).map((item) => ({ name: labelType(item.label), value: item.value }));
  const hourData = Array.from({ length: 24 }, (_, hour) => ({ hour: `${hour}h`, value: report?.hourly.find((item) => item.hour === hour)?.value ?? 0 }));
  const total = report?.summary.total ?? 0;
  const pages = report?.topPages ?? [];
  const returns = report?.topReturns ?? [];
  const insights = buildSiteInsights(report);

  return (
    <div className="siteView">
      <section className="chartPanel span2 siteHeader">
        <div>
          <div className="siteTitle"><Globe2 size={18} /><h2>{selected}</h2>{report && !report.whole && mode === "host" && <span className="siteTag">host</span>}</div>
          <p>{total.toLocaleString("pt-BR")} eventos{report?.summary.firstSeen ? ` · ${formatDate(report.summary.firstSeen)} até ${formatDate(report.summary.lastSeen)}` : ""}{loading ? " · atualizando..." : ""}</p>
        </div>
        <div className="siteRefine">
          <label>De<input type="date" value={from} onChange={(event) => setFrom(event.target.value)} /></label>
          <label>Até<input type="date" value={to} onChange={(event) => setTo(event.target.value)} /></label>
          {(from || to) && <button className="secondaryAction" onClick={() => { setFrom(""); setTo(""); }}>Limpar período</button>}
          <button className="secondaryAction" onClick={() => { setSelected(""); setFrom(""); setTo(""); }}>Trocar site</button>
        </div>
      </section>

      {insights.length > 0 && (
        <section className="chartPanel span2">
          <PanelTitle icon={<Sparkles size={16} />} title="Curiosidades" />
          <div className="insightStrip">
            {insights.map((item) => (
              <div className="insightCard" key={item.label}>
                <span>{item.label}</span>
                <strong>{item.value}</strong>
                {item.hint && <small>{item.hint}</small>}
              </div>
            ))}
          </div>
        </section>
      )}

      <section className="chartPanel span2">
        <PanelTitle icon={<CalendarDays size={16} />} title="Atividade mensal no site" />
        <ResponsiveContainer width="100%" height={200}>
          <AreaChart data={timeline}>
            <defs>
              <linearGradient id="sitePulse" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="#34d399" stopOpacity={0.7} />
                <stop offset="100%" stopColor="#34d399" stopOpacity={0.02} />
              </linearGradient>
            </defs>
            <CartesianGrid stroke="var(--grid)" vertical={false} />
            <XAxis dataKey="name" tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} />
            <YAxis tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} width={42} />
            <ChartTooltip contentStyle={{ background: "var(--surface-strong)", border: "1px solid var(--border)", color: "var(--text)" }} />
            <Area type="monotone" dataKey="value" stroke="#34d399" strokeWidth={2} fill="url(#sitePulse)" />
          </AreaChart>
        </ResponsiveContainer>
      </section>

      <section className="chartPanel">
        <PanelTitle icon={<BarChart3 size={16} />} title="Horas do dia" />
        <ResponsiveContainer width="100%" height={200}>
          <BarChart data={hourData}>
            <CartesianGrid stroke="var(--grid)" vertical={false} />
            <XAxis dataKey="hour" tick={{ fill: "var(--muted)", fontSize: 10 }} tickLine={false} axisLine={false} interval={2} />
            <YAxis tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} width={42} />
            <ChartTooltip contentStyle={{ background: "var(--surface-strong)", border: "1px solid var(--border)", color: "var(--text)" }} />
            <Bar dataKey="value" fill="#34d399" radius={[6, 6, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </section>

      <section className="chartPanel">
        <PanelTitle icon={<Layers3 size={16} />} title="Tipos de interação" />
        <div className="rankList">
          {typeData.map((item) => (
            <div className="rankStatic" key={item.name}><span>{item.name}</span><strong>{item.value.toLocaleString("pt-BR")}</strong></div>
          ))}
          {typeData.length === 0 && <p className="siteHint">Sem eventos para este período.</p>}
        </div>
      </section>

      <section className="chartPanel">
        <PanelTitle icon={<Search size={16} />} title="Mais frequentes" />
        <div className="pageList">
          {pages.map((page, index) => (
            <a key={(page.url ?? "") + index} className="pageRow" href={page.url ?? undefined} target="_blank" rel="noreferrer" title={page.url ?? ""}>
              <span className="pageTitle">{page.title || page.url || "Sem título"}</span>
              <span className="pageUrl">{page.url}</span>
              <strong>{page.value.toLocaleString("pt-BR")}</strong>
            </a>
          ))}
          {pages.length === 0 && <p className="siteHint">Nenhuma página com URL neste site/período.</p>}
        </div>
      </section>

      <section className="chartPanel">
        <PanelTitle icon={<CalendarDays size={16} />} title="Você sempre voltava" />
        <div className="pageList">
          {returns.map((page, index) => (
            <a key={(page.url ?? "") + index} className="pageRow returnRow" href={page.url ?? undefined} target="_blank" rel="noreferrer" title={page.url ?? ""}>
              <span className="pageTitle">{page.title || page.url || "Sem título"}</span>
              <span className="pageUrl">{page.firstDay} — {page.lastDay}</span>
              <strong>{page.days} dias</strong>
            </a>
          ))}
          {returns.length === 0 && <p className="siteHint">Nenhuma página revisitada em dias diferentes neste período.</p>}
        </div>
      </section>
    </div>
  );
}

function buildSiteInsights(report: SiteReport | null): Array<{ label: string; value: string; hint?: string }> {
  if (!report) return [];
  const weekdayNames = ["Domingo", "Segunda", "Terça", "Quarta", "Quinta", "Sexta", "Sábado"];
  const insights: Array<{ label: string; value: string; hint?: string }> = [];

  const topMonth = [...(report.timeline ?? [])].sort((a, b) => b.value - a.value)[0];
  if (topMonth) insights.push({ label: "Mês mais ativo", value: topMonth.label, hint: `${topMonth.value.toLocaleString("pt-BR")} eventos` });

  const topWeekday = [...(report.weekdays ?? [])].sort((a, b) => b.value - a.value)[0];
  if (topWeekday) insights.push({ label: "Dia favorito", value: weekdayNames[topWeekday.weekday] ?? "-" });

  const topHour = [...(report.hourly ?? [])].sort((a, b) => b.value - a.value)[0];
  if (topHour) insights.push({ label: "Horário de pico", value: `${topHour.hour}h` });

  const mostRevisited = report.topReturns?.[0];
  if (mostRevisited) insights.push({ label: "Mais revisitado", value: `${mostRevisited.days} dias`, hint: (mostRevisited.title || mostRevisited.url || "").slice(0, 42) });

  if (report.summary.firstSeen) insights.push({ label: "Primeira visita", value: formatDate(report.summary.firstSeen) });

  return insights;
}
