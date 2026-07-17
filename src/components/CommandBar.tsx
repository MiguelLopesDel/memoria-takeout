import React from "react";
import * as Tooltip from "@radix-ui/react-tooltip";
import { Command, Database, EyeOff, Loader2, PanelLeftClose, PanelLeftOpen, X } from "lucide-react";
import { activeChips, compactNumber } from "../format";
import type { FiltersState, Status } from "../types";

export function CommandBar({
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
