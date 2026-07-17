import { useRef } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { Search } from "lucide-react";
import { formatDateTime, labelType, mask, stripMarks } from "../format";
import { EventGlyph } from "./primitives";
import type { EventRow } from "../types";

export function EventStream({ events, privacy, selectedEventId, onSelect }: { events: EventRow[]; privacy: boolean; selectedEventId: number | null; onSelect: (event: EventRow) => void }) {
  const parentRef = useRef<HTMLDivElement>(null);
  const rowVirtualizer = useVirtualizer({
    count: events.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 132,
    overscan: 8
  });
  if (events.length === 0) return <div className="emptyState">Nenhum registro encontrado.</div>;
  return (
    <section className="eventStream">
      <div className="streamHead">
        <div><h2>Event stream</h2><p>{events.length.toLocaleString("pt-BR")} registros carregados</p></div>
        <Search size={18} />
      </div>
      <div ref={parentRef} className="virtualList">
        <div style={{ height: `${rowVirtualizer.getTotalSize()}px`, position: "relative" }}>
          {rowVirtualizer.getVirtualItems().map((virtualRow) => {
            const event = events[virtualRow.index];
            return (
              <button
                key={event.id}
                className={event.id === selectedEventId ? "eventRow selected" : "eventRow"}
                style={{ transform: `translateY(${virtualRow.start}px)` }}
                onClick={() => onSelect(event)}
              >
                <EventGlyph type={event.type} />
                <div className="eventBody">
                  <div className="eventMeta"><span>{formatDateTime(event.timestamp)}</span><span>{event.source}</span><span>{labelType(event.type)}</span>{event.domain && <span>{event.domain}</span>}</div>
                  <h3>{mask(event.title || event.url || "Registro sem título", privacy)}</h3>
                  {(event.snippet || event.text) && <p>{mask(stripMarks(event.snippet || event.text), privacy)}</p>}
                </div>
              </button>
            );
          })}
        </div>
      </div>
    </section>
  );
}
