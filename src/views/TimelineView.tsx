import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip as ChartTooltip, XAxis, YAxis } from "recharts";
import { CalendarDays } from "lucide-react";
import { EventStream } from "../components/EventStream";
import { PanelTitle } from "../components/primitives";
import type { EventRow } from "../types";

export function TimelineView({ days, events, privacy, onSelect, onOpenDay }: { days: Array<{ day: string; total: number }>; events: EventRow[]; privacy: boolean; onSelect: (event: EventRow) => void; onOpenDay: (day: string) => void }) {
  return (
    <div className="twoColumnView">
      <div className="chartPanel">
        <PanelTitle icon={<CalendarDays size={16} />} title="Dias recentes" />
        <ResponsiveContainer width="100%" height={360}>
          <BarChart data={days.slice(0, 60).reverse().map((day) => ({ name: day.day.slice(5), day: day.day, value: day.total }))}>
            <CartesianGrid stroke="var(--grid)" vertical={false} />
            <XAxis dataKey="name" tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} />
            <YAxis tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} width={42} />
            <ChartTooltip contentStyle={{ background: "var(--surface-strong)", border: "1px solid var(--border)", color: "var(--text)" }} />
            <Bar dataKey="value" fill="#a78bfa" radius={[8, 8, 0, 0]} cursor="pointer" onClick={(payload: any) => payload?.day && onOpenDay(payload.day)} />
          </BarChart>
        </ResponsiveContainer>
      </div>
      <EventStream events={events.slice(0, 80)} privacy={privacy} selectedEventId={null} onSelect={onSelect} />
    </div>
  );
}
