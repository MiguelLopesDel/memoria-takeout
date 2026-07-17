// Pure display helpers: pt-BR date/number formatting, type labels, the privacy mask and
// small filter-state utilities. Nothing here touches the network or React.

import type { FiltersState } from "./types";

const APP_TIMEZONE = "America/Sao_Paulo";

const PT_MONTHS = ["janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"];

export function monthLabel(ym: string) {
  const [y, m] = ym.split("-").map(Number);
  if (!y || !m) return ym;
  return `${(PT_MONTHS[m - 1] ?? "").slice(0, 3)}/${String(y).slice(2)}`;
}

// Builds labels from the plain date parts so a date-only string is never shifted by timezone.
export function monthDayLabel(md: string) {
  const [m, d] = md.split("-").map(Number);
  if (!m || !d) return md;
  return `${d} de ${PT_MONTHS[m - 1] ?? ""}`;
}

export function formatDayShort(day?: string | null) {
  if (!day) return "—";
  const [y, m, d] = day.split("-");
  if (!y || !m || !d) return day;
  return `${d}/${m}/${y.slice(2)}`;
}

export function formatDayLong(day: string) {
  const [y, m, d] = day.split("-").map(Number);
  if (!y || !m || !d) return day;
  return `${d} de ${PT_MONTHS[m - 1] ?? ""} de ${y}`;
}

export function formatDate(value?: string | null) {
  if (!value) return "Sem data";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Sem data";
  return new Intl.DateTimeFormat("pt-BR", { dateStyle: "medium", timeZone: APP_TIMEZONE }).format(date);
}

export function formatDateTime(value?: string | null) {
  if (!value) return "Sem data";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Sem data";
  return new Intl.DateTimeFormat("pt-BR", { dateStyle: "medium", timeStyle: "short", timeZone: APP_TIMEZONE }).format(date);
}

export function compactNumber(value: number) {
  return new Intl.NumberFormat("pt-BR", { notation: "compact", maximumFractionDigits: 1 }).format(value);
}

export function formatBytes(value: number) {
  return new Intl.NumberFormat("pt-BR", { style: "unit", unit: "megabyte", maximumFractionDigits: 1 }).format(value / 1024 / 1024);
}

export function formatDuration(seconds: number) {
  const safe = Math.max(0, Math.round(seconds));
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  if (hours > 0) return `${hours}h ${minutes}min`;
  if (minutes > 0) return `${minutes}min`;
  return `${safe}s`;
}

export function labelType(value: string) {
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

export function mask(value: string | null, privacy: boolean) {
  if (!value || !privacy) return value;
  if (value.length <= 12) return "••••••";
  return `${value.slice(0, 4)}••••••${value.slice(-4)}`;
}

export function stripMarks(value: string | null) {
  return value ? value.replaceAll("<mark>", "").replaceAll("</mark>", "") : value;
}

export function activeChips(filters: FiltersState): Array<{ key: keyof FiltersState; label: string }> {
  return [
    filters.source && { key: "source" as const, label: `source:${filters.source}` },
    filters.type && { key: "type" as const, label: `type:${filters.type}` },
    filters.domain && { key: "domain" as const, label: `site:${filters.domain}` },
    filters.from && { key: "from" as const, label: `after:${filters.from}` },
    filters.to && { key: "to" as const, label: `before:${filters.to}` }
  ].filter(Boolean) as Array<{ key: keyof FiltersState; label: string }>;
}

export function appendFilterValue(current: string, value: string) {
  const parts = current.split(",").map((item) => item.trim()).filter(Boolean);
  if (parts.includes(value)) return parts.filter((item) => item !== value).join(",");
  return [...parts, value].join(",");
}

export function shouldBrowseBeforeImport(path: string) {
  const normalized = path.trim().replace(/\/+$/, "");
  return !normalized || normalized === "/imports" || normalized === "Pasta montada";
}
