import { useEffect, useState } from "react";
import { BookOpen, Check, NotebookText, Plus, Tag } from "lucide-react";
import { readJson } from "../api";
import { formatDateTime, labelType, mask } from "../format";
import { EventGlyph } from "./primitives";
import type { EventRow } from "../types";

export function EntityPanel({ event, privacy, tags, collections, postJson }: { event: EventRow | null; privacy: boolean; tags: Array<{ id: number; name: string; color: string }>; collections: Array<{ id: number; name: string }>; postJson: (url: string, body: Record<string, unknown>) => Promise<any> }) {
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
