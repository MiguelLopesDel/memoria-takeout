import { useEffect, useState } from "react";
import { History, Loader2, Sparkles } from "lucide-react";
import { readJson } from "../api";
import { monthDayLabel } from "../format";
import { MemoryLine } from "../components/primitives";
import type { OnThisDay } from "../types";

export function MemoriesView({ active, privacy, onOpenDay }: { active: boolean; privacy: boolean; onOpenDay: (day: string) => void }) {
  const [data, setData] = useState<OnThisDay | null>(null);
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [jumpDay, setJumpDay] = useState("");

  useEffect(() => {
    if (!active || loaded) return;
    let alive = true;
    setLoading(true);
    fetch("/api/on-this-day")
      .then(readJson)
      .then((payload) => { if (alive) { setData(payload); setLoaded(true); } })
      .catch(() => {})
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [active, loaded]);

  const thisYear = new Date().getFullYear();
  if (loading && !data) return <div className="emptyState"><Loader2 className="spin" size={18} /> Buscando memórias…</div>;
  if (!data) return <div className="emptyState">Não foi possível carregar as memórias.</div>;

  return (
    <div className="memoriesView">
      <header className="memoriesHead">
        <h2><History size={18} /> Neste dia — {monthDayLabel(data.monthDay)}</h2>
        <p>O que você registrava nesta data em anos anteriores.</p>
        <form
          className="dayJump"
          onSubmit={(event) => { event.preventDefault(); if (jumpDay) onOpenDay(jumpDay); }}
        >
          <label htmlFor="dayJumpInput">Ver um dia específico:</label>
          <input id="dayJumpInput" type="date" value={jumpDay} onChange={(event) => setJumpDay(event.target.value)} />
          <button type="submit" className="linkAction" disabled={!jumpDay}>abrir o dia</button>
        </form>
      </header>

      {data.flashback && (
        <div className="flashbackCard">
          <span className="flashbackTag"><Sparkles size={13} /> Relembre</span>
          <MemoryLine event={data.flashback} privacy={privacy} />
          {data.flashback.local_day && (
            <button className="linkAction" onClick={() => onOpenDay(data.flashback!.local_day!)}>abrir esse dia</button>
          )}
        </div>
      )}

      {data.years.length === 0 && <div className="emptyState">Nada registrado nesta data ainda.</div>}

      <div className="memoryYears">
        {data.years.map((group) => {
          const ago = thisYear - Number(group.year);
          const day = `${group.year}-${data.monthDay}`;
          return (
            <section key={group.year} className="memoryYear">
              <div className="memoryYearHead">
                <div className="memoryYearWhen">
                  <strong>{group.year}</strong>
                  <span>{ago <= 0 ? "este ano" : ago === 1 ? "há 1 ano" : `há ${ago} anos`}</span>
                </div>
                <button className="linkAction" onClick={() => onOpenDay(day)}>{group.total.toLocaleString("pt-BR")} eventos · abrir o dia</button>
              </div>
              <div className="memoryLines">
                {group.events.map((event) => <MemoryLine key={event.id} event={event} privacy={privacy} />)}
              </div>
            </section>
          );
        })}
      </div>
    </div>
  );
}
