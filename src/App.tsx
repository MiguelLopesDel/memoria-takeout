// App shell: owns the shared filter state, the /api orchestration (load, import job
// polling, maintenance actions) and the tab layout. Each tab's UI lives in views/.

import { useEffect, useMemo, useState } from "react";
import * as Tabs from "@radix-ui/react-tabs";
import * as Tooltip from "@radix-ui/react-tooltip";
import {
  Activity,
  BarChart3,
  CalendarDays,
  Download,
  Globe2,
  Hash,
  History,
  Layers3,
  Sparkles,
  Youtube
} from "lucide-react";
import { readJson } from "./api";
import { shouldBrowseBeforeImport } from "./format";
import { initialFilters } from "./types";
import { CommandBar } from "./components/CommandBar";
import { DayPanel } from "./components/DayPanel";
import { EntityPanel } from "./components/EntityPanel";
import { EventStream } from "./components/EventStream";
import { FilterRail } from "./components/FilterRail";
import { InsightCanvas } from "./components/InsightCanvas";
import { CalendarView } from "./views/CalendarView";
import { ExportPanel } from "./views/ExportPanel";
import { MemoriesView } from "./views/MemoriesView";
import { OrganizePanel } from "./views/OrganizePanel";
import { PatternsView } from "./views/PatternsView";
import { SiteView } from "./views/SiteView";
import { TimelineView } from "./views/TimelineView";
import { TopicsView } from "./views/TopicsView";
import { YouTubeView } from "./views/YouTubeView";
import type {
  BrowseResponse,
  EventRow,
  ExtraData,
  Facets,
  FiltersState,
  ImportJob,
  Metrics,
  Status,
  WorkspaceTab
} from "./types";

export function App() {
  const [status, setStatus] = useState<Status | null>(null);
  const [metrics, setMetrics] = useState<Metrics | null>(null);
  const [facets, setFacets] = useState<Facets>({ sources: [], types: [], domains: [] });
  const [events, setEvents] = useState<EventRow[]>([]);
  const [selectedEventId, setSelectedEventId] = useState<number | null>(null);
  const [importPath, setImportPath] = useState("");
  const [pathTouched, setPathTouched] = useState(false);
  const [loading, setLoading] = useState(false);
  const [panelLoading, setPanelLoading] = useState(false);
  const [importing, setImporting] = useState(false);
  const [browserOpen, setBrowserOpen] = useState(false);
  const [browserLoading, setBrowserLoading] = useState(false);
  const [browser, setBrowser] = useState<BrowseResponse | null>(null);
  const [importJob, setImportJob] = useState<ImportJob | null>(null);
  const [error, setError] = useState("");
  const [activeTab, setActiveTab] = useState<WorkspaceTab>("explore");
  const [dayView, setDayView] = useState<string | null>(null);
  const [dayEvents, setDayEvents] = useState<EventRow[]>([]);
  const [dayLoading, setDayLoading] = useState(false);
  const [privacy, setPrivacy] = useState(false);
  const [railOpen, setRailOpen] = useState(true);
  const [extra, setExtra] = useState<ExtraData>({
    products: [],
    days: [],
    calendar: [],
    ranking: [],
    sourceRanking: [],
    typeRanking: [],
    quality: [],
    hourly: [],
    weekdays: [],
    savedFilters: [],
    tags: [],
    collections: []
  });
  const [filters, setFilters] = useState<FiltersState>(initialFilters);
  const [debouncedQ, setDebouncedQ] = useState(filters.q);

  const selectedEvent = useMemo(
    () => events.find((event) => event.id === selectedEventId) ?? events[0] ?? null,
    [events, selectedEventId]
  );

  const query = useMemo(() => {
    const params = new URLSearchParams();
    Object.entries({ ...filters, q: debouncedQ }).forEach(([key, value]) => {
      if (value.trim()) params.set(key, value.trim());
    });
    return params.toString();
  }, [filters, debouncedQ]);

  async function loadData() {
    setLoading(true);
    setError("");
    try {
      const [statusRes, metricsRes, facetsRes, eventsRes] = await Promise.all([
        fetch("/api/status"),
        fetch(`/api/metrics?${query}`),
        fetch(`/api/facets?${query}`),
        fetch(`/api/search?${query}`)
      ]);
      if (!statusRes.ok || !metricsRes.ok || !facetsRes.ok || !eventsRes.ok) throw new Error("Falha ao carregar dados locais.");

      const nextStatus: Status = await statusRes.json();
      const nextEvents = (await eventsRes.json()).rows as EventRow[];
      setStatus(nextStatus);
      if (!pathTouched && !importPath) setImportPath(nextStatus.defaultTakeoutPath);
      setMetrics(await metricsRes.json());
      setFacets(await facetsRes.json());
      setEvents(nextEvents);
      setSelectedEventId((current) => current && nextEvents.some((event) => event.id === current) ? current : nextEvents[0]?.id ?? null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro inesperado.");
    } finally {
      setLoading(false);
    }
  }

  async function loadStaticData() {
    const [products, savedFilters, tags, collections] = await Promise.all([
      fetch("/api/products").then((res) => res.json()),
      fetch("/api/saved-filters").then((res) => res.json()),
      fetch("/api/tags").then((res) => res.json()),
      fetch("/api/collections").then((res) => res.json())
    ]);
    setExtra((current) => ({ ...current, products, savedFilters, tags, collections }));
  }

  async function loadPanels() {
    setPanelLoading(true);
    try {
      const [days, calendar, ranking, sourceRanking, typeRanking, quality, overview] = await Promise.all([
      fetch(`/api/timeline/days?${query}`).then((res) => res.json()),
      fetch(`/api/metrics/calendar?${query}`).then((res) => res.json()),
      fetch(`/api/rankings/domains?${query}&limit=50`).then((res) => res.json()),
      fetch(`/api/rankings/sources?${query}&limit=50`).then((res) => res.json()),
      fetch(`/api/rankings/types?${query}&limit=50`).then((res) => res.json()),
      fetch(`/api/quality?${query}`).then((res) => res.json()),
      fetch(`/api/metrics/overview?${query}`).then((res) => res.json())
      ]);
      setExtra((current) => ({ ...current, days, calendar, ranking, sourceRanking, typeRanking, quality, hourly: overview.hourly ?? [], weekdays: overview.weekdays ?? [] }));
    } finally {
      setPanelLoading(false);
    }
  }

  async function runImport() {
    if (shouldBrowseBeforeImport(importPath)) {
      await loadBrowser(importPath || "");
      setError("Selecione uma pasta Takeout específica antes de importar.");
      return;
    }
    setImporting(true);
    setError("");
    try {
      const res = await fetch("/api/import", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ path: importPath })
      });
      const payload = await readJson(res);
      if (!res.ok) throw new Error(payload?.error ?? `Importação falhou (HTTP ${res.status}).`);
      if (!payload?.id) throw new Error("Resposta de importação inválida — reinicie o backend para carregar as mudanças.");
      setImportJob(payload);
      await waitForImport(payload.id);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro inesperado.");
    } finally {
      setImporting(false);
    }
  }

  async function resetBase() {
    const confirmed = window.confirm(
      "Limpar base: apaga TODOS os eventos, tags, coleções e notas importados. Esta ação não pode ser desfeita. Deseja continuar?"
    );
    if (!confirmed) return;
    setImporting(true);
    setError("");
    try {
      const res = await fetch("/api/reset", { method: "POST" });
      const payload = await readJson(res);
      if (!res.ok) {
        throw new Error(payload?.error
          ?? `Não foi possível limpar a base (HTTP ${res.status}). Se o endpoint /api/reset não existe, reinicie o backend.`);
      }
      await loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro inesperado.");
    } finally {
      setImporting(false);
    }
  }

  async function waitForImport(jobId: string) {
    for (;;) {
      await new Promise((resolve) => window.setTimeout(resolve, 1200));
      const res = await fetch("/api/import/status");
      const job = (await readJson(res)) as ImportJob | null;
      if (!job) continue;
      setImportJob(job);
      if (job.id !== jobId) return;
      if (job.status === "done") {
        await loadData();
        return;
      }
      if (job.status === "error") throw new Error(job.error ?? "Importação falhou.");
    }
  }

  async function resumeImportStatus() {
    try {
      const res = await fetch("/api/import/status");
      const job = (await readJson(res)) as ImportJob | null;
      if (!job) return;
      setImportJob(job);
      if (job.status === "running") {
        setImporting(true);
        await waitForImport(job.id);
        setImporting(false);
      }
    } catch {
      setImporting(false);
    }
  }

  async function loadBrowser(path = "") {
    setBrowserLoading(true);
    setError("");
    try {
      const params = path ? `?path=${encodeURIComponent(path)}` : "";
      const res = await fetch(`/api/files/browse${params}`);
      const payload = await res.json();
      if (!res.ok) throw new Error(payload.error ?? "Falha ao listar pastas.");
      setBrowser(payload);
      setBrowserOpen(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro inesperado.");
    } finally {
      setBrowserLoading(false);
    }
  }

  async function openDay(day: string) {
    setDayView(day);
    setDayEvents([]);
    setDayLoading(true);
    try {
      const res = await fetch(`/api/day?date=${encodeURIComponent(day)}`);
      const rows = await readJson(res);
      setDayEvents(Array.isArray(rows) ? rows : []);
    } catch {
      setDayEvents([]);
    } finally {
      setDayLoading(false);
    }
  }

  async function postJson(url: string, body: Record<string, unknown>) {
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
    if (!res.ok) throw new Error("Operação falhou.");
    await loadStaticData();
    return res.json().catch(() => null);
  }

  async function runTimestampBackfill() {
    setPanelLoading(true);
    setError("");
    try {
      const res = await fetch("/api/backfill/timestamps", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ limit: 100000 })
      });
      const payload = await res.json();
      if (!res.ok) throw new Error(payload.error ?? "Backfill falhou.");
      await Promise.all([loadData(), loadPanels()]);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro inesperado.");
    } finally {
      setPanelLoading(false);
    }
  }

  // Removes HTML-format rows duplicated by a JSON twin (old + new Takeout of the same
  // account); annotations are consolidated onto the surviving row by the backend.
  async function runFormatDedup() {
    const confirmed = window.confirm(
      "Remover duplicados de formato: quando o mesmo registro existe no formato HTML (Takeout antigo) e JSON (Takeout novo), a cópia HTML é removida. Tags, coleções e notas são movidas para a cópia que fica. Continuar?"
    );
    if (!confirmed) return;
    setPanelLoading(true);
    setError("");
    try {
      const res = await fetch("/api/cleanup/format-duplicates", { method: "POST" });
      const payload = await readJson(res);
      if (!res.ok) throw new Error(payload?.error ?? `Limpeza falhou (HTTP ${res.status}).`);
      await Promise.all([loadData(), loadPanels()]);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro inesperado.");
    } finally {
      setPanelLoading(false);
    }
  }

  useEffect(() => {
    loadData();
  }, [query]);

  useEffect(() => {
    loadPanels();
  }, [query]);

  useEffect(() => {
    const id = window.setTimeout(() => setDebouncedQ(filters.q), 280);
    return () => window.clearTimeout(id);
  }, [filters.q]);

  useEffect(() => {
    void loadStaticData();
    void resumeImportStatus();
  }, []);

  return (
    <Tooltip.Provider delayDuration={220}>
      <main className={railOpen ? "osShell" : "osShell railCollapsed"}>
        <FilterRail
          open={railOpen}
          status={status}
          filters={filters}
          facets={facets}
          importPath={importPath}
          importing={importing}
          browserOpen={browserOpen}
          browserLoading={browserLoading}
          browser={browser}
          importJob={importJob}
          setFilters={setFilters}
          setImportPath={(path) => {
            setPathTouched(true);
            setImportPath(path);
          }}
          runImport={runImport}
          resetBase={resetBase}
          loadBrowser={loadBrowser}
          closeBrowser={() => setBrowserOpen(false)}
        />

        <section className="workspace">
          <CommandBar
            filters={filters}
            setFilters={setFilters}
            status={status}
            loading={loading}
            privacy={privacy}
            setPrivacy={setPrivacy}
            railOpen={railOpen}
            setRailOpen={setRailOpen}
          />

          {error && <div className="errorBanner">{error}</div>}

          <Tabs.Root value={activeTab} onValueChange={(value) => setActiveTab(value as WorkspaceTab)} className="workspaceTabs">
            <Tabs.List className="tabList" aria-label="Visões do Memoria">
              <Tabs.Trigger value="explore"><Sparkles size={15} /> Explorar</Tabs.Trigger>
              <Tabs.Trigger value="memorias"><History size={15} /> Memórias</Tabs.Trigger>
              <Tabs.Trigger value="padroes"><Activity size={15} /> Padrões</Tabs.Trigger>
              <Tabs.Trigger value="youtube"><Youtube size={15} /> YouTube</Tabs.Trigger>
              <Tabs.Trigger value="assuntos"><Hash size={15} /> Assuntos</Tabs.Trigger>
              <Tabs.Trigger value="timeline"><CalendarDays size={15} /> Timeline</Tabs.Trigger>
              <Tabs.Trigger value="calendar"><BarChart3 size={15} /> Calendário</Tabs.Trigger>
              <Tabs.Trigger value="site"><Globe2 size={15} /> Site</Tabs.Trigger>
              <Tabs.Trigger value="organize"><Layers3 size={15} /> Organizar</Tabs.Trigger>
              <Tabs.Trigger value="export"><Download size={15} /> Exportar</Tabs.Trigger>
            </Tabs.List>

            <Tabs.Content value="explore" className="tabPanel">
              <div className="exploreGrid">
                <InsightCanvas metrics={metrics} extra={extra} setFilters={setFilters} />
                <EventStream
                  events={events}
                  privacy={privacy}
                  selectedEventId={selectedEvent?.id ?? null}
                  onSelect={(event) => setSelectedEventId(event.id)}
                />
                <EntityPanel event={selectedEvent} privacy={privacy} tags={extra.tags} collections={extra.collections} postJson={postJson} />
              </div>
            </Tabs.Content>

            <Tabs.Content value="memorias" className="tabPanel">
              <MemoriesView active={activeTab === "memorias"} privacy={privacy} onOpenDay={openDay} />
            </Tabs.Content>

            <Tabs.Content value="padroes" className="tabPanel">
              <PatternsView active={activeTab === "padroes"} query={query} privacy={privacy} setFilters={setFilters} />
            </Tabs.Content>

            <Tabs.Content value="youtube" className="tabPanel">
              <YouTubeView active={activeTab === "youtube"} query={query} privacy={privacy} onOpenDay={openDay} />
            </Tabs.Content>

            <Tabs.Content value="assuntos" className="tabPanel">
              <TopicsView active={activeTab === "assuntos"} query={query} privacy={privacy} />
            </Tabs.Content>

            <Tabs.Content value="timeline" className="tabPanel">
              <TimelineView days={extra.days} events={events} privacy={privacy} onSelect={(event) => setSelectedEventId(event.id)} onOpenDay={openDay} />
            </Tabs.Content>

            <Tabs.Content value="calendar" className="tabPanel">
              <CalendarView data={extra.calendar} ranking={extra.ranking} quality={extra.quality} hourly={extra.hourly} weekdays={extra.weekdays} loading={panelLoading} filters={filters} setFilters={setFilters} onBackfill={runTimestampBackfill} onFormatDedup={runFormatDedup} onOpenDay={openDay} />
            </Tabs.Content>

            <Tabs.Content value="site" className="tabPanel">
              <SiteView initialDomain={filters.domain} />
            </Tabs.Content>

            <Tabs.Content value="organize" className="tabPanel">
              <OrganizePanel filters={filters} savedFilters={extra.savedFilters} tags={extra.tags} collections={extra.collections} postJson={postJson} privacy={privacy} onSelect={(event: EventRow) => { setSelectedEventId(event.id); setActiveTab("explore"); }} />
            </Tabs.Content>

            <Tabs.Content value="export" className="tabPanel">
              <ExportPanel query={query} privacy={privacy} />
            </Tabs.Content>
          </Tabs.Root>
        </section>
        {dayView && <DayPanel day={dayView} events={dayEvents} loading={dayLoading} privacy={privacy} onClose={() => setDayView(null)} />}
      </main>
    </Tooltip.Provider>
  );
}
