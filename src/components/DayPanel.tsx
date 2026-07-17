import { useEffect, useMemo } from "react";
import { CalendarDays, Clock, Loader2, X } from "lucide-react";
import { formatDayLong } from "../format";
import { MemoryLine } from "./primitives";
import type { EventRow } from "../types";

export function DayPanel({ day, events, loading, privacy, onClose }: { day: string; events: EventRow[]; loading: boolean; privacy: boolean; onClose: () => void }) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const groups = useMemo(() => {
    const map = new Map<number, EventRow[]>();
    for (const event of events) {
      const hour = typeof event.local_hour === "number" ? event.local_hour : -1;
      if (!map.has(hour)) map.set(hour, []);
      map.get(hour)!.push(event);
    }
    return Array.from(map.entries()).sort((a, b) => a[0] - b[0]);
  }, [events]);

  return (
    <div className="dayOverlay" onClick={onClose}>
      <aside className="dayPanel" onClick={(event) => event.stopPropagation()}>
        <header className="dayPanelHead">
          <div>
            <h3><CalendarDays size={17} /> {formatDayLong(day)}</h3>
            <small>{events.length.toLocaleString("pt-BR")} eventos{events.length >= 5000 ? " (limite de 5000)" : ""}</small>
          </div>
          <button className="iconButton" onClick={onClose} title="Fechar (Esc)"><X size={16} /></button>
        </header>
        <div className="dayPanelBody">
          {loading ? (
            <div className="emptyState"><Loader2 className="spin" size={18} /> Reconstruindo o dia…</div>
          ) : events.length === 0 ? (
            <div className="emptyState">Sem eventos registrados neste dia.</div>
          ) : (
            groups.map(([hour, rows]) => (
              <section key={hour} className="dayHourBlock">
                <div className="dayHourLabel"><Clock size={13} /> {hour < 0 ? "Sem hora" : `${String(hour).padStart(2, "0")}h`}</div>
                <div className="dayHourLines">
                  {rows.map((event) => <MemoryLine key={event.id} event={event} privacy={privacy} />)}
                </div>
              </section>
            ))
          )}
        </div>
      </aside>
    </div>
  );
}
