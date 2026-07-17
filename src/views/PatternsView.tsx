import React, { useEffect, useMemo, useState } from "react";
import { Activity, Flame, History, Loader2, Repeat, TrendingUp } from "lucide-react";
import { readJson } from "../api";
import { formatDayLong, monthLabel } from "../format";
import { PanelTitle } from "../components/primitives";
import type { FiltersState, Patterns } from "../types";

const WEEKDAY_LABELS = ["Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb"];

export function PatternsView({ active, query, privacy, setFilters }: { active: boolean; query: string; privacy: boolean; setFilters: React.Dispatch<React.SetStateAction<FiltersState>> }) {
  const [data, setData] = useState<Patterns | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!active) return;
    let alive = true;
    setLoading(true);
    fetch(`/api/patterns?${query}`)
      .then(readJson)
      .then((payload) => { if (alive) setData(payload); })
      .catch(() => {})
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [active, query]);

  const rhythm = useMemo(() => {
    const map = new Map<string, number>();
    let max = 1;
    for (const cell of data?.rhythm ?? []) {
      map.set(`${cell.weekday}-${cell.hour}`, cell.value);
      if (cell.value > max) max = cell.value;
    }
    return { map, max };
  }, [data]);

  if (loading && !data) return <div className="emptyState"><Loader2 className="spin" size={18} /> Analisando padrões…</div>;
  if (!data) return <div className="emptyState">Sem dados de padrões.</div>;

  return (
    <div className="patternsView">
      <section className="chartPanel span2">
        <PanelTitle icon={<Activity size={16} />} title="Rotina — quando você fica ativo (hora × dia da semana)" />
        <div className="rhythmGrid">
          <div className="rhythmCorner" />
          {Array.from({ length: 24 }, (_, hour) => <div key={`h${hour}`} className="rhythmHourLabel">{hour % 3 === 0 ? `${hour}h` : ""}</div>)}
          {WEEKDAY_LABELS.map((label, weekday) => (
            <React.Fragment key={weekday}>
              <div className="rhythmDayLabel">{label}</div>
              {Array.from({ length: 24 }, (_, hour) => {
                const value = rhythm.map.get(`${weekday}-${hour}`) ?? 0;
                return <div key={hour} className="rhythmCell" title={`${label} ${hour}h: ${value.toLocaleString("pt-BR")}`} style={{ opacity: value === 0 ? 0.06 : 0.16 + (value / rhythm.max) * 0.84 }} />;
              })}
            </React.Fragment>
          ))}
        </div>
      </section>

      <section className="chartPanel">
        <PanelTitle icon={<Flame size={16} />} title="Sequências — dias seguidos ativos" />
        <p className="patternsMeta">{data.streaks.activeDays.toLocaleString("pt-BR")} dias com atividade{data.streaks.firstDay ? ` · desde ${formatDayLong(data.streaks.firstDay)}` : ""}</p>
        <div className="streakCards">
          {data.streaks.top.map((streak, index) => (
            <button key={index} className="streakCard" onClick={() => setFilters((current) => ({ ...current, from: streak.start, to: streak.end }))} title="Filtrar por esse período">
              <strong>{streak.length} dias</strong>
              <small>{formatDayLong(streak.start)} → {formatDayLong(streak.end)}</small>
            </button>
          ))}
          {data.streaks.top.length === 0 && <div className="emptyState compact">Sem dias suficientes.</div>}
        </div>
      </section>

      <section className="chartPanel">
        <PanelTitle icon={<Repeat size={16} />} title="Buscas que você repete" />
        <div className="rankList">
          {data.searches.map((search, index) => (
            <button key={index} onClick={() => setFilters((current) => ({ ...current, q: search.title ?? "" }))} title="Buscar isto">
              <span>{privacy ? "•••" : (search.title ?? "(sem título)")}</span>
              <strong>{search.value}×</strong>
            </button>
          ))}
          {data.searches.length === 0 && <div className="emptyState compact">Nenhuma busca repetida.</div>}
        </div>
      </section>

      <section className="chartPanel span2">
        <PanelTitle icon={<TrendingUp size={16} />} title="Fases — sites que dominaram um período" />
        <div className="phaseList">
          {data.phases.map((phase) => {
            const peakMax = Math.max(1, ...phase.series.map((point) => point.value));
            return (
              <div key={phase.label} className="phaseRow">
                <div className="phaseHead">
                  <strong>{phase.label}</strong>
                  <span>{phase.peak ? `pico ${monthLabel(phase.peak)}` : ""} · {phase.total.toLocaleString("pt-BR")}</span>
                </div>
                <div className="phaseSpark">
                  {phase.series.map((point) => (
                    <div key={point.ym} className="phaseBar" title={`${point.ym}: ${point.value.toLocaleString("pt-BR")}`} style={{ height: `${6 + (point.value / peakMax) * 34}px`, opacity: point.ym === phase.peak ? 1 : 0.5 }} />
                  ))}
                </div>
              </div>
            );
          })}
          {data.phases.length === 0 && <div className="emptyState compact">Sem fases para este recorte.</div>}
        </div>
      </section>

      <section className="chartPanel span2">
        <PanelTitle icon={<History size={16} />} title="Páginas que você sempre revisita" />
        <div className="returnsList">
          {data.returns.map((item, index) => (
            <div key={index} className="returnRow">
              <div className="returnBody">
                <span className="memoryLineTitle">{privacy ? "•••" : (item.title || item.url || "(sem título)")}</span>
                <small>{item.days} dias distintos · {item.value}×{item.firstDay ? ` · ${monthLabel(item.firstDay.slice(0, 7))}` : ""}{item.lastDay && item.lastDay.slice(0, 7) !== item.firstDay?.slice(0, 7) ? ` → ${monthLabel(item.lastDay.slice(0, 7))}` : ""}</small>
              </div>
              {item.url && !privacy && <a href={item.url} target="_blank" rel="noreferrer" className="memoryLineOpen">abrir</a>}
            </div>
          ))}
          {data.returns.length === 0 && <div className="emptyState compact">Nada revisitado o bastante.</div>}
        </div>
      </section>
    </div>
  );
}
