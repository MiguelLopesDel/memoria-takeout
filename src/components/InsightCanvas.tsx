import React from "react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip as ChartTooltip,
  XAxis,
  YAxis
} from "recharts";
import { BarChart3, BookOpen, CalendarDays, Globe2, Shield, Youtube } from "lucide-react";
import { appendFilterValue, compactNumber, formatDate, labelType } from "../format";
import { Metric, PanelTitle } from "./primitives";
import type { ExtraData, FiltersState, Metrics } from "../types";

const accentColors = ["#67e8f9", "#a78bfa", "#fb7185", "#fbbf24", "#34d399", "#f472b6"];

export function InsightCanvas({ metrics, extra, setFilters }: { metrics: Metrics | null; extra: ExtraData; setFilters: React.Dispatch<React.SetStateAction<FiltersState>> }) {
  const timeline = metrics?.timeline.slice(-30).map((item) => ({ name: item.label, value: item.value })) ?? [];
  const topTypes = metrics?.byType.slice(0, 6).map((item) => ({ name: labelType(item.label), value: item.value })) ?? [];
  return (
    <section className="insightCanvas">
      <div className="heroMetric">
        <span>Memória indexada</span>
        <strong>{metrics ? compactNumber(metrics.summary.total) : "0"}</strong>
        <p>{metrics?.summary.firstSeen ? `${formatDate(metrics.summary.firstSeen)} até ${formatDate(metrics.summary.lastSeen)}` : "Importe um Takeout para iniciar."}</p>
      </div>
      <div className="metricStrip">
        <Metric icon={<Globe2 />} label="Domínios" value={metrics ? compactNumber(metrics.summary.domains) : "0"} />
        <Metric icon={<Youtube />} label="Top fonte" value={metrics?.bySource[0]?.label ?? "-"} />
        <Metric icon={<Shield />} label="Ranking" value={extra.ranking[0]?.label ?? "-"} />
      </div>
      <div className="chartPanel span2">
        <PanelTitle icon={<CalendarDays size={16} />} title="Pulso mensal" />
        <ResponsiveContainer width="100%" height={190}>
          <AreaChart data={timeline}>
            <defs>
              <linearGradient id="pulse" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="#67e8f9" stopOpacity={0.75} />
                <stop offset="100%" stopColor="#67e8f9" stopOpacity={0.02} />
              </linearGradient>
            </defs>
            <CartesianGrid stroke="var(--grid)" vertical={false} />
            <XAxis dataKey="name" tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} />
            <YAxis tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} width={42} />
            <ChartTooltip contentStyle={{ background: "var(--surface-strong)", border: "1px solid var(--border)", color: "var(--text)" }} />
            <Area type="monotone" dataKey="value" stroke="#67e8f9" strokeWidth={2} fill="url(#pulse)" />
          </AreaChart>
        </ResponsiveContainer>
      </div>
      <div className="chartPanel">
        <PanelTitle icon={<BarChart3 size={16} />} title="Tipos" />
        <ResponsiveContainer width="100%" height={190}>
          <BarChart data={topTypes} layout="vertical" margin={{ left: 4, right: 12 }}>
            <XAxis type="number" hide />
            <YAxis type="category" dataKey="name" width={86} tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} />
            <ChartTooltip contentStyle={{ background: "var(--surface-strong)", border: "1px solid var(--border)", color: "var(--text)" }} />
            <Bar dataKey="value" radius={[0, 8, 8, 0]}>
              {topTypes.map((_, index) => <Cell key={index} fill={accentColors[index % accentColors.length]} />)}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
      <div className="productCloud">
        <PanelTitle icon={<BookOpen size={16} />} title="Produtos e fontes" />
        <div>
          {extra.products.slice(0, 14).map((item) => (
            <button key={item.label} onClick={() => setFilters((current) => ({ ...current, source: appendFilterValue(current.source, item.label) }))}>
              <span>{item.label}</span><strong>{compactNumber(item.value)}</strong>
            </button>
          ))}
        </div>
      </div>
    </section>
  );
}
