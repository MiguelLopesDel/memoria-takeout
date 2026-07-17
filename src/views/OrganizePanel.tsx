import { useEffect, useState } from "react";
import { BookOpen, Filter, Loader2, Plus, Tag } from "lucide-react";
import { readJson } from "../api";
import { EventStream } from "../components/EventStream";
import { List, PanelTitle } from "../components/primitives";
import type { EventRow } from "../types";

export function OrganizePanel({ filters, savedFilters, tags, collections, postJson, privacy, onSelect }: any) {
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
