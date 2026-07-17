import React from "react";
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip as ChartTooltip, XAxis, YAxis } from "recharts";
import { BarChart3, CalendarDays, Globe2, Shield } from "lucide-react";
import { PanelTitle } from "../components/primitives";
import type { Facet, FiltersState } from "../types";

export function CalendarView({
  data,
  ranking,
  quality,
  hourly,
  weekdays,
  loading,
  filters,
  setFilters,
  onBackfill,
  onFormatDedup,
  onOpenDay
}: {
  data: Array<{ day: string; value: number }>;
  ranking: Facet[];
  quality: Array<Record<string, string | number>>;
  hourly: Array<{ hour: number; value: number }>;
  weekdays: Array<{ weekday: number; value: number }>;
  loading: boolean;
  filters: FiltersState;
  setFilters: React.Dispatch<React.SetStateAction<FiltersState>>;
  onBackfill: () => void;
  onFormatDedup: () => void;
  onOpenDay: (day: string) => void;
}) {
  const max = Math.max(...data.map((item) => item.value), 1);
  const hourData = Array.from({ length: 24 }, (_, hour) => ({ hour: `${hour}h`, value: hourly.find((item) => item.hour === hour)?.value ?? 0 }));
  const weekdayData = ["Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb"].map((label, weekday) => ({ label, value: weekdays.find((item) => item.weekday === weekday)?.value ?? 0 }));
  return (
    <div className="calendarGrid">
      <section className="chartPanel">
        <PanelTitle icon={<CalendarDays size={16} />} title={loading ? "Calendário atualizando" : "Calendário de atividade"} />
        <div className="heatmap">
          {data.slice(-370).map((item) => (
            <button
              key={item.day}
              title={`${item.day}: ${item.value} — abrir o dia`}
              style={{ opacity: item.value === 0 ? 0.14 : 0.25 + (item.value / max) * 0.75 }}
              onClick={() => onOpenDay(item.day)}
            />
          ))}
        </div>
        {data.length === 0 && <div className="emptyState compact">Sem datas normalizadas para este filtro.</div>}
      </section>
      <section className="chartPanel">
        <PanelTitle icon={<Globe2 size={16} />} title="Sites dominantes" />
        <div className="rankList">
          {ranking.slice(0, 24).map((item) => (
            <button key={item.label} className={filters.domain === item.label ? "active" : ""} onClick={() => setFilters((current) => ({ ...current, domain: current.domain === item.label ? "" : item.label }))}>
              <span>{item.label}</span><strong>{item.value.toLocaleString("pt-BR")}</strong>
            </button>
          ))}
        </div>
      </section>
      <section className="chartPanel">
        <PanelTitle icon={<BarChart3 size={16} />} title="Horas do dia" />
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={hourData}>
            <CartesianGrid stroke="var(--grid)" vertical={false} />
            <XAxis dataKey="hour" tick={{ fill: "var(--muted)", fontSize: 10 }} tickLine={false} axisLine={false} interval={2} />
            <YAxis tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} width={42} />
            <ChartTooltip contentStyle={{ background: "var(--surface-strong)", border: "1px solid var(--border)", color: "var(--text)" }} />
            <Bar dataKey="value" fill="#67e8f9" radius={[6, 6, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </section>
      <section className="chartPanel">
        <PanelTitle icon={<CalendarDays size={16} />} title="Dia da semana" />
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={weekdayData}>
            <CartesianGrid stroke="var(--grid)" vertical={false} />
            <XAxis dataKey="label" tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} />
            <YAxis tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} width={42} />
            <ChartTooltip contentStyle={{ background: "var(--surface-strong)", border: "1px solid var(--border)", color: "var(--text)" }} />
            <Bar dataKey="value" fill="#a78bfa" radius={[6, 6, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </section>
      <section className="chartPanel span2">
        <div className="panelTitle splitTitle"><span><Shield size={16} /> Qualidade temporal</span><span><button className="secondaryAction" onClick={onBackfill} disabled={loading}>Corrigir datas</button> <button className="secondaryAction" onClick={onFormatDedup} disabled={loading} title="Remove cópias HTML quando o mesmo registro existe em JSON (Takeouts sobrepostos)">Remover duplicados</button></span></div>
        <div className="qualityTable">
          {quality.slice(0, 12).map((row) => (
            <button key={String(row.source)} onClick={() => setFilters((current) => ({ ...current, source: String(row.source) }))}>
              <span>{String(row.source)}</span>
              <strong>{Number(row.timestampCoverage ?? 0).toLocaleString("pt-BR")}%</strong>
              <small>{Number(row.withoutTimestamp ?? 0).toLocaleString("pt-BR")} sem data</small>
            </button>
          ))}
        </div>
      </section>
    </div>
  );
}
