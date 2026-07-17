import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import * as Tabs from "@radix-ui/react-tabs";
import * as Tooltip from "@radix-ui/react-tooltip";
import { useVirtualizer } from "@tanstack/react-virtual";
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
import {
  Activity,
  Archive,
  BarChart3,
  BookOpen,
  CalendarDays,
  Check,
  ChevronLeft,
  Clock,
  Command,
  Database,
  Download,
  EyeOff,
  FileArchive,
  Filter,
  FolderInput,
  Flame,
  FolderOpen,
  Globe2,
  HardDrive,
  Hash,
  History,
  Layers3,
  Loader2,
  NotebookText,
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  Repeat,
  Search,
  Shield,
  Sparkles,
  Tag,
  TrendingUp,
  X,
  Youtube
} from "lucide-react";
import "./styles.css";

type Status = {
  dbPath: string;
  eventCount: number;
  defaultTakeoutPath: string;
  latestImport: null | { source_path: string; imported_at: string; event_count: number };
};
type Facet = { label: string; value: number };
type Metrics = {
  summary: { total: number; domains: number; firstSeen: string | null; lastSeen: string | null };
  byType: Facet[];
  bySource: Facet[];
  topDomains: Facet[];
  timeline: Facet[];
};
type EventRow = {
  id: number;
  timestamp: string | null;
  source: string;
  type: string;
  title: string | null;
  text: string | null;
  url: string | null;
  domain: string | null;
  file_path?: string | null;
  local_day?: string | null;
  local_hour?: number | null;
  snippet?: string | null;
};
type OnThisDay = {
  monthDay: string;
  years: Array<{ year: string; total: number; events: EventRow[] }>;
  flashback: EventRow | null;
};
type Phase = { label: string; total: number; first: string | null; last: string | null; peak: string | null; peakValue: number; months: number; series: Array<{ ym: string; value: number }> };
type RecurringSearch = { title: string | null; value: number; days: number; firstDay: string | null; lastDay: string | null };
type Patterns = {
  rhythm: Array<{ weekday: number; hour: number; value: number }>;
  streaks: { activeDays: number; firstDay: string | null; lastDay: string | null; top: Array<{ length: number; start: string; end: string }> };
  phases: Phase[];
  returns: SiteReturn[];
  searches: RecurringSearch[];
};
type Facets = { sources: Facet[]; types: Facet[]; domains: Facet[] };
type BrowseEntry = { name: string; path: string; type: "directory" | "archive"; root: boolean; importable: boolean };
type BrowseResponse = { path: string; parent: string | null; entries: BrowseEntry[]; importable: boolean };
type ExtraData = {
  products: Facet[];
  days: Array<{ day: string; total: number }>;
  calendar: Array<{ day: string; value: number }>;
  ranking: Facet[];
  sourceRanking: Facet[];
  typeRanking: Facet[];
  quality: Array<Record<string, string | number>>;
  hourly: Array<{ hour: number; value: number }>;
  weekdays: Array<{ weekday: number; value: number }>;
  savedFilters: Array<Record<string, string | number>>;
  tags: Array<{ id: number; name: string; color: string }>;
  collections: Array<{ id: number; name: string; description: string }>;
};
type SitePage = { url: string | null; title: string | null; value: number; lastSeen?: string | null };
type SiteReturn = { url: string | null; title: string | null; days: number; value: number; firstDay: string | null; lastDay: string | null };
type SiteReport = {
  domain: string;
  whole: boolean;
  summary: { total: number; domains: number; firstSeen: string | null; lastSeen: string | null };
  timeline: Facet[];
  byType: Facet[];
  hourly: Array<{ hour: number; value: number }>;
  weekdays: Array<{ weekday: number; value: number }>;
  days: Array<{ day: string; total: number }>;
  topPages: SitePage[];
  topReturns: SiteReturn[];
};
type FiltersState = { q: string; source: string; type: string; domain: string; from: string; to: string };
type WorkspaceTab = "explore" | "memorias" | "padroes" | "youtube" | "assuntos" | "timeline" | "calendar" | "site" | "organize" | "export";
type YtVideo = { videoId: string; title: string | null; channel: string | null; value: number; interactions: number; days: number; firstDay: string | null; lastDay: string | null; firstSeen: string | null };
type YtChannel = { channel: string; value: number; videos: number; days: number; months: number; firstDay: string | null; lastDay: string | null };
type YtChannelPhase = Phase & { status: "ativo" | "abandonado" | "novo" };
type YtReport = {
  summary: { total: number; videos: number; channels: number; comments: number; activeDays: number; firstSeen: string | null; lastSeen: string | null };
  timeline: Facet[];
  byType: Facet[];
  topVideos: YtVideo[];
  topChannels: YtChannel[];
  channelPhases: YtChannelPhase[];
};
type YtVideoDetail = {
  videoId: string;
  summary: { total: number; views: number; interactions: number; days: number; title: string | null; channel: string | null; firstSeen: string | null; lastSeen: string | null; firstDay: string | null; lastDay: string | null };
  byYear: Array<{ year: string; value: number }>;
  byType: Facet[];
  events: Array<EventRow & { channel?: string | null }>;
};
type YtChannelDetail = {
  channel: string;
  summary: { total: number; videos: number; days: number; firstSeen: string | null; lastSeen: string | null };
  timeline: Facet[];
  topVideos: Array<{ videoId: string; title: string | null; value: number; interactions: number; firstDay: string | null; lastDay: string | null }>;
};
type Topic = { id: number; name: string; color: string; keywords: string; created_at: string };
type TopicReport = {
  keywords: string;
  summary: { total: number; activeDays: number; domains: number; firstSeen: string | null; lastSeen: string | null };
  timeline: Facet[];
  bySource: Facet[];
  byType: Facet[];
  topDomains: Facet[];
  topPages: Array<{ url: string | null; title: string | null; value: number; days: number; firstDay: string | null; lastDay: string | null }>;
  topChannels: Array<{ channel: string; value: number; firstDay: string | null; lastDay: string | null }>;
  searches: Array<{ title: string | null; value: number; firstDay: string | null; lastDay: string | null }>;
  recent: EventRow[];
};
type ImportJob = {
  id: string;
  path: string;
  status: "idle" | "running" | "done" | "error";
  message: string;
  eventCount: number;
  filesFound: number;
  filesProcessed: number;
  currentFile?: string | null;
  currentSource?: string | null;
  bytesRead?: number;
  eventsPerMinute?: number;
  elapsedSeconds?: number;
  estimatedRemainingSeconds?: number | null;
  startedAt: string | null;
  finishedAt: string | null;
  error: string | null;
};

const initialFilters: FiltersState = { q: "", source: "", type: "", domain: "", from: "", to: "" };
const accentColors = ["#67e8f9", "#a78bfa", "#fb7185", "#fbbf24", "#34d399", "#f472b6"];

// Parses a response body defensively: an empty or non-JSON body (e.g. a 404 for an
// endpoint the running backend doesn't have yet) returns null instead of throwing the
// cryptic "Unexpected end of JSON input".
async function readJson(res: Response): Promise<any> {
  const text = await res.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function App() {
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

function CommandBar({
  filters,
  setFilters,
  status,
  loading,
  privacy,
  setPrivacy,
  railOpen,
  setRailOpen
}: {
  filters: FiltersState;
  setFilters: React.Dispatch<React.SetStateAction<FiltersState>>;
  status: Status | null;
  loading: boolean;
  privacy: boolean;
  setPrivacy: React.Dispatch<React.SetStateAction<boolean>>;
  railOpen: boolean;
  setRailOpen: React.Dispatch<React.SetStateAction<boolean>>;
}) {
  const chips = activeChips(filters);
  return (
    <header className="commandBar">
      <button className="iconButton" onClick={() => setRailOpen((value) => !value)} title={railOpen ? "Ocultar filtros" : "Mostrar filtros"}>
        {railOpen ? <PanelLeftClose size={18} /> : <PanelLeftOpen size={18} />}
      </button>
      <div className="globalSearch">
        <Command size={18} />
        <input
          value={filters.q}
          onChange={(event) => setFilters((current) => ({ ...current, q: event.target.value }))}
          placeholder="Buscar na sua memória digital, ou use site:youtube.com type:comment"
        />
      </div>
      <div className="chipDock">
        {chips.map((chip) => (
          <button className="queryChip" key={chip.key} title="Remover filtro" onClick={() => setFilters((current) => ({ ...current, [chip.key]: "" }))}>
            {chip.label} <X size={11} />
          </button>
        ))}
      </div>
      <div className="statusPill">
        {loading ? <Loader2 className="spin" size={15} /> : <Database size={15} />}
        <span>{status?.eventCount ? compactNumber(status.eventCount) : "sem base"}</span>
      </div>
      <Tooltip.Root>
        <Tooltip.Trigger asChild>
          <button className={privacy ? "iconButton active" : "iconButton"} onClick={() => setPrivacy((value) => !value)}>
            <EyeOff size={18} />
          </button>
        </Tooltip.Trigger>
        <Tooltip.Portal><Tooltip.Content className="tooltip">Modo privacidade<Tooltip.Arrow /></Tooltip.Content></Tooltip.Portal>
      </Tooltip.Root>
    </header>
  );
}

function FilterRail({
  open,
  status,
  filters,
  facets,
  importPath,
  importing,
  browserOpen,
  browserLoading,
  browser,
  importJob,
  setFilters,
  setImportPath,
  runImport,
  resetBase,
  loadBrowser,
  closeBrowser
}: {
  open: boolean;
  status: Status | null;
  filters: FiltersState;
  facets: Facets;
  importPath: string;
  importing: boolean;
  browserOpen: boolean;
  browserLoading: boolean;
  browser: BrowseResponse | null;
  importJob: ImportJob | null;
  setFilters: React.Dispatch<React.SetStateAction<FiltersState>>;
  setImportPath: (path: string) => void;
  runImport: () => void;
  resetBase: () => void;
  loadBrowser: (path?: string) => void;
  closeBrowser: () => void;
}) {
  return (
    <aside className={open ? "filterRail" : "filterRail hidden"}>
      <div className="brandBlock">
        <div className="brandGlyph"><Archive size={21} /></div>
        <div>
          <h1>Memoria</h1>
          <p>Google Takeout OS</p>
        </div>
      </div>

      <section className="railSection importDock">
        <div className="sectionHead"><FolderInput size={16} /><span>Importação</span></div>
        <div className="pathControl">
          <input value={importPath} onChange={(event) => setImportPath(event.target.value)} placeholder="/imports" />
          <button title="Navegar" onClick={() => loadBrowser(importPath)} disabled={browserLoading}>
            {browserLoading ? <Loader2 className="spin" size={17} /> : <FolderOpen size={17} />}
          </button>
          <button title={shouldBrowseBeforeImport(importPath) ? "Escolher Takeout" : "Importar Takeout"} onClick={runImport} disabled={importing}>
            {importing ? <Loader2 className="spin" size={17} /> : shouldBrowseBeforeImport(importPath) ? <FolderInput size={17} /> : <Database size={17} />}
          </button>
        </div>
        {browserOpen && <FileBrowser browser={browser} loading={browserLoading} currentPath={importPath} onOpen={loadBrowser} onClose={closeBrowser} onSelect={setImportPath} />}
        {importJob?.status === "running" && <ImportProgress job={importJob} />}
        <small>A importação mescla: reimportar só adiciona eventos novos e preserva tags, coleções e notas.</small>
        <div className="importFootRow">
          <small>{status?.latestImport ? `Última: ${formatDate(status.latestImport.imported_at)}` : "Nenhum Takeout importado."}</small>
          {status?.eventCount ? (
            <button className="linkDanger" onClick={resetBase} disabled={importing} title="Apagar tudo e recomeçar">Limpar base</button>
          ) : null}
        </div>
      </section>

      <section className="railSection">
        <div className="sectionHead"><Filter size={16} /><span>Filtros vivos</span></div>
        <select value={filters.source} onChange={(event) => setFilters((current) => ({ ...current, source: event.target.value }))}>
          <option value="">Todas as fontes</option>
          {facets.sources.map((item) => <option key={item.label} value={item.label}>{item.label} ({item.value})</option>)}
        </select>
        <select value={filters.type} onChange={(event) => setFilters((current) => ({ ...current, type: event.target.value }))}>
          <option value="">Todos os tipos</option>
          {facets.types.map((item) => <option key={item.label} value={item.label}>{labelType(item.label)} ({item.value})</option>)}
        </select>
        <select value={filters.domain} onChange={(event) => setFilters((current) => ({ ...current, domain: event.target.value }))}>
          <option value="">Todos os sites</option>
          {facets.domains.map((item) => <option key={item.label} value={item.label}>{item.label} ({item.value})</option>)}
        </select>
        <div className="dateDuo">
          <label>De<input type="date" value={filters.from} onChange={(event) => setFilters((current) => ({ ...current, from: event.target.value }))} /></label>
          <label>Até<input type="date" value={filters.to} onChange={(event) => setFilters((current) => ({ ...current, to: event.target.value }))} /></label>
        </div>
        <div className="presetGrid">
          {["comment", "video", "search", "access"].map((type) => (
            <button key={type} onClick={() => setFilters((current) => ({ ...current, type }))}>{labelType(type)}</button>
          ))}
        </div>
        <button className="secondaryAction" onClick={() => setFilters(initialFilters)}>Limpar filtros</button>
      </section>
    </aside>
  );
}

function ImportProgress({ job }: { job: ImportJob }) {
  const percent = job.filesFound > 0 ? Math.min(100, Math.round((job.filesProcessed / job.filesFound) * 100)) : 0;
  return (
    <div className="importProgress">
      <div><Loader2 className="spin" size={15} /><span>{job.message}</span></div>
      <div className="progressTrack"><span style={{ width: `${percent}%` }} /></div>
      <small>{job.filesFound > 0 ? `${job.filesProcessed}/${job.filesFound} arquivos · ${job.eventCount.toLocaleString("pt-BR")} eventos` : "Preparando..."}</small>
      <div className="importStats">
        {job.currentSource && <span>{job.currentSource}</span>}
        {!!job.eventsPerMinute && <span>{compactNumber(job.eventsPerMinute)}/min</span>}
        {!!job.bytesRead && <span>{formatBytes(job.bytesRead)}</span>}
        {!!job.elapsedSeconds && <span>{formatDuration(job.elapsedSeconds)}</span>}
        {job.estimatedRemainingSeconds != null && job.estimatedRemainingSeconds > 0 && <span>ETA {formatDuration(job.estimatedRemainingSeconds)}</span>}
      </div>
      {job.currentFile && <code title={job.currentFile}>{job.currentFile}</code>}
    </div>
  );
}

function InsightCanvas({ metrics, extra, setFilters }: { metrics: Metrics | null; extra: ExtraData; setFilters: React.Dispatch<React.SetStateAction<FiltersState>> }) {
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

function EventStream({ events, privacy, selectedEventId, onSelect }: { events: EventRow[]; privacy: boolean; selectedEventId: number | null; onSelect: (event: EventRow) => void }) {
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

function EntityPanel({ event, privacy, tags, collections, postJson }: { event: EventRow | null; privacy: boolean; tags: Array<{ id: number; name: string; color: string }>; collections: Array<{ id: number; name: string }>; postJson: (url: string, body: Record<string, unknown>) => Promise<any> }) {
  const [eventTags, setEventTags] = useState<number[]>([]);
  const [note, setNote] = useState("");
  const [noteSaved, setNoteSaved] = useState(true);
  const [newTag, setNewTag] = useState("");
  const [addedCollections, setAddedCollections] = useState<number[]>([]);

  useEffect(() => {
    if (!event) return;
    let alive = true;
    Promise.all([
      fetch(`/api/events/tags?eventId=${event.id}`).then(readJson),
      fetch(`/api/notes?eventId=${event.id}`).then(readJson)
    ]).then(([tagRows, noteRow]) => {
      if (!alive) return;
      setEventTags(Array.isArray(tagRows) ? tagRows.map((t: any) => t.id) : []);
      setNote(noteRow?.note ?? "");
      setNoteSaved(true);
      setAddedCollections([]);
    }).catch(() => {});
    return () => { alive = false; };
  }, [event?.id]);

  if (!event) return <aside className="entityPanel emptyPanel">Selecione um registro.</aside>;
  const current = event;

  async function toggleTag(tagId: number) {
    const has = eventTags.includes(tagId);
    setEventTags((cur) => has ? cur.filter((id) => id !== tagId) : [...cur, tagId]);
    await postJson(has ? "/api/tags/remove" : "/api/tags/apply", { eventId: current.id, tagId });
  }
  async function createAndApply() {
    const name = newTag.trim();
    if (!name) return;
    const created = await postJson("/api/tags", { name });
    setNewTag("");
    if (created?.id) {
      await postJson("/api/tags/apply", { eventId: current.id, tagId: created.id });
      setEventTags((cur) => cur.includes(created.id) ? cur : [...cur, created.id]);
    }
  }
  async function addToCollection(collectionId: number) {
    setAddedCollections((cur) => cur.includes(collectionId) ? cur : [...cur, collectionId]);
    await postJson("/api/collections/add", { eventId: current.id, collectionId });
  }
  async function saveNote() {
    await postJson("/api/notes", { eventId: current.id, note });
    setNoteSaved(true);
  }

  return (
    <aside className="entityPanel">
      <div className="panelHeader">
        <EventGlyph type={current.type} />
        <div><span>{labelType(current.type)}</span><h2>{mask(current.title || "Registro", privacy)}</h2></div>
      </div>
      <dl className="detailGrid">
        <div><dt>Data</dt><dd>{formatDateTime(current.timestamp)}</dd></div>
        <div><dt>Fonte</dt><dd>{current.source}</dd></div>
        <div><dt>Domínio</dt><dd>{current.domain ?? "sem domínio"}</dd></div>
        <div><dt>Arquivo</dt><dd>{current.file_path ?? "-"}</dd></div>
      </dl>
      {current.text && <p className="detailText">{mask(current.text, privacy)}</p>}
      {current.url && <a className="detailLink" href={current.url} target="_blank" rel="noreferrer">{mask(current.url, privacy)}</a>}

      <div className="panelSection">
        <span className="panelSectionLabel"><Tag size={13} /> Tags</span>
        <div className="tagChips">
          {tags.map((tag) => {
            const on = eventTags.includes(tag.id);
            return (
              <button key={tag.id} className={on ? "tagChip on" : "tagChip"} style={on ? { borderColor: tag.color, background: `${tag.color}22` } : undefined} onClick={() => toggleTag(tag.id)}>
                {on && <Check size={11} />} {tag.name}
              </button>
            );
          })}
          {tags.length === 0 && <small className="muted">Crie tags na aba Organizar.</small>}
        </div>
        <div className="inlineForm">
          <input value={newTag} onChange={(e) => setNewTag(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") createAndApply(); }} placeholder="Nova tag" />
          <button onClick={createAndApply} title="Criar e aplicar"><Plus size={14} /></button>
        </div>
      </div>

      <div className="panelSection">
        <span className="panelSectionLabel"><BookOpen size={13} /> Adicionar a coleção</span>
        <div className="tagChips">
          {collections.map((collection) => (
            <button key={collection.id} className={addedCollections.includes(collection.id) ? "tagChip on" : "tagChip"} onClick={() => addToCollection(collection.id)}>
              {addedCollections.includes(collection.id) ? <Check size={11} /> : <Plus size={11} />} {collection.name}
            </button>
          ))}
          {collections.length === 0 && <small className="muted">Crie coleções na aba Organizar.</small>}
        </div>
      </div>

      <div className="panelSection">
        <span className="panelSectionLabel"><NotebookText size={13} /> Nota</span>
        <textarea className="noteEditor" value={note} onChange={(e) => { setNote(e.target.value); setNoteSaved(false); }} placeholder="Escreva uma nota sobre este registro…" />
        <button className="secondaryAction" onClick={saveNote} disabled={noteSaved}>{noteSaved ? "Nota salva" : "Salvar nota"}</button>
      </div>
    </aside>
  );
}

function TimelineView({ days, events, privacy, onSelect, onOpenDay }: { days: Array<{ day: string; total: number }>; events: EventRow[]; privacy: boolean; onSelect: (event: EventRow) => void; onOpenDay: (day: string) => void }) {
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

function MemoryLine({ event, privacy }: { event: EventRow; privacy: boolean }) {
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

function MemoriesView({ active, privacy, onOpenDay }: { active: boolean; privacy: boolean; onOpenDay: (day: string) => void }) {
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

function DayPanel({ day, events, loading, privacy, onClose }: { day: string; events: EventRow[]; loading: boolean; privacy: boolean; onClose: () => void }) {
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

const WEEKDAY_LABELS = ["Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb"];

function monthLabel(ym: string) {
  const [y, m] = ym.split("-").map(Number);
  if (!y || !m) return ym;
  return `${(PT_MONTHS[m - 1] ?? "").slice(0, 3)}/${String(y).slice(2)}`;
}

function PatternsView({ active, query, privacy, setFilters }: { active: boolean; query: string; privacy: boolean; setFilters: React.Dispatch<React.SetStateAction<FiltersState>> }) {
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

// Thumbnails come straight from YouTube's public CDN by video id — no API key needed.
function ytThumb(videoId: string) {
  return `https://i.ytimg.com/vi/${videoId}/mqdefault.jpg`;
}

function YtVideoLine({ videoId, title, meta, value, interactions, privacy, onClick }: {
  videoId: string;
  title: string | null;
  meta: string;
  value: number;
  interactions: number;
  privacy: boolean;
  onClick: () => void;
}) {
  return (
    <button className="ytVideoRow" onClick={onClick} title="Ver histórico deste vídeo">
      {privacy
        ? <div className="ytThumb ytThumbHidden"><EyeOff size={16} /></div>
        : <img className="ytThumb" loading="lazy" src={ytThumb(videoId)} alt="" onError={(event) => { (event.target as HTMLImageElement).style.visibility = "hidden"; }} />}
      <div className="ytVideoBody">
        <span className="pageTitle">{mask(title || videoId, privacy)}</span>
        <span className="pageUrl">{meta}</span>
      </div>
      <span className="ytVideoMetric" title={`Aberto/assistido ${value} ${value === 1 ? "vez" : "vezes"}${interactions > 0 ? ` · ${interactions} interações suas (comentários, chat, posts)` : ""}`}>
        <strong>{value}×</strong>
        <small>assistido/aberto</small>
        {interactions > 0 && <small>{interactions} interações</small>}
      </span>
    </button>
  );
}

const CHANNEL_STATUS: Record<string, { label: string; color: string }> = {
  ativo: { label: "ainda acompanha", color: "#34d399" },
  abandonado: { label: "abandonado", color: "#fb7185" },
  novo: { label: "descoberta recente", color: "#67e8f9" }
};

function YouTubeView({ active, query, privacy, onOpenDay }: { active: boolean; query: string; privacy: boolean; onOpenDay: (day: string) => void }) {
  const [data, setData] = useState<YtReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [videoSearch, setVideoSearch] = useState("");
  const [videoRows, setVideoRows] = useState<YtVideo[] | null>(null);
  const [videoLoading, setVideoLoading] = useState(false);
  const [videoDetail, setVideoDetail] = useState<YtVideoDetail | null>(null);
  const [channelDetail, setChannelDetail] = useState<YtChannelDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  useEffect(() => {
    if (!active) return;
    let alive = true;
    setLoading(true);
    fetch(`/api/youtube?${query}&limit=30`)
      .then(readJson)
      .then((payload) => { if (alive) setData(payload); })
      .catch(() => {})
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [active, query]);

  useEffect(() => {
    if (!active) return;
    if (!videoSearch.trim()) { setVideoRows(null); return; }
    let alive = true;
    setVideoLoading(true);
    const id = window.setTimeout(() => {
      fetch(`/api/youtube/videos?${query}&search=${encodeURIComponent(videoSearch.trim())}&limit=40`)
        .then(readJson)
        .then((rows) => { if (alive) setVideoRows(Array.isArray(rows) ? rows : []); })
        .catch(() => {})
        .finally(() => { if (alive) setVideoLoading(false); });
    }, 260);
    return () => { alive = false; window.clearTimeout(id); };
  }, [active, query, videoSearch]);

  async function openVideo(videoId: string) {
    setDetailLoading(true);
    setChannelDetail(null);
    try {
      setVideoDetail(await fetch(`/api/youtube/video?id=${encodeURIComponent(videoId)}`).then(readJson));
    } finally {
      setDetailLoading(false);
    }
  }

  async function openChannel(name: string) {
    setDetailLoading(true);
    setVideoDetail(null);
    try {
      setChannelDetail(await fetch(`/api/youtube/channel?name=${encodeURIComponent(name)}&${query}`).then(readJson));
    } finally {
      setDetailLoading(false);
    }
  }

  if (loading && !data) return <div className="emptyState"><Loader2 className="spin" size={18} /> Analisando seu YouTube…</div>;
  if (!data) return <div className="emptyState">Sem dados do YouTube neste recorte.</div>;

  const timeline = data.timeline.map((item) => ({ name: item.label, value: item.value }));
  const videos = videoRows ?? data.topVideos;

  return (
    <div className="siteView">
      <section className="chartPanel span2 siteHeader">
        <div>
          <div className="siteTitle"><Youtube size={18} /><h2>Seu ecossistema YouTube</h2></div>
          <p>Vídeos, YouTube Music, comentários, chats e visitas ao site, tudo junto.{loading ? " · atualizando…" : ""}</p>
        </div>
      </section>

      <section className="chartPanel span2">
        <PanelTitle icon={<Sparkles size={16} />} title="Resumo" />
        <div className="insightStrip">
          <div className="insightCard"><span>Eventos</span><strong>{compactNumber(data.summary.total)}</strong></div>
          <div className="insightCard"><span>Vídeos únicos</span><strong>{compactNumber(data.summary.videos)}</strong></div>
          <div className="insightCard"><span>Canais</span><strong>{compactNumber(data.summary.channels)}</strong></div>
          <div className="insightCard"><span>Comentários</span><strong>{compactNumber(data.summary.comments)}</strong></div>
          <div className="insightCard"><span>Dias ativos</span><strong>{compactNumber(data.summary.activeDays)}</strong></div>
          <div className="insightCard"><span>Primeira atividade</span><strong>{formatDate(data.summary.firstSeen)}</strong></div>
        </div>
      </section>

      <section className="chartPanel span2">
        <PanelTitle icon={<CalendarDays size={16} />} title="Atividade mensal" />
        <ResponsiveContainer width="100%" height={190}>
          <AreaChart data={timeline}>
            <defs>
              <linearGradient id="ytPulse" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="#fb7185" stopOpacity={0.7} />
                <stop offset="100%" stopColor="#fb7185" stopOpacity={0.02} />
              </linearGradient>
            </defs>
            <CartesianGrid stroke="var(--grid)" vertical={false} />
            <XAxis dataKey="name" tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} />
            <YAxis tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} width={42} />
            <ChartTooltip contentStyle={{ background: "var(--surface-strong)", border: "1px solid var(--border)", color: "var(--text)" }} />
            <Area type="monotone" dataKey="value" stroke="#fb7185" strokeWidth={2} fill="url(#ytPulse)" />
          </AreaChart>
        </ResponsiveContainer>
      </section>

      <section className="chartPanel span2">
        <PanelTitle icon={<Search size={16} />} title="Vídeos e músicas — quantas vezes e a primeira vez" />
        <div className="siteSearchBox">
          <Search size={16} />
          <input value={videoSearch} onChange={(event) => setVideoSearch(event.target.value)} placeholder="Buscar um vídeo, uma música ou um canal (ex.: one punch man)" />
          {videoSearch && <button className="iconButton" onClick={() => setVideoSearch("")} title="Limpar"><X size={14} /></button>}
        </div>
        <div className="pageList">
          {videoLoading && <p className="siteHint"><Loader2 className="spin" size={13} /> Buscando…</p>}
          {videos.map((video) => (
            <YtVideoLine
              key={video.videoId}
              videoId={video.videoId}
              title={video.title}
              meta={`${video.channel ? `${video.channel} · ` : ""}primeira vez ${formatDayShort(video.firstDay)} · última ${formatDayShort(video.lastDay)}`}
              value={video.value}
              interactions={video.interactions}
              privacy={privacy}
              onClick={() => openVideo(video.videoId)}
            />
          ))}
          {!videoLoading && videos.length === 0 && <p className="siteHint">{videoSearch ? "Nada encontrado para essa busca." : "Nenhum vídeo neste recorte."}</p>}
        </div>
      </section>

      <section className="chartPanel">
        <PanelTitle icon={<TrendingUp size={16} />} title="Canais que você mais viu" />
        <div className="rankList">
          {data.topChannels.map((channel) => (
            <button key={channel.channel} onClick={() => openChannel(channel.channel)} title="Ver a história deste canal">
              <span>{mask(channel.channel, privacy)}</span>
              <strong>{compactNumber(channel.value)}</strong>
            </button>
          ))}
          {data.topChannels.length === 0 && <p className="siteHint">Nenhum canal identificado neste recorte.</p>}
        </div>
      </section>

      <section className="chartPanel">
        <PanelTitle icon={<History size={16} />} title="Trajetória — acompanhou e abandonou" />
        <div className="phaseList">
          {data.channelPhases.map((phase) => {
            const peakMax = Math.max(1, ...phase.series.map((point) => point.value));
            const status = CHANNEL_STATUS[phase.status] ?? CHANNEL_STATUS.ativo;
            return (
              <div key={phase.label} className="phaseRow">
                <div className="phaseHead">
                  <strong>{mask(phase.label, privacy)}</strong>
                  <span>{phase.total.toLocaleString("pt-BR")}</span>
                </div>
                <div className="phaseStatusLine">
                  <span className="siteTag" style={{ color: status.color, borderColor: status.color }}>{status.label}</span>
                  <span>{phase.first ? monthLabel(phase.first) : ""} → {phase.last ? monthLabel(phase.last) : ""}</span>
                </div>
                <div className="phaseSpark">
                  {phase.series.map((point) => (
                    <div key={point.ym} className="phaseBar" title={`${point.ym}: ${point.value.toLocaleString("pt-BR")}`} style={{ height: `${6 + (point.value / peakMax) * 34}px`, opacity: point.ym === phase.peak ? 1 : 0.5 }} />
                  ))}
                </div>
              </div>
            );
          })}
          {data.channelPhases.length === 0 && <p className="siteHint">Sem canais suficientes.</p>}
        </div>
      </section>

      {(videoDetail || channelDetail || detailLoading) && (
        <YtDetailPanel
          videoDetail={videoDetail}
          channelDetail={channelDetail}
          loading={detailLoading}
          privacy={privacy}
          onOpenChannel={openChannel}
          onOpenDay={onOpenDay}
          onClose={() => { setVideoDetail(null); setChannelDetail(null); }}
        />
      )}
    </div>
  );
}

// Slide-in detail for one video (every encounter, year by year) or one channel (monthly
// rhythm and most-watched videos). Reuses the DayPanel overlay chrome.
function YtDetailPanel({ videoDetail, channelDetail, loading, privacy, onOpenChannel, onOpenDay, onClose }: {
  videoDetail: YtVideoDetail | null;
  channelDetail: YtChannelDetail | null;
  loading: boolean;
  privacy: boolean;
  onOpenChannel: (name: string) => void;
  onOpenDay: (day: string) => void;
  onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const title = videoDetail ? (videoDetail.summary.title || videoDetail.videoId) : channelDetail?.channel ?? "";
  return (
    <div className="dayOverlay" onClick={onClose}>
      <aside className="dayPanel" onClick={(event) => event.stopPropagation()}>
        <header className="dayPanelHead">
          <div>
            <h3><Youtube size={17} /> {mask(title, privacy)}</h3>
            {videoDetail && (
              <small>
                assistido/aberto {videoDetail.summary.views.toLocaleString("pt-BR")} {videoDetail.summary.views === 1 ? "vez" : "vezes"}
                {videoDetail.summary.interactions > 0 ? ` · ${videoDetail.summary.interactions.toLocaleString("pt-BR")} interações suas` : ""}
                {" "}· {videoDetail.summary.days.toLocaleString("pt-BR")} dias
                {videoDetail.summary.firstSeen ? ` · primeira vez ${formatDateTime(videoDetail.summary.firstSeen)}` : ""}
              </small>
            )}
            {channelDetail && (
              <small>
                {channelDetail.summary.total.toLocaleString("pt-BR")} eventos · {channelDetail.summary.videos.toLocaleString("pt-BR")} vídeos · {channelDetail.summary.days.toLocaleString("pt-BR")} dias
                {channelDetail.summary.firstSeen ? ` · desde ${formatDate(channelDetail.summary.firstSeen)}` : ""}
              </small>
            )}
          </div>
          <button className="iconButton" onClick={onClose} title="Fechar (Esc)"><X size={16} /></button>
        </header>
        <div className="dayPanelBody">
          {loading && <div className="emptyState"><Loader2 className="spin" size={18} /> Carregando…</div>}

          {!loading && videoDetail && (
            <>
              {!privacy && (
                <img
                  className="ytThumbBanner"
                  src={ytThumb(videoDetail.videoId)}
                  alt=""
                  onError={(event) => { (event.target as HTMLImageElement).style.display = "none"; }}
                />
              )}
              <div className="insightStrip">
                {videoDetail.summary.channel && (
                  <button className="insightCard" onClick={() => onOpenChannel(videoDetail.summary.channel!)} title="Ver o canal" style={{ cursor: "pointer" }}>
                    <span>Canal</span><strong>{mask(videoDetail.summary.channel, privacy)}</strong>
                  </button>
                )}
                <button
                  className="insightCard"
                  disabled={!videoDetail.summary.firstDay}
                  onClick={() => videoDetail.summary.firstDay && onOpenDay(videoDetail.summary.firstDay)}
                  title="Abrir esse dia inteiro"
                  style={{ cursor: videoDetail.summary.firstDay ? "pointer" : "default" }}
                >
                  <span>Primeira vez</span><strong>{formatDateTime(videoDetail.summary.firstSeen)}</strong>
                </button>
                <button
                  className="insightCard"
                  disabled={!videoDetail.summary.lastDay}
                  onClick={() => videoDetail.summary.lastDay && onOpenDay(videoDetail.summary.lastDay)}
                  title="Abrir esse dia inteiro"
                  style={{ cursor: videoDetail.summary.lastDay ? "pointer" : "default" }}
                >
                  <span>Última vez</span><strong>{formatDateTime(videoDetail.summary.lastSeen)}</strong>
                </button>
              </div>
              <ResponsiveContainer width="100%" height={140}>
                <BarChart data={videoDetail.byYear.map((row) => ({ name: row.year, value: row.value }))}>
                  <XAxis dataKey="name" tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} />
                  <YAxis tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} width={36} />
                  <ChartTooltip contentStyle={{ background: "var(--surface-strong)", border: "1px solid var(--border)", color: "var(--text)" }} />
                  <Bar dataKey="value" fill="#fb7185" radius={[6, 6, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
              <div className="rankList">
                {videoDetail.byType.map((item) => (
                  <div className="rankStatic" key={item.label}><span>{labelType(item.label)}</span><strong>{item.value.toLocaleString("pt-BR")}</strong></div>
                ))}
              </div>
              {!privacy && (
                <a className="detailLink" href={`https://www.youtube.com/watch?v=${videoDetail.videoId}`} target="_blank" rel="noreferrer">abrir no YouTube</a>
              )}
              <div className="memoryLines">
                {videoDetail.events.map((event) => <MemoryLine key={event.id} event={event} privacy={privacy} />)}
              </div>
            </>
          )}

          {!loading && channelDetail && (
            <>
              <ResponsiveContainer width="100%" height={150}>
                <AreaChart data={channelDetail.timeline.map((item) => ({ name: item.label, value: item.value }))}>
                  <XAxis dataKey="name" tick={{ fill: "var(--muted)", fontSize: 10 }} tickLine={false} axisLine={false} />
                  <YAxis tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} width={36} />
                  <ChartTooltip contentStyle={{ background: "var(--surface-strong)", border: "1px solid var(--border)", color: "var(--text)" }} />
                  <Area type="monotone" dataKey="value" stroke="#fb7185" strokeWidth={2} fill="#fb718533" />
                </AreaChart>
              </ResponsiveContainer>
              <div className="pageList">
                {channelDetail.topVideos.map((video) => (
                  <YtVideoLine
                    key={video.videoId}
                    videoId={video.videoId}
                    title={video.title}
                    meta={`${formatDayShort(video.firstDay)} → ${formatDayShort(video.lastDay)}`}
                    value={video.value}
                    interactions={video.interactions}
                    privacy={privacy}
                    onClick={() => { if (!privacy) window.open(`https://www.youtube.com/watch?v=${video.videoId}`, "_blank", "noreferrer"); }}
                  />
                ))}
              </div>
            </>
          )}
        </div>
      </aside>
    </div>
  );
}

const TOPIC_SUGGESTIONS: Array<{ name: string; keywords: string }> = [
  { name: "Mangá & Manhwa", keywords: "manga, manhwa, manhua, webtoon, scan, capítulo" },
  { name: "Anime", keywords: "anime, otaku, crunchyroll, dublado, episódio" },
  { name: "Games", keywords: "gameplay, minecraft, jogo, game, steam, boss" },
  { name: "Música", keywords: "música, playlist, lyrics, mv, clipe oficial" },
  { name: "Programação", keywords: "programação, java, python, javascript, github, código" },
  { name: "Estudos", keywords: "enem, vestibular, matemática, física, resumo, aula" }
];

function TopicsView({ active, query, privacy }: { active: boolean; query: string; privacy: boolean }) {
  const [topics, setTopics] = useState<Topic[]>([]);
  const [name, setName] = useState("");
  const [keywords, setKeywords] = useState("");
  const [selected, setSelected] = useState<{ topic: Topic | null; name: string; keywords: string } | null>(null);
  const [report, setReport] = useState<TopicReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function loadTopics() {
    const rows = await fetch("/api/topics").then(readJson).catch(() => null);
    setTopics(Array.isArray(rows) ? rows : []);
  }

  useEffect(() => {
    if (active) void loadTopics();
  }, [active]);

  useEffect(() => {
    if (!active || !selected) { setReport(null); return; }
    let alive = true;
    setLoading(true);
    setError("");
    fetch(`/api/topics/report?${query}&keywords=${encodeURIComponent(selected.keywords)}&limit=20`)
      .then(async (res) => {
        const payload = await readJson(res);
        if (!res.ok) throw new Error(payload?.error ?? "Falha ao analisar o assunto.");
        if (alive) setReport(payload);
      })
      .catch((err) => { if (alive) setError(err instanceof Error ? err.message : "Erro inesperado."); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [active, query, selected?.keywords]);

  async function saveTopic(topicName: string, topicKeywords: string) {
    const clean = topicKeywords.trim();
    if (!clean) return;
    const res = await fetch("/api/topics", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: topicName.trim() || "Assunto", keywords: clean })
    });
    const created = await readJson(res);
    setName("");
    setKeywords("");
    await loadTopics();
    if (created?.id) setSelected({ topic: created, name: created.name, keywords: created.keywords });
  }

  async function removeTopic(topic: Topic) {
    if (!window.confirm(`Excluir o assunto "${topic.name}"? Os eventos não são afetados.`)) return;
    await fetch("/api/topics/delete", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ id: topic.id })
    });
    if (selected?.topic?.id === topic.id) setSelected(null);
    await loadTopics();
  }

  const timeline = (report?.timeline ?? []).map((item) => ({ name: item.label, value: item.value }));

  return (
    <section className="organizeLayout">
      <div className="organizeSidebar">
        <div className="chartPanel">
          <PanelTitle icon={<Hash size={16} />} title="Seus assuntos" />
          <div className="inlineForm">
            <input value={name} onChange={(event) => setName(event.target.value)} placeholder="Nome (ex.: Mangá & Anime)" />
          </div>
          <div className="inlineForm">
            <input
              value={keywords}
              onChange={(event) => setKeywords(event.target.value)}
              onKeyDown={(event) => { if (event.key === "Enter") void saveTopic(name, keywords); }}
              placeholder="Palavras-chave separadas por vírgula"
            />
            <button onClick={() => void saveTopic(name, keywords)} title="Salvar assunto"><Plus size={16} /></button>
          </div>
          {keywords.trim() && (
            <button className="secondaryAction" onClick={() => setSelected({ topic: null, name: name.trim() || "Prévia", keywords })}>
              Testar sem salvar
            </button>
          )}
          <div className="orgList">
            {topics.map((topic) => (
              <button key={topic.id} className={selected?.topic?.id === topic.id ? "orgItem on" : "orgItem"} onClick={() => setSelected({ topic, name: topic.name, keywords: topic.keywords })}>
                <span className="tagDot" style={{ background: topic.color }} /> {topic.name}
              </button>
            ))}
            {topics.length === 0 && <small className="muted">Nenhum assunto salvo ainda.</small>}
          </div>
        </div>

        <div className="chartPanel">
          <PanelTitle icon={<Sparkles size={16} />} title="Sugestões" />
          <div className="orgList">
            {TOPIC_SUGGESTIONS.map((suggestion) => (
              <button key={suggestion.name} className="orgItem" title={suggestion.keywords} onClick={() => { setName(suggestion.name); setKeywords(suggestion.keywords); setSelected({ topic: null, name: suggestion.name, keywords: suggestion.keywords }); }}>
                <Plus size={13} /> {suggestion.name}
              </button>
            ))}
          </div>
          <small className="muted">Clique para testar; ajuste as palavras-chave e salve.</small>
        </div>
      </div>

      <div className="organizeMain">
        {!selected ? (
          <div className="emptyState">
            Um assunto agrupa tudo que você viu, leu, buscou e comentou sobre um tema — em todas as fontes ao mesmo tempo.
            Crie um com palavras-chave (ex.: manga, manhwa, anime) ou use uma sugestão ao lado.
          </div>
        ) : (
          <div className="siteView">
            <section className="chartPanel span2 siteHeader">
              <div>
                <div className="siteTitle"><Hash size={18} /><h2>{selected.name}</h2>{!selected.topic && <span className="siteTag">prévia</span>}</div>
                <p>{selected.keywords}{loading ? " · analisando…" : ""}</p>
              </div>
              <div className="siteRefine">
                {!selected.topic && <button className="secondaryAction" onClick={() => void saveTopic(selected.name, selected.keywords)}>Salvar assunto</button>}
                {selected.topic && <button className="linkDanger" onClick={() => void removeTopic(selected.topic!)}>Excluir</button>}
              </div>
            </section>

            {error && <div className="errorBanner span2">{error}</div>}

            {report && (
              <>
                <section className="chartPanel span2">
                  <PanelTitle icon={<Sparkles size={16} />} title="Resumo" />
                  <div className="insightStrip">
                    <div className="insightCard"><span>Eventos</span><strong>{compactNumber(report.summary.total)}</strong></div>
                    <div className="insightCard"><span>Dias ativos</span><strong>{compactNumber(report.summary.activeDays)}</strong></div>
                    <div className="insightCard"><span>Sites</span><strong>{compactNumber(report.summary.domains)}</strong></div>
                    <div className="insightCard"><span>Começou em</span><strong>{formatDate(report.summary.firstSeen)}</strong></div>
                    <div className="insightCard"><span>Último registro</span><strong>{formatDate(report.summary.lastSeen)}</strong></div>
                  </div>
                </section>

                <section className="chartPanel span2">
                  <PanelTitle icon={<CalendarDays size={16} />} title="O assunto ao longo do tempo" />
                  <ResponsiveContainer width="100%" height={180}>
                    <AreaChart data={timeline}>
                      <defs>
                        <linearGradient id="topicPulse" x1="0" x2="0" y1="0" y2="1">
                          <stop offset="0%" stopColor="#a78bfa" stopOpacity={0.7} />
                          <stop offset="100%" stopColor="#a78bfa" stopOpacity={0.02} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid stroke="var(--grid)" vertical={false} />
                      <XAxis dataKey="name" tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} />
                      <YAxis tick={{ fill: "var(--muted)", fontSize: 11 }} tickLine={false} axisLine={false} width={42} />
                      <ChartTooltip contentStyle={{ background: "var(--surface-strong)", border: "1px solid var(--border)", color: "var(--text)" }} />
                      <Area type="monotone" dataKey="value" stroke="#a78bfa" strokeWidth={2} fill="url(#topicPulse)" />
                    </AreaChart>
                  </ResponsiveContainer>
                </section>

                <section className="chartPanel">
                  <PanelTitle icon={<Globe2 size={16} />} title="Onde acontece" />
                  <div className="rankList">
                    {report.topDomains.map((item) => (
                      <div className="rankStatic" key={item.label}><span>{item.label}</span><strong>{item.value.toLocaleString("pt-BR")}</strong></div>
                    ))}
                    {report.topDomains.length === 0 && <p className="siteHint">Nenhum site neste recorte.</p>}
                  </div>
                </section>

                <section className="chartPanel">
                  <PanelTitle icon={<Youtube size={16} />} title="Canais deste assunto" />
                  <div className="rankList">
                    {report.topChannels.map((item) => (
                      <div className="rankStatic" key={item.channel}><span>{mask(item.channel, privacy)}</span><strong>{item.value.toLocaleString("pt-BR")}</strong></div>
                    ))}
                    {report.topChannels.length === 0 && <p className="siteHint">Nenhum canal identificado.</p>}
                  </div>
                </section>

                <section className="chartPanel">
                  <PanelTitle icon={<Search size={16} />} title="O que você buscou" />
                  <div className="rankList">
                    {report.searches.map((item, index) => (
                      <div className="rankStatic" key={index}><span>{privacy ? "•••" : (item.title ?? "(sem título)")}</span><strong>{item.value}×</strong></div>
                    ))}
                    {report.searches.length === 0 && <p className="siteHint">Nenhuma busca sobre o assunto.</p>}
                  </div>
                </section>

                <section className="chartPanel">
                  <PanelTitle icon={<History size={16} />} title="Páginas que marcaram" />
                  <div className="pageList">
                    {report.topPages.map((page, index) => (
                      <a key={(page.url ?? "") + index} className="pageRow" href={privacy ? undefined : page.url ?? undefined} target="_blank" rel="noreferrer" title={page.url ?? ""}>
                        <span className="pageTitle">{mask(page.title || page.url || "Sem título", privacy)}</span>
                        <span className="pageUrl">{formatDayShort(page.firstDay)} → {formatDayShort(page.lastDay)} · {page.days} dias</span>
                        <strong>{page.value}×</strong>
                      </a>
                    ))}
                    {report.topPages.length === 0 && <p className="siteHint">Nenhuma página com URL.</p>}
                  </div>
                </section>

                <section className="chartPanel span2">
                  <PanelTitle icon={<Clock size={16} />} title="Registros recentes" />
                  <div className="memoryLines">
                    {report.recent.map((event) => <MemoryLine key={event.id} event={event} privacy={privacy} />)}
                  </div>
                </section>
              </>
            )}
          </div>
        )}
      </div>
    </section>
  );
}

function CalendarView({
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

function SiteView({ initialDomain }: { initialDomain?: string }) {
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

function OrganizePanel({ filters, savedFilters, tags, collections, postJson, privacy, onSelect }: any) {
  const [tagName, setTagName] = useState("");
  const [collectionName, setCollectionName] = useState("");
  const [selection, setSelection] = useState<{ kind: "tag" | "collection"; id: number; name: string } | null>(null);
  const [items, setItems] = useState<EventRow[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!selection) { setItems([]); return; }
    let alive = true;
    setLoading(true);
    const url = selection.kind === "tag"
      ? `/api/events/by-tag?tagId=${selection.id}&limit=500`
      : `/api/collections/events?collectionId=${selection.id}&limit=500`;
    fetch(url).then(readJson)
      .then((rows) => { if (alive) setItems(Array.isArray(rows) ? rows : []); })
      .catch(() => {})
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [selection?.kind, selection?.id]);

  return (
    <section className="organizeLayout">
      <div className="organizeSidebar">
        <div className="chartPanel">
          <PanelTitle icon={<Tag size={16} />} title="Tags" />
          <div className="inlineForm">
            <input value={tagName} onChange={(e) => setTagName(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter" && tagName.trim()) postJson("/api/tags", { name: tagName.trim() }).then(() => setTagName("")); }} placeholder="Nova tag" />
            <button onClick={() => tagName.trim() && postJson("/api/tags", { name: tagName.trim() }).then(() => setTagName(""))}><Plus size={16} /></button>
          </div>
          <div className="orgList">
            {tags.map((tag: any) => (
              <button key={tag.id} className={selection?.kind === "tag" && selection.id === tag.id ? "orgItem on" : "orgItem"} onClick={() => setSelection({ kind: "tag", id: tag.id, name: tag.name })}>
                <span className="tagDot" style={{ background: tag.color }} /> {tag.name}
              </button>
            ))}
            {tags.length === 0 && <small className="muted">Nenhuma tag ainda.</small>}
          </div>
        </div>

        <div className="chartPanel">
          <PanelTitle icon={<BookOpen size={16} />} title="Coleções" />
          <div className="inlineForm">
            <input value={collectionName} onChange={(e) => setCollectionName(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter" && collectionName.trim()) postJson("/api/collections", { name: collectionName.trim() }).then(() => setCollectionName("")); }} placeholder="Nova coleção" />
            <button onClick={() => collectionName.trim() && postJson("/api/collections", { name: collectionName.trim() }).then(() => setCollectionName(""))}><Plus size={16} /></button>
          </div>
          <div className="orgList">
            {collections.map((collection: any) => (
              <button key={collection.id} className={selection?.kind === "collection" && selection.id === collection.id ? "orgItem on" : "orgItem"} onClick={() => setSelection({ kind: "collection", id: collection.id, name: collection.name })}>
                <BookOpen size={13} /> {collection.name}
              </button>
            ))}
            {collections.length === 0 && <small className="muted">Nenhuma coleção ainda.</small>}
          </div>
        </div>

        <div className="chartPanel">
          <PanelTitle icon={<Filter size={16} />} title="Filtros salvos" />
          <button className="primaryAction" onClick={() => postJson("/api/saved-filters", { name: `Filtro ${new Date().toLocaleDateString("pt-BR")}`, ...filters })}>Salvar filtro atual</button>
          <List items={savedFilters.map((item: any) => `${item.name} (${item.query || "sem busca"})`)} />
        </div>
      </div>

      <div className="organizeMain">
        {!selection ? (
          <div className="emptyState">Escolha uma tag ou coleção para ver seus registros. Aplique tags e coleções a partir do painel de um registro (aba Explorar).</div>
        ) : (
          <>
            <div className="organizeMainHead">
              <h3>{selection.kind === "tag" ? <Tag size={15} /> : <BookOpen size={15} />} {selection.name}</h3>
              <small>{loading ? "carregando…" : `${items.length} registros`}</small>
            </div>
            {loading ? (
              <div className="emptyState"><Loader2 className="spin" size={18} /> Carregando…</div>
            ) : items.length === 0 ? (
              <div className="emptyState">Nada aqui ainda. Aplique {selection.kind === "tag" ? "esta tag" : "esta coleção"} a partir do painel de um registro.</div>
            ) : (
              <EventStream events={items} privacy={privacy} selectedEventId={null} onSelect={onSelect} />
            )}
          </>
        )}
      </div>
    </section>
  );
}

function ExportPanel({ query, privacy }: { query: string; privacy: boolean }) {
  const suffix = query ? `?${query}&` : "?";
  return (
    <section className="exportGrid">
      {["json", "csv", "pdf"].map((format) => (
        <a className="exportButton" key={format} href={`/api/export${suffix}format=${format}`} target="_blank" rel="noreferrer">
          <Download size={18} /> Exportar {format.toUpperCase()}
        </a>
      ))}
      <p>{privacy ? "Modo privacidade ativo na tela. Downloads preservam os dados completos." : "Downloads usam os filtros atuais."}</p>
    </section>
  );
}

function FileBrowser({ browser, loading, currentPath, onOpen, onClose, onSelect }: { browser: BrowseResponse | null; loading: boolean; currentPath: string; onOpen: (path?: string) => void; onClose: () => void; onSelect: (path: string) => void }) {
  return (
    <div className="fileBrowser">
      <div className="fileBrowserTop">
        <button title="Voltar" onClick={() => browser?.parent ? onOpen(browser.parent) : onOpen("")} disabled={loading}><ChevronLeft size={16} /></button>
        <span title={browser?.path || "Raízes"}>{browser?.path ? browser.path.replace("/imports", "Pasta montada") : "Pastas disponíveis"}</span>
        <button onClick={onClose}><X size={14} /></button>
      </div>
      {browser?.importable && browser.path && <button className="selectCurrent" onClick={() => onSelect(browser.path)}><FolderInput size={16} /> Usar esta pasta</button>}
      <div className="browserList">
        {loading && <div className="browserEmpty"><Loader2 className="spin" size={16} /> Carregando</div>}
        {!loading && browser?.entries.length === 0 && <div className="browserEmpty">Nada importável nesta pasta.</div>}
        {!loading && browser?.entries.map((entry) => (
          <button key={entry.path} className={entry.path === currentPath ? "browserItem active" : "browserItem"} onClick={() => entry.type === "directory" ? onOpen(entry.path) : onSelect(entry.path)} title={entry.path}>
            {entry.root ? <HardDrive size={16} /> : entry.type === "archive" ? <FileArchive size={16} /> : <FolderOpen size={16} />}
            <span>{entry.name}</span>
            {entry.importable && <strong>Takeout</strong>}
          </button>
        ))}
      </div>
    </div>
  );
}

function Metric({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return <div className="metricCard"><div>{icon}</div><span>{label}</span><strong>{value}</strong></div>;
}

function PanelTitle({ icon, title }: { icon: React.ReactNode; title: string }) {
  return <div className="panelTitle">{icon}<h3>{title}</h3></div>;
}

function EventGlyph({ type }: { type: string }) {
  const Icon = type === "video" ? Youtube : type === "search" ? Search : type === "comment" ? NotebookText : type === "map" ? Globe2 : Database;
  return <div className={`eventGlyph type-${type}`}><Icon size={16} /></div>;
}

function List({ items }: { items: string[] }) {
  return <ul className="simpleList">{items.slice(0, 28).map((item) => <li key={item}>{item}</li>)}</ul>;
}

function activeChips(filters: FiltersState): Array<{ key: keyof FiltersState; label: string }> {
  return [
    filters.source && { key: "source" as const, label: `source:${filters.source}` },
    filters.type && { key: "type" as const, label: `type:${filters.type}` },
    filters.domain && { key: "domain" as const, label: `site:${filters.domain}` },
    filters.from && { key: "from" as const, label: `after:${filters.from}` },
    filters.to && { key: "to" as const, label: `before:${filters.to}` }
  ].filter(Boolean) as Array<{ key: keyof FiltersState; label: string }>;
}

function appendFilterValue(current: string, value: string) {
  const parts = current.split(",").map((item) => item.trim()).filter(Boolean);
  if (parts.includes(value)) return parts.filter((item) => item !== value).join(",");
  return [...parts, value].join(",");
}

function shouldBrowseBeforeImport(path: string) {
  const normalized = path.trim().replace(/\/+$/, "");
  return !normalized || normalized === "/imports" || normalized === "Pasta montada";
}

function mask(value: string | null, privacy: boolean) {
  if (!value || !privacy) return value;
  if (value.length <= 12) return "••••••";
  return `${value.slice(0, 4)}••••••${value.slice(-4)}`;
}

function stripMarks(value: string | null) {
  return value ? value.replaceAll("<mark>", "").replaceAll("</mark>", "") : value;
}

const APP_TIMEZONE = "America/Sao_Paulo";

const PT_MONTHS = ["janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"];

// Builds labels from the plain date parts so a date-only string is never shifted by timezone.
function monthDayLabel(md: string) {
  const [m, d] = md.split("-").map(Number);
  if (!m || !d) return md;
  return `${d} de ${PT_MONTHS[m - 1] ?? ""}`;
}

function formatDayShort(day?: string | null) {
  if (!day) return "—";
  const [y, m, d] = day.split("-");
  if (!y || !m || !d) return day;
  return `${d}/${m}/${y.slice(2)}`;
}

function formatDayLong(day: string) {
  const [y, m, d] = day.split("-").map(Number);
  if (!y || !m || !d) return day;
  return `${d} de ${PT_MONTHS[m - 1] ?? ""} de ${y}`;
}

function formatDate(value?: string | null) {
  if (!value) return "Sem data";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Sem data";
  return new Intl.DateTimeFormat("pt-BR", { dateStyle: "medium", timeZone: APP_TIMEZONE }).format(date);
}

function formatDateTime(value?: string | null) {
  if (!value) return "Sem data";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Sem data";
  return new Intl.DateTimeFormat("pt-BR", { dateStyle: "medium", timeStyle: "short", timeZone: APP_TIMEZONE }).format(date);
}

function compactNumber(value: number) {
  return new Intl.NumberFormat("pt-BR", { notation: "compact", maximumFractionDigits: 1 }).format(value);
}

function formatBytes(value: number) {
  return new Intl.NumberFormat("pt-BR", { style: "unit", unit: "megabyte", maximumFractionDigits: 1 }).format(value / 1024 / 1024);
}

function formatDuration(seconds: number) {
  const safe = Math.max(0, Math.round(seconds));
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  if (hours > 0) return `${hours}h ${minutes}min`;
  if (minutes > 0) return `${minutes}min`;
  return `${safe}s`;
}

function labelType(value: string) {
  const labels: Record<string, string> = {
    access: "Acesso",
    activity: "Atividade",
    comment: "Comentário",
    email: "Email",
    map: "Mapa",
    post: "Postagem",
    search: "Pesquisa",
    video: "Vídeo"
  };
  return labels[value] ?? value;
}

createRoot(document.getElementById("root")!).render(<App />);
