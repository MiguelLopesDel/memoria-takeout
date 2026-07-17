// The left rail: import dock (path input, in-UI file browser, live progress) and the
// always-on filter controls. FileBrowser and ImportProgress are private to this module.

import React from "react";
import {
  Archive,
  ChevronLeft,
  Database,
  FileArchive,
  Filter,
  FolderInput,
  FolderOpen,
  HardDrive,
  Loader2,
  X
} from "lucide-react";
import { compactNumber, formatBytes, formatDate, formatDuration, labelType, shouldBrowseBeforeImport } from "../format";
import { initialFilters } from "../types";
import type { BrowseResponse, Facets, FiltersState, ImportJob, Status } from "../types";

export function FilterRail({
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
