// Tiny presentational atoms shared across tabs.

import React from "react";
import { Database, Globe2, NotebookText, Search, Youtube } from "lucide-react";
import { formatDateTime } from "../format";
import type { EventRow } from "../types";

export function Metric({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return <div className="metricCard"><div>{icon}</div><span>{label}</span><strong>{value}</strong></div>;
}

export function PanelTitle({ icon, title }: { icon: React.ReactNode; title: string }) {
  return <div className="panelTitle">{icon}<h3>{title}</h3></div>;
}

export function EventGlyph({ type }: { type: string }) {
  const Icon = type === "video" ? Youtube : type === "search" ? Search : type === "comment" ? NotebookText : type === "map" ? Globe2 : Database;
  return <div className={`eventGlyph type-${type}`}><Icon size={16} /></div>;
}

export function List({ items }: { items: string[] }) {
  return <ul className="simpleList">{items.slice(0, 28).map((item) => <li key={item}>{item}</li>)}</ul>;
}

export function MemoryLine({ event, privacy }: { event: EventRow; privacy: boolean }) {
  const title = privacy ? "•••" : (event.title || event.url || event.text || "(sem título)");
  const meta = [event.source, event.domain, event.timestamp ? formatDateTime(event.timestamp) : null].filter(Boolean).join(" · ");
  return (
    <div className="memoryLine">
      <EventGlyph type={event.type} />
      <div className="memoryLineBody">
        <span className="memoryLineTitle" title={typeof title === "string" ? title : undefined}>{title}</span>
        <small>{meta}</small>
      </div>
      {event.url && !privacy && <a href={event.url} target="_blank" rel="noreferrer" className="memoryLineOpen">abrir</a>}
    </div>
  );
}
