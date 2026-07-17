// Shapes of the /api responses and the app-wide filter state. These mirror the JSON the
// backend produces today; RFC 0003 will eventually generate/verify them against Java records.

export type Status = {
  dbPath: string;
  eventCount: number;
  defaultTakeoutPath: string;
  latestImport: null | { source_path: string; imported_at: string; event_count: number };
};
export type Facet = { label: string; value: number };
export type Metrics = {
  summary: { total: number; domains: number; firstSeen: string | null; lastSeen: string | null };
  byType: Facet[];
  bySource: Facet[];
  topDomains: Facet[];
  timeline: Facet[];
};
export type EventRow = {
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
export type OnThisDay = {
  monthDay: string;
  years: Array<{ year: string; total: number; events: EventRow[] }>;
  flashback: EventRow | null;
};
export type Phase = { label: string; total: number; first: string | null; last: string | null; peak: string | null; peakValue: number; months: number; series: Array<{ ym: string; value: number }> };
export type RecurringSearch = { title: string | null; value: number; days: number; firstDay: string | null; lastDay: string | null };
export type Patterns = {
  rhythm: Array<{ weekday: number; hour: number; value: number }>;
  streaks: { activeDays: number; firstDay: string | null; lastDay: string | null; top: Array<{ length: number; start: string; end: string }> };
  phases: Phase[];
  returns: SiteReturn[];
  searches: RecurringSearch[];
};
export type Facets = { sources: Facet[]; types: Facet[]; domains: Facet[] };
export type BrowseEntry = { name: string; path: string; type: "directory" | "archive"; root: boolean; importable: boolean };
export type BrowseResponse = { path: string; parent: string | null; entries: BrowseEntry[]; importable: boolean };
export type ExtraData = {
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
export type SitePage = { url: string | null; title: string | null; value: number; lastSeen?: string | null };
export type SiteReturn = { url: string | null; title: string | null; days: number; value: number; firstDay: string | null; lastDay: string | null };
export type SiteReport = {
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
export type FiltersState = { q: string; source: string; type: string; domain: string; from: string; to: string };
export type WorkspaceTab = "explore" | "memorias" | "padroes" | "youtube" | "assuntos" | "timeline" | "calendar" | "site" | "organize" | "export";
export type YtVideo = { videoId: string; title: string | null; channel: string | null; value: number; interactions: number; days: number; firstDay: string | null; lastDay: string | null; firstSeen: string | null };
export type YtChannel = { channel: string; value: number; videos: number; days: number; months: number; firstDay: string | null; lastDay: string | null };
export type YtChannelPhase = Phase & { status: "ativo" | "abandonado" | "novo" };
export type YtReport = {
  summary: { total: number; videos: number; channels: number; comments: number; activeDays: number; firstSeen: string | null; lastSeen: string | null };
  timeline: Facet[];
  byType: Facet[];
  topVideos: YtVideo[];
  topChannels: YtChannel[];
  channelPhases: YtChannelPhase[];
};
export type YtVideoDetail = {
  videoId: string;
  summary: { total: number; views: number; interactions: number; days: number; title: string | null; channel: string | null; firstSeen: string | null; lastSeen: string | null; firstDay: string | null; lastDay: string | null };
  byYear: Array<{ year: string; value: number }>;
  byType: Facet[];
  events: Array<EventRow & { channel?: string | null }>;
};
export type YtChannelDetail = {
  channel: string;
  summary: { total: number; videos: number; days: number; firstSeen: string | null; lastSeen: string | null };
  timeline: Facet[];
  topVideos: Array<{ videoId: string; title: string | null; value: number; interactions: number; firstDay: string | null; lastDay: string | null }>;
};
export type Topic = { id: number; name: string; color: string; keywords: string; created_at: string };
export type TopicReport = {
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
export type ImportJob = {
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

export const initialFilters: FiltersState = { q: "", source: "", type: "", domain: "", from: "", to: "" };
